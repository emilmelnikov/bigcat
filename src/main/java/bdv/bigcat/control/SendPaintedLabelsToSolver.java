package bdv.bigcat.control;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;

import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import com.google.protobuf.ByteString;

import bdv.bigcat.control.GrowingStoreRandomAccessible.Factory;
import bdv.bigcat.control.SolverMessages.Start.Builder;
import bdv.labels.labelset.Label;
import bdv.labels.labelset.LabelMultisetType;
import bdv.util.sparse.MapSparseRandomAccessible;
import bdv.util.sparse.MapSparseRandomAccessible.HashableLongArray;
import bdv.viewer.ViewerPanel;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Point;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.algorithm.fill.Filter;
import net.imglib2.algorithm.fill.FloodFill;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.algorithm.neighborhood.DiamondShape.NeighborhoodsAccessible;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Pair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * Persist fragment segment assignments, painted labels, viewer state, and
 * flattened labels.
 *
 * @author Philipp Hanslovsky
 */
public class SendPaintedLabelsToSolver
{
	final private ViewerPanel viewer;

	final private RandomAccessibleInterval< LabelMultisetType > labelMultisetSource;

	final private RandomAccessibleInterval< LongType > labelSource;

	final private AffineTransform3D labelTransform;

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

	private final int[] cellDimensions;

	private final Socket socket;

	public SendPaintedLabelsToSolver(
			final ViewerPanel viewer,
			final RandomAccessibleInterval< LabelMultisetType > labelMultisetSource,
			final RandomAccessibleInterval< LongType > labelSource,
			final AffineTransform3D labelTransform,
			final int[] cellDimensions,
			final InputTriggerConfig config,
			final Socket socket )
	{
		this.viewer = viewer;
		this.labelMultisetSource = labelMultisetSource;
		this.labelSource = labelSource;
		this.labelTransform = labelTransform;
		this.cellDimensions = cellDimensions;
		this.inputAdder = config.inputTriggerAdder( inputTriggerMap, "Solver" );
		this.socket = socket;

		new SendLabels( "send painted label to solver", "ctrl shift A button1" ).register();
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

	private < P extends RealLocalizable & RealPositionable > void setCoordinates( final P labelLocation, final int x, final int y )
	{
		labelLocation.setPosition( x, 0 );
		labelLocation.setPosition( y, 1 );
		labelLocation.setPosition( 0, 2 );

		viewer.displayToGlobalCoordinates( labelLocation );

		labelTransform.applyInverse( labelLocation, labelLocation );
	}

	private class SendLabels extends SelfRegisteringBehaviour implements ClickBehaviour
	{
		public SendLabels( final String name, final String... defaultTriggers )
		{
			super( name, defaultTriggers );
		}

		@Override
		public void click( final int x, final int y )
		{

			System.out.println( "Sending Labels!" );

			synchronized ( viewer )
			{
				final RealRandomAccess< LongType > paintAccess =
						Views.interpolate( Views.extendValue( labelSource, new LongType( Label.OUTSIDE ) ), new NearestNeighborInterpolatorFactory<>() ).realRandomAccess();
				setCoordinates( paintAccess, x, y );

				final LongType label = paintAccess.get();
				final long labelLong = label.get();
				System.out.println( labelLong + " " + paintAccess );

				if ( labelLong != Label.TRANSPARENT && labelLong != Label.INVALID && labelLong != Label.OUTSIDE )
				{
					System.out.println( "YEP!" );
					System.out.println( labelLong + " " + paintAccess );
					final long[] initialMin = new long[ 3 ];
					final long[] initialMax = new long[ 3 ];
					final Point seed = new Point( 3 );

					for ( int d = 0; d < 3; ++d )
					{
						final long pos = ( long ) paintAccess.getDoublePosition( d );
						final long min = pos % cellDimensions[ d ];
						initialMin[ d ] = min;
						initialMax[ d ] = min + cellDimensions[ d ] - 1;
						seed.setPosition( pos, d );
					}

					final Factory< BitType > factory = ( min, max, u ) -> {
						final long[] dim = new long[ min.length ];
						long product = max[ 0 ] - min[ 0 ] + 1;
						dim[ 0 ] = product;
						for ( int d = 1; d < max.length; ++d )
						{
							final long currentDim = max[ d ] - min[ d ] + 1;
							product *= currentDim;
							dim[ d ] = currentDim;
						}

						final Img< BitType > result;
						if ( product > Integer.MAX_VALUE )
						{
							result = new CellImgFactory< BitType >( cellDimensions ).create( dim, u );
						}
						else
						{
							result = ArrayImgs.bits( dim );
						}

						return Views.translate( result, min );
					};

					final long[] cellDimensionsLong = new long[] { cellDimensions[ 0 ], cellDimensions[ 1 ], cellDimensions[ 2 ] };
					final HashMap< HashableLongArray, RandomAccessibleInterval< BitType > > hm = new HashMap<>();

					final MapSparseRandomAccessible< BitType > target =
							new MapSparseRandomAccessible<>( new ArrayImgFactory<>(), cellDimensionsLong, new BitType( false ), hm );
					final ExtendedRandomAccessibleInterval< LongType, RandomAccessibleInterval< LongType > > source =
							Views.extendValue( labelSource, new LongType( Label.OUTSIDE ) );
					final BitType fillLabel = new BitType( true );
					final DiamondShape shape = new DiamondShape( 1 );
					final Filter< Pair< LongType, BitType >, Pair< LongType, BitType > > filter = ( lb1, lb2 ) -> {
						return !lb1.getB().get() && lb1.getA().valueEquals( lb2.getA() );
					};
					System.out.println( "Start fill" );
					FloodFill.fill( source, target, seed, fillLabel, shape, filter );
					System.out.println( "Stop fill" );

					final long[] min = new long[] { Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE };
					final long[] max = new long[] { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };

					for ( final HashableLongArray h : hm.keySet() )
					{
						final long[] data = h.getData();
						for ( int d = 0; d < min.length; ++d )
						{
							min[ d ] = Math.min( min[ d ], data[ d ] );
							max[ d ] = Math.max( max[ d ], data[ d ] );
						}
					}

					for ( int d = 0; d < 3; ++d )
					{
						min[ d ] *= cellDimensionsLong[ d ];
						max[ d ] = max[ d ] * cellDimensionsLong[ d ] + cellDimensionsLong[ d ] - 1;
					}

					System.out.println( "min: " + Arrays.toString( min ) );
					System.out.println( "max: " + Arrays.toString( max ) );

					// find "affected" fragments
					final HashSet< Long > inner = new HashSet<>();
					final HashSet< Long > outer = new HashSet<>();
					final HashMap< Long, long[] > innerPoints = new HashMap<>();
					final HashMap< Long, long[] > outerPoints = new HashMap<>();
					for ( final Entry< HashableLongArray, RandomAccessibleInterval< BitType > > entry : hm.entrySet() )
					{
						final RandomAccessibleInterval< BitType > volume = entry.getValue();
						final long[] entryMin = new long[ 3 ];
						final long[] entryMax = new long[ 3 ];
						volume.min( entryMin );
						volume.max( entryMax );
						System.out.println( "Current min: " + Arrays.toString( entryMin ) );
						System.out.println( "current max: " + Arrays.toString( entryMax ) );
						final FinalInterval fi = new FinalInterval( entryMin, entryMax );
						final RandomAccessible< Pair< LabelMultisetType, BitType > > paired =
								Views.pair( Views.extendValue( labelMultisetSource, new LabelMultisetType() ), target );
						final NeighborhoodsAccessible< Pair< LabelMultisetType, BitType > > pairsWithNeighbors = new DiamondShape( 1 ).neighborhoodsRandomAccessible( paired );

						final Cursor< Pair< LabelMultisetType, BitType > > pairCursor = Views.interval( paired, fi ).cursor();
						final Cursor< Neighborhood< Pair< LabelMultisetType, BitType > > > neighborhoodCursor = Views.interval( pairsWithNeighbors, fi ).cursor();

						while ( pairCursor.hasNext() )
						{
							final Pair< LabelMultisetType, BitType > labelsAndMask = pairCursor.next();
							final Neighborhood< Pair< LabelMultisetType, BitType > > neighborhood = neighborhoodCursor.next();
							if ( labelsAndMask.getB().get() )
							{
								final LabelMultisetType labels = labelsAndMask.getA();
								long[] innerPoint = null;
								for ( final bdv.labels.labelset.Multiset.Entry< Label > l : labels.entrySet() )
								{
									final long currentLabel = l.getElement().id();
									inner.add( currentLabel );
									if ( !innerPoints.containsKey( currentLabel ) )
									{
										if ( innerPoint == null )
										{
											innerPoint = new long[ 3 ];
											pairCursor.localize( innerPoint );
										}
									}
									innerPoints.put( currentLabel, innerPoint );
								}


								for ( final Cursor< Pair< LabelMultisetType, BitType > > n = neighborhood.cursor(); n.hasNext(); )
								{
									final Pair< LabelMultisetType, BitType > neighborPair = n.next();
									if ( !neighborPair.getB().get() )
									{
										long[] outerPoint = null;
										for ( final bdv.labels.labelset.Multiset.Entry< Label > l : neighborPair.getA().entrySet() )
										{
											final long currentLabel = l.getElement().id();
											outer.add( currentLabel );
											if ( !outerPoints.containsKey( currentLabel ) )
											{
												if ( outerPoint == null )
												{
													outerPoint = new long[ 3 ];
													pairCursor.localize( outerPoint );
												}
											}
											outerPoints.put( currentLabel, outerPoint );
										}
									}
								}
							}

						}
					}

					final HashSet< Long > overPainted = new HashSet<>();

					for ( final long out : outer )
					{
						System.out.println( out );
					}

					System.out.println( "BLUB" );

					for ( final long in : inner )
					{
						System.out.println( in );
						if ( !outer.contains( in ) )
						{
							overPainted.add( in );
						}
					}

					System.out.println( "BLEB" );

					final long uuid = labelLong;

					final Builder startMessageBuilder = SolverMessages.Start.newBuilder();
					startMessageBuilder.setUuid( Long.toString( uuid ) );
					startMessageBuilder.setId( labelLong );
					for ( int d = 0; d < 3; ++d ) {
						startMessageBuilder.addMin( min[ d ] );
						startMessageBuilder.addMax( max[ d ] );
					}

					startMessageBuilder.addAllContainedIds( inner );
					startMessageBuilder.addAllNeighboringIds( outer );
					startMessageBuilder.addAllCompletelyRemovedIds( overPainted );

					final SolverMessages.Start startMessage =startMessageBuilder.build();

//					socket.send( "start", ZMQ.SNDMORE );
					socket.send( SolverMessages.Wrapper.newBuilder()
							.setType( SolverMessages.Type.START )
							.setStart( startMessage )
							.build().toByteArray(), ZMQ.SNDMORE );
//					final byte[] bboxArray = new byte[ 3 * Long.BYTES + 3 * Long.BYTES ];
//					final ByteBuffer bboxBuffer = ByteBuffer.wrap( bboxArray );
//					for ( final long m : min )
//					{
//						bboxBuffer.putLong( m );
//					}
//					for ( final long m : max )
//					{
//						bboxBuffer.putLong( m );
//					}
//					socket.send( bboxArray, ZMQ.SNDMORE );

					final int cellSize = cellDimensions[0] * cellDimensions[1] * cellDimensions[2];

//					final byte[] bytes = new byte[ cellSize + 3 * Long.BYTES + + 3 * Long.BYTES + 1 * Long.BYTES ]; // mask (cell) + min + max + label

					final byte[] bytes = new byte[ cellSize ];

					final ByteBuffer buffer = ByteBuffer.wrap( bytes );

					final byte ONE = ( byte ) 1;
					final byte ZERO = ( byte ) 0;

					final long[] currentMin = new long[ 3 ];
					final long[] currentMax = new long[ 3 ];

					for ( final Entry< HashableLongArray, RandomAccessibleInterval< BitType > > entry : hm.entrySet() )
					{
						final SolverMessages.Annotation.Builder annotationMessageBuilder = SolverMessages.Annotation.newBuilder();
						annotationMessageBuilder.setId( labelLong );
						annotationMessageBuilder.setUuid( Long.toString( uuid ) );
						boolean sendMessage = false;
						buffer.rewind();

						final RandomAccessibleInterval< BitType > rai = entry.getValue();
						rai.min( currentMin );
						rai.max( currentMax );

						for ( int d = 0; d < currentMin.length; ++d )
						{
							annotationMessageBuilder.addMin( currentMin[ d ] );
							annotationMessageBuilder.addMax( currentMax[ d ] );
						}

//						for ( final long c : currentMin )
//						{
//							buffer.putLong( c );
//						}
//
//						for ( final long c : currentMax )
//						{
//							buffer.putLong( c );
//						}
//						buffer.putLong( labelLong );

						final IntervalView< BitType > cell = Views.interval(
								target,
								currentMin,
								currentMax );
						for ( final BitType bit : cell )
						{
							if ( bit.get() )
							{
								buffer.put( ONE );
								sendMessage = true;
							}
							else
							{
								buffer.put( ZERO );
							}
						}

						if ( sendMessage )
						{
							annotationMessageBuilder.setData( ByteString.copyFrom( bytes ) );
							System.out.println( "Sending message!" );
//							socket.send( bytes, ZMQ.SNDMORE );
							socket.send( SolverMessages.Wrapper.newBuilder()
									.setType( SolverMessages.Type.ANNOTATION )
									.setAnnotation( annotationMessageBuilder )
									.build().toByteArray(), ZMQ.SNDMORE );
						}
					}

//					socket.send( "stop" );
					socket.send( SolverMessages.Wrapper.newBuilder()
							.setType( SolverMessages.Type.STOP )
							.setStop( SolverMessages.Stop.newBuilder().setUuid( Long.toString( uuid ) ).build() )
							.build().toByteArray(), 0 );

				}
			}
		}
	}


}