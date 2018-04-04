package bdv.bigcat.control;

import java.awt.event.ActionEvent;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.bigcat.label.FragmentSegmentAssignment;
import bdv.bigcat.label.SegmentAssignment ;
import bdv.bigcat.ui.AbstractARGBStream;
import bdv.viewer.ViewerPanel;

/**
 * Assign the current segment to the confirmed segments group.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ConfirmSegmentController
{
	final protected ViewerPanel viewer;
	final protected SelectionController selectionController;
	final protected FragmentSegmentAssignment assignment;
	final protected SegmentAssignment completeSegments;
	final protected AbstractARGBStream colorStream;
	final protected Wheel modeWheel;

	// for keystroke actions
	private final ActionMap ksActionMap = new ActionMap();
	private final InputMap ksInputMap = new InputMap();
	private final KeyStrokeAdder ksKeyStrokeAdder;

	public ConfirmSegmentController(
			final ViewerPanel viewer,
			final SelectionController selectionController,
			final FragmentSegmentAssignment assignment,
			final SegmentAssignment completeSegments,
			final AbstractARGBStream colorStream,
			final Wheel modeWheel,
			final InputTriggerConfig config,
			final InputActionBindings inputActionBindings )
	{
		this.viewer = viewer;
		this.selectionController = selectionController;
		this.assignment = assignment;
		this.completeSegments = completeSegments;
		this.colorStream = colorStream;
		this.modeWheel = modeWheel;
		ksKeyStrokeAdder = config.keyStrokeAdder( ksInputMap, "confirm segment" );

		new ConfirmSegment( "confirm segment", "U" ).register();
		new AdvanceVisibilityMode( "advance visibility mode", "J" ).register();
		new RegressVisibilityMode( "regress visibility mode", "shift J" ).register();

		inputActionBindings.addActionMap( "confirm segment", ksActionMap );
		inputActionBindings.addInputMap( "confirm segment", ksInputMap );
	}

	private abstract class SelfRegisteringAction extends AbstractNamedAction
	{
		private final String[] defaultTriggers;

		public SelfRegisteringAction( final String name, final String ... defaultTriggers )
		{
			super( name );
			this.defaultTriggers = defaultTriggers;
		}

		public void register()
		{
			put( ksActionMap );
			ksKeyStrokeAdder.put( name(), defaultTriggers );
		}
	}

	private class ConfirmSegment extends SelfRegisteringAction
	{
		public ConfirmSegment( final String name, final String ... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			long activeSegmentId;
			synchronized ( viewer )
			{
				final long activeFragmentId = selectionController.getActiveFragmentId();
				activeSegmentId = assignment.getSegment( activeFragmentId );
				completeSegments.add( activeSegmentId );
				colorStream.clearCache();
			}
			viewer.showMessage( "completed segment " + activeSegmentId );
			viewer.requestRepaint();
		}
	}

	private class AdvanceVisibilityMode extends SelfRegisteringAction
	{
		public AdvanceVisibilityMode( final String name, final String ... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			synchronized ( viewer )
			{
				modeWheel.advance();
			}
			viewer.requestRepaint();
		}
	}

	private class RegressVisibilityMode extends SelfRegisteringAction
	{
		public RegressVisibilityMode( final String name, final String ... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			synchronized ( viewer )
			{
				modeWheel.regress();
			}
			viewer.requestRepaint();
		}
	}
}
