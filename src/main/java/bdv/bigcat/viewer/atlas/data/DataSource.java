package bdv.bigcat.viewer.atlas.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Supplier;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.img.h5.H5Utils;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;

/**
 *
 * {@link Source} that includes a type <code>D</code> representation that is
 * used for data processing (in contrast to <code>T</code> that is used for
 * visualization).
 *
 */
public interface DataSource< D, T > extends Source< T >
{
	public RandomAccessibleInterval< D > getDataSource( int t, int level );

	public RealRandomAccessible< D > getInterpolatedDataSource( final int t, final int level, final Interpolation method );

	public D getDataType();

	/**
	 * Create a primitive single scale level source without visualization
	 * conversion from an H5 dataset.
	 *
	 * @param name
	 * @param rawFile
	 * @param rawDataset
	 * @param rawCellSize
	 * @param resolution
	 * @param sharedQueue
	 * @param priority
	 * @param typeSupplier
	 * @param volatileTypeSupplier
	 * @return
	 * @throws IOException
	 */
	public static < T extends NativeType< T > & NumericType< T >, V extends NumericType< V > > RandomAccessibleIntervalDataSource< T, V > createH5RawSource(
			final String name,
			final String rawFile,
			final String rawDataset,
			final int[] rawCellSize,
			final double[] resolution,
			final SharedQueue sharedQueue,
			final int priority,
			final Supplier< T > typeSupplier,
			final Supplier< V > volatileTypeSupplier ) throws IOException
	{
		final RandomAccessibleInterval< T > raw = H5Utils.open( HDF5Factory.openForReading( rawFile ), rawDataset, rawCellSize );
		final AffineTransform3D rawTransform = new AffineTransform3D();
		rawTransform.set(
				resolution[ 0 ], 0, 0, 0,
				0, resolution[ 1 ], 0, 0,
				0, 0, resolution[ 2 ], 0 );

		@SuppressWarnings( "unchecked" )
		final RandomAccessibleIntervalDataSource< T, V > rawSource =
				new RandomAccessibleIntervalDataSource< T, V >(
						( RandomAccessibleInterval< T >[] ) new RandomAccessibleInterval[] { raw },
						( RandomAccessibleInterval< V >[] ) new RandomAccessibleInterval[] {
								VolatileViews.wrapAsVolatile( raw, sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) ) },
						new AffineTransform3D[] { rawTransform },
						( interpolation ) -> {
							switch ( ( Interpolation ) interpolation )
							{
							case NLINEAR:
								return new NLinearInterpolatorFactory< T >();
							default:
								return new NearestNeighborInterpolatorFactory< T >();
							}
						},
						( interpolation ) -> {
							switch ( ( Interpolation ) interpolation )
							{
							case NLINEAR:
								return new NLinearInterpolatorFactory< V >();
							default:
								return new NearestNeighborInterpolatorFactory< V >();
							}
						},
						typeSupplier,
						volatileTypeSupplier,
						name );
		return rawSource;
	}

	/**
	 * Create a primitive single scale level source without visualization
	 * conversion from an N5 dataset.
	 *
	 * @param name
	 * @param n5
	 * @param rawDataset
	 * @param resolution
	 * @param sharedQueue
	 * @param priority
	 * @param typeSupplier
	 * @param volatileTypeSupplier
	 * @return
	 * @throws IOException
	 */
	public static < T extends NativeType< T > & NumericType< T >, V extends NumericType< V > > RandomAccessibleIntervalDataSource< T, V > createN5RawSource(
			final String name,
			final N5Reader n5,
			final String dataset,
			final double[] resolution,
			final SharedQueue sharedQueue,
			final int priority,
			final Supplier< T > typeSupplier,
			final Supplier< V > volatileTypeSupplier ) throws IOException
	{
		final RandomAccessibleInterval< T > raw = N5Utils.openVolatile( n5, dataset );

		final AffineTransform3D rawTransform = new AffineTransform3D();
		rawTransform.set(
				resolution[ 0 ], 0, 0, 0,
				0, resolution[ 1 ], 0, 0,
				0, 0, resolution[ 2 ], 0 );

		@SuppressWarnings( "unchecked" )
		final RandomAccessibleIntervalDataSource< T, V > rawSource =
				new RandomAccessibleIntervalDataSource< T, V >(
						( RandomAccessibleInterval< T >[] ) new RandomAccessibleInterval[] { raw },
						( RandomAccessibleInterval< V >[] ) new RandomAccessibleInterval[] {
								VolatileViews.wrapAsVolatile( raw, sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) ) },
						new AffineTransform3D[] { rawTransform },
						( interpolation ) -> {
							switch ( ( Interpolation ) interpolation )
							{
							case NLINEAR:
								return new NLinearInterpolatorFactory< T >();
							default:
								return new NearestNeighborInterpolatorFactory< T >();
							}
						},
						( interpolation ) -> {
							switch ( ( Interpolation ) interpolation )
							{
							case NLINEAR:
								return new NLinearInterpolatorFactory< V >();
							default:
								return new NearestNeighborInterpolatorFactory< V >();
							}
						},
						typeSupplier,
						volatileTypeSupplier,
						name );
		return rawSource;
	}

	/**
	 * Create a primitive multi scale level source without visualization
	 * conversion from an N5 multi scale group.
	 *
	 * @param name
	 * @param n5
	 * @param group
	 * @param resolution
	 * @param sharedQueue
	 * @param priority
	 * @param typeSupplier
	 * @param volatileTypeSupplier
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings( "unchecked" )
	public static < T extends NativeType< T > & NumericType< T >, V extends NumericType< V > > RandomAccessibleIntervalDataSource< T, V > createN5MipmapRawSource(
			final String name,
			final N5Reader n5,
			final String group,
			final double[] resolution,
			final SharedQueue sharedQueue,
			final Supplier< T > typeSupplier,
			final Supplier< V > volatileTypeSupplier ) throws IOException
	{
		final AffineTransform3D rawTransform = new AffineTransform3D();
		rawTransform.set(
				resolution[ 0 ], 0, 0, 0,
				0, resolution[ 1 ], 0, 0,
				0, 0, resolution[ 2 ], 0 );

		final ArrayList< RandomAccessibleInterval< T > > mipmaps = new ArrayList<>();
		final ArrayList< RandomAccessibleInterval< V > > volatileMipmaps = new ArrayList<>();
		final ArrayList< AffineTransform3D > mipmapTransforms = new ArrayList<>();

		final String[] sortedMipmapDatasets =
				Arrays.stream( n5.list( group ) )
				.filter( dataset -> dataset.matches( "s\\d+" ) )
				.sorted()
				.toArray( String[]::new );

		for ( int i = 0; i < sortedMipmapDatasets.length; ++i )
		{
			final RandomAccessibleInterval< T > mipmap = N5Utils.openVolatile( n5, group + "/" + sortedMipmapDatasets[ i ] );
			mipmaps.add( mipmap );
			volatileMipmaps.add( ( RandomAccessibleInterval< V > )VolatileViews.wrapAsVolatile( mipmap, sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, i, true ) ) );
			final long[] downsampleFactors = n5.getAttribute( group + "/" + sortedMipmapDatasets[ i ], "downsamplingFactors", long[].class );
			final AffineTransform3D mipmapTransform = rawTransform.copy();
			if ( downsampleFactors != null )
			{
				mipmapTransform.set(
						resolution[ 0 ] * downsampleFactors[ 0 ], 0, 0, 0.5 * ( downsampleFactors[ 0 ] - 1 ),
						0, resolution[ 1 ] * downsampleFactors[ 1 ], 0, 0.5 * ( downsampleFactors[ 1 ] - 1 ),
						0, 0, resolution[ 2 ] * downsampleFactors[ 2 ], 0.5 * ( downsampleFactors[ 2 ] - 1 ) );
			}
			mipmapTransforms.add( mipmapTransform );
		}

		final RandomAccessibleIntervalDataSource< T, V > rawSource =
				new RandomAccessibleIntervalDataSource< T, V >(
						mipmaps.toArray( new RandomAccessibleInterval[ 0 ] ),
						volatileMipmaps.toArray( new RandomAccessibleInterval[ 0 ] ),
						mipmapTransforms.toArray( new AffineTransform3D[ 0 ] ),
						( interpolation ) -> {
							switch ( ( Interpolation ) interpolation )
							{
							case NLINEAR:
								return new NLinearInterpolatorFactory< T >();
							default:
								return new NearestNeighborInterpolatorFactory< T >();
							}
						},
						( interpolation ) -> {
							switch ( ( Interpolation ) interpolation )
							{
							case NLINEAR:
								return new NLinearInterpolatorFactory< V >();
							default:
								return new NearestNeighborInterpolatorFactory< V >();
							}
						},
						typeSupplier,
						volatileTypeSupplier,
						name );
		return rawSource;
	}
}
