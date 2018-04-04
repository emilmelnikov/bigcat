package bdv.bigcat.control;

import java.awt.Cursor;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.ScrollBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.bigcat.ui.BrushOverlay;
import bdv.bigcat.util.DirtyInterval;
import bdv.img.AccessBoxRandomAccessible;
import bdv.labels.labelset.Label;
import bdv.util.Affine3DHelpers;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.neighborhood.HyperSphereNeighborhood;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.ui.TransformEventHandler;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * A {@link TransformEventHandler} that changes an {@link AffineTransform3D}
 * through a set of {@link Behaviour}s.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class LabelBrushController
{
	final protected ViewerPanel viewer;
	final protected RandomAccessibleInterval< LongType > labels;
	final protected RandomAccessible< LongType > extendedLabels;
	final protected DirtyInterval dirtyLabelsInterval;
	final protected AffineTransform3D labelTransform;
	final protected FragmentSegmentAssignment assignment;
	final protected SelectionController selectionController;
	final protected RealPoint labelLocation;
	final protected BrushOverlay brushOverlay;
	final protected AffineTransform3D viewerTransform = new AffineTransform3D();

	final protected int[] labelsH5CellDimensions;

	protected int brushRadius = 5;

	// for behavioUrs
	private final BehaviourMap behaviourMap = new BehaviourMap();
	private final InputTriggerMap inputTriggerMap = new InputTriggerMap();
	private final InputTriggerAdder inputAdder;

	public BehaviourMap getBehaviourMap()
	{
		return behaviourMap;
	}

	public InputTriggerMap getInputTriggerMap()
	{
		return inputTriggerMap;
	}

	public BrushOverlay getBrushOverlay()
	{
		return brushOverlay;
	}

	/**
	 * Coordinates where mouse dragging started.
	 */
	private int oX, oY;

	public LabelBrushController(
			final ViewerPanel viewer,
			final RandomAccessibleInterval< LongType > labels,
			final DirtyInterval dirtyLabelsInterval,
			final AffineTransform3D labelTransform,
			final FragmentSegmentAssignment assignment,
			final SelectionController selectionController,
			final int[] labelsH5CellDimensions,
			final InputTriggerConfig config)
	{
		this.viewer = viewer;
		this.labels = labels;
		extendedLabels = Views.extendValue( this.labels, new LongType( Label.TRANSPARENT ) );
		this.dirtyLabelsInterval = dirtyLabelsInterval;
		this.labelTransform = labelTransform;
		this.assignment = assignment;
		this.selectionController = selectionController;
		this.labelsH5CellDimensions = labelsH5CellDimensions;
		brushOverlay = new BrushOverlay( viewer );
		inputAdder = config.inputTriggerAdder( inputTriggerMap, "brush" );

		labelLocation = new RealPoint( 3 );

		new Paint( "paint", "SPACE button1" ).register();
		new Erase( "erase", "SPACE button2", "SPACE button3" ).register();
		new ChangeBrushRadius( "change brush radius", "SPACE scroll" ).register();
		new MoveBrush( "move brush", "SPACE" ).register();
	}

	private void setCoordinates( final int x, final int y )
	{
		labelLocation.setPosition( x, 0 );
		labelLocation.setPosition( y, 1 );
		labelLocation.setPosition( 0, 2 );

		viewer.displayToGlobalCoordinates( labelLocation );

		labelTransform.applyInverse( labelLocation, labelLocation );
	}

	private int getNormalAxis()
	{
		viewer.getState().getViewerTransform( viewerTransform );
		int normalAxis = 0;
		final double absDotX = Math.abs( viewerTransform.get( 0, 2 ) );
		final double absDotY = Math.abs( viewerTransform.get( 1, 2 ) );
		final double absDotZ = Math.abs( viewerTransform.get( 2, 2 ) );
		if ( absDotY > absDotX ) {
			normalAxis = 1;
			if ( absDotZ > absDotY )
				normalAxis = 2;
		}
		else if ( absDotZ > absDotX )
			normalAxis = 2;

		return normalAxis;
	}

	private abstract class SelfRegisteringBehaviour implements Behaviour
	{
		private final String name;

		private final String[] defaultTriggers;

		protected String getName()
		{
			return name;
		}

		public SelfRegisteringBehaviour( final String name, final String... defaultTriggers )
		{
			this.name = name;
			this.defaultTriggers = defaultTriggers;
		}

		public void register()
		{
			behaviourMap.put( name, this );
			inputAdder.put( name, defaultTriggers );
		}
	}

	private abstract class AbstractPaintBehavior extends SelfRegisteringBehaviour implements DragBehaviour
	{
		public AbstractPaintBehavior( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		protected void paint(final RealLocalizable coords)
		{
			final AccessBoxRandomAccessible< LongType > accessBoxExtendedLabels = new AccessBoxRandomAccessible<>( extendedLabels );
			final int brushNormalAxis = getNormalAxis();
			final RandomAccessible< LongType > labelSource = Views.hyperSlice( accessBoxExtendedLabels, brushNormalAxis, Math.round( coords.getDoublePosition( brushNormalAxis ) ) );

			final Neighborhood< LongType > sphere =
					HyperSphereNeighborhood.< LongType >factory().create(
							new long[]{
									Math.round( coords.getDoublePosition( brushNormalAxis == 0 ? 1 : 0 ) ),
									Math.round( coords.getDoublePosition( brushNormalAxis != 2 ? 2 : 1 ) ) },
							Math.round( brushRadius / Affine3DHelpers.extractScale( labelTransform, brushNormalAxis == 0 ? 1 : 0 ) ),
							labelSource.randomAccess() );

			for ( final LongType t : sphere )
				t.set( getValue() );

			dirtyLabelsInterval.touch( accessBoxExtendedLabels.createAccessInterval() );
		}

		protected void paint( final int x, final int y )
		{
			setCoordinates( x, y );
			paint( labelLocation );
		}

		protected void paint( final int x1, final int y1, final int x2, final int y2 )
		{
			setCoordinates( x1, y1 );
			final double[] p1 = new double[ 3 ];
			final RealPoint rp1 = RealPoint.wrap( p1 );
			labelLocation.localize( p1 );

			setCoordinates( x2, y2 );
			final double[] d = new double[ 3 ];
			labelLocation.localize( d );

			LinAlgHelpers.subtract( d, p1, d );

			final double l = LinAlgHelpers.length( d );
			LinAlgHelpers.normalize( d );

			for ( int i = 1; i < l; ++i )
			{
				LinAlgHelpers.add( p1, d, p1 );
				paint( rp1 );
			}
			paint( labelLocation );
		}

		abstract protected long getValue();

		@Override
		public void init( final int x, final int y )
		{
			synchronized ( this )
			{
				oX = x;
				oY = y;
			}

			paint( x, y );

			viewer.requestRepaint();

			// System.out.println( getName() + " drag start (" + oX + ", " + oY + ")" );
		}

		@Override
		public void drag( final int x, final int y )
		{
			brushOverlay.setPosition( x, y );

			paint( oX, oY, x, y );

			synchronized ( this )
			{
				oX = x;
				oY = y;
			}

			viewer.requestRepaint();

			// System.out.println( getName() + " drag by (" + dX + ", " + dY + ")" );
		}

		@Override
		public void end( final int x, final int y )
		{
			System.out.println( "modified box: " + Util.printInterval( dirtyLabelsInterval.getDirtyInterval() ) );
		}
	}

	private class Paint extends AbstractPaintBehavior
	{
		public Paint( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		protected long getValue()
		{
			return selectionController.getActiveFragmentId();
		}
	}

	private class Erase extends AbstractPaintBehavior
	{
		public Erase( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		protected long getValue()
		{
			return Label.TRANSPARENT;
		}
	}

	private class ChangeBrushRadius extends SelfRegisteringBehaviour implements ScrollBehaviour
	{
		public ChangeBrushRadius( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void scroll( final double wheelRotation, final boolean isHorizontal, final int x, final int y )
		{
			if ( !isHorizontal )
			{
				if ( wheelRotation < 0 )
					brushRadius += 1;
				else if ( wheelRotation > 0 )
					brushRadius = Math.max( 0, brushRadius - 1 );

				brushOverlay.setRadius( brushRadius );
				// TODO request only overlays to repaint
				viewer.getDisplay().repaint();
			}
		}
	}

	private class MoveBrush extends SelfRegisteringBehaviour implements DragBehaviour
	{
		public MoveBrush( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void init( final int x, final int y )
		{
			brushOverlay.setPosition( x, y );
			brushOverlay.setVisible( true );
			// TODO request only overlays to repaint
			viewer.setCursor( Cursor.getPredefinedCursor( Cursor.CROSSHAIR_CURSOR ) );
			viewer.getDisplay().repaint();
		}

		@Override
		public void drag( final int x, final int y )
		{
			brushOverlay.setPosition( x, y );
		}

		@Override
		public void end( final int x, final int y )
		{
			brushOverlay.setVisible( false );
			// TODO request only overlays to repaint
			viewer.setCursor( Cursor.getPredefinedCursor( Cursor.DEFAULT_CURSOR ) );
			viewer.getDisplay().repaint();

		}
	}
}
