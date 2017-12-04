package bdv.bigcat.viewer.atlas.opendialog;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import com.google.gson.JsonElement;

import bdv.bigcat.viewer.atlas.data.DataSource;
import bdv.bigcat.viewer.atlas.data.LabelDataSource;
import bdv.bigcat.viewer.atlas.data.LabelDataSourceFromDelegates;
import bdv.bigcat.viewer.atlas.data.RandomAccessibleIntervalDataSource;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentWithHistory;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileTypeMatcher;
import bdv.util.volatiles.VolatileViews;
import gnu.trove.map.hash.TLongLongHashMap;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.AbstractVolatileRealType;
import net.imglib2.util.Util;

public class BackendDialogN5 implements BackendDialog
{

	private static final String RESOLUTION_KEY = "resolution";

	private static final String OFFSET_KEY = "offset";

	private final SimpleObjectProperty< String > n5 = new SimpleObjectProperty<>();

	private final SimpleObjectProperty< String > dataset = new SimpleObjectProperty<>();

	private final SimpleObjectProperty< String > error = new SimpleObjectProperty<>();

	private final SimpleDoubleProperty resX = new SimpleDoubleProperty( Double.NaN );

	private final SimpleDoubleProperty resY = new SimpleDoubleProperty( Double.NaN );

	private final SimpleDoubleProperty resZ = new SimpleDoubleProperty( Double.NaN );

	private final SimpleDoubleProperty offX = new SimpleDoubleProperty( Double.NaN );

	private final SimpleDoubleProperty offY = new SimpleDoubleProperty( Double.NaN );

	private final SimpleDoubleProperty offZ = new SimpleDoubleProperty( Double.NaN );

	private final ObservableList< String > datasetChoices = FXCollections.observableArrayList();
	{
		n5.addListener( ( obs, oldv, newv ) -> {
			if ( newv != null && new File( newv ).exists() )
			{
				this.error.set( null );
				final List< File > files = new ArrayList<>();
				findSubdirectories( new File( newv ), dir -> new File( dir, "attributes.json" ).exists(), files::add );
				if ( datasetChoices.size() == 0 )
					datasetChoices.add( "" );
				final URI baseURI = new File( newv ).toURI();
				datasetChoices.setAll( files.stream().map( File::toURI ).map( baseURI::relativize ).map( URI::getPath ).collect( Collectors.toList() ) );
				if ( !oldv.equals( newv ) )
					this.dataset.set( null );
			}
			else
			{
				datasetChoices.clear();
				error.set( "No valid path for n5 root." );
			}
		} );
		dataset.addListener( ( obs, oldv, newv ) -> {
			if ( newv != null )
			{
				error.set( null );
				try
				{
					final HashMap< String, JsonElement > attributes = new N5FSReader( n5.get() ).getAttributes( newv );
					final double[] resolution = attributes.containsKey( RESOLUTION_KEY ) ? IntStream.range( 0, 3 ).mapToDouble( i -> attributes.get( RESOLUTION_KEY ).getAsJsonArray().get( i ).getAsDouble() ).toArray() : DoubleStream.generate( () -> Double.NaN ).limit( 3 ).toArray();
					resX.set( resolution[ 0 ] );
					resY.set( resolution[ 1 ] );
					resZ.set( resolution[ 2 ] );

					final double[] offset = attributes.containsKey( OFFSET_KEY ) ? IntStream.range( 0, 3 ).mapToDouble( i -> attributes.get( OFFSET_KEY ).getAsJsonArray().get( i ).getAsDouble() ).toArray() : DoubleStream.generate( () -> Double.NaN ).limit( 3 ).toArray();
					offX.set( offset[ 0 ] );
					offY.set( offset[ 1 ] );
					offZ.set( offset[ 2 ] );

				}
				catch ( final IOException e )
				{
					// TODO just ignore?
				}

			}
			else
				error.set( "No n5 dataset found at " + n5 + " " + dataset );
		} );
		datasetChoices.addListener( ( ListChangeListener< String > ) change -> {
			while ( change.next() )
				if ( datasetChoices.size() == 0 )
					error.set( "No datasets found for n5 root: " + n5.get() );
		} );
		n5.set( "" );
	}

	@Override
	public Node getDialogNode()
	{
		final TextField n5Field = new TextField( n5.get() );
		n5Field.setMinWidth( 0 );
		n5Field.setMaxWidth( Double.POSITIVE_INFINITY );
		final ComboBox< String > datasetDropDown = new ComboBox<>( datasetChoices );
		n5Field.textProperty().bindBidirectional( n5 );
		datasetDropDown.valueProperty().bindBidirectional( dataset );
		datasetDropDown.setMinWidth( n5Field.getMinWidth() );
		datasetDropDown.setPrefWidth( n5Field.getPrefWidth() );
		datasetDropDown.setMaxWidth( n5Field.getMaxWidth() );
		final GridPane grid = new GridPane();
		grid.add( new Label( "n5" ), 0, 0 );
		grid.add( new Label( "data set" ), 0, 1 );
		grid.add( n5Field, 1, 0 );
		grid.add( datasetDropDown, 1, 1 );
		GridPane.setHgrow( n5Field, Priority.ALWAYS );
		GridPane.setHgrow( datasetDropDown, Priority.ALWAYS );
		final Button button = new Button( "Browse" );
		button.setOnAction( event -> {
			final DirectoryChooser directoryChooser = new DirectoryChooser();
			final File initDir = new File( n5.get() );
			directoryChooser.setInitialDirectory( initDir.exists() && initDir.isDirectory() ? initDir : FileSystems.getDefault().getPath( "." ).toFile() );
			final File directory = directoryChooser.showDialog( grid.getScene().getWindow() );
			Optional.ofNullable( directory ).map( File::getAbsolutePath ).ifPresent( n5::set );
		} );
		grid.add( button, 2, 0 );
		return grid;
	}

	@Override
	public ObjectProperty< String > errorMessage()
	{
		return error;
	}

	public static void findSubdirectories( final File file, final Predicate< File > check, final Consumer< File > action )
	{
		if ( check.test( file ) )
			action.accept( file );
		else
			Arrays.stream( file.listFiles() ).filter( File::isDirectory ).forEach( f -> findSubdirectories( f, check, action ) );
	}

	@Override
	public < T extends RealType< T > & NativeType< T >, V extends RealType< V > > Optional< DataSource< T, V > > getRaw(
			final String name,
			final double[] resolution,
			final double[] offset,
			final SharedQueue sharedQueue,
			final int priority ) throws IOException
	{
		final String group = n5.get();
		final N5FSReader reader = new N5FSReader( group );
		final String dataset = this.dataset.get();

		return Optional.of( DataSource.createN5RawSource( name, reader, dataset, resolution, offset, sharedQueue, priority ) );
	}

	@Override
	public Optional< LabelDataSource< ?, ? > > getLabels(
			final String name,
			final double[] resolution,
			final double[] offset,
			final SharedQueue sharedQueue,
			final int priority ) throws IOException
	{
		final String group = n5.get();
		final N5FSReader reader = new N5FSReader( group );
		final String dataset = this.dataset.get();
//		final DataSource< ?, ? > source = DataSource.createN5RawSource( name, reader, dataset, new double[] { 1, 1, 1 }, sharedQueue, priority );
		final DataType type = reader.getDatasetAttributes( dataset ).getDataType();
		if ( isLabelType( type ) )
		{
			if ( isIntegerType( type ) )
				return Optional.of( ( LabelDataSource< ?, ? > ) getIntegerTypeSource( name, reader, dataset, resolution, offset, sharedQueue, priority ) );
			else if ( isLabelMultisetType( type ) )
				return Optional.empty();
			else
				return Optional.empty();
		}
		else
			return Optional.empty();
	}

	private static final < T extends IntegerType< T > & NativeType< T >, V extends AbstractVolatileRealType< T, V > > LabelDataSource< T, V > getIntegerTypeSource(
			final String name,
			final N5FSReader reader,
			final String dataset,
			final double[] resolution,
			final double[] offset,
			final SharedQueue sharedQueue,
			final int priority ) throws IOException
	{
		final RandomAccessibleInterval< T > raw = N5Utils.openVolatile( reader, dataset );
		final T t = Util.getTypeFromInterval( raw );
		@SuppressWarnings( "unchecked" )
		final V v = ( V ) VolatileTypeMatcher.getVolatileTypeForType( t );

		final AffineTransform3D rawTransform = new AffineTransform3D();
		rawTransform.set(
				resolution[ 0 ], 0, 0, offset[ 0 ],
				0, resolution[ 1 ], 0, offset[ 1 ],
				0, 0, resolution[ 2 ], offset[ 2 ] );

		@SuppressWarnings( "unchecked" )
		final RandomAccessibleIntervalDataSource< T, V > source =
				new RandomAccessibleIntervalDataSource< T, V >(
						new RandomAccessibleInterval[] { raw },
						new RandomAccessibleInterval[] { VolatileViews.wrapAsVolatile( raw, sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) ) },
						new AffineTransform3D[] { rawTransform },
						interpolation -> new NearestNeighborInterpolatorFactory<>(),
						interpolation -> new NearestNeighborInterpolatorFactory<>(),
						t::createVariable,
						v::createVariable,
						name );
		final FragmentSegmentAssignmentWithHistory frag = new FragmentSegmentAssignmentWithHistory( new TLongLongHashMap(), action -> {}, () -> {
			try
			{
				Thread.sleep( 1000 );
			}
			catch ( final InterruptedException e )
			{
				e.printStackTrace();
			}
			return null;
		} );
		final LabelDataSourceFromDelegates< T, V > delegated = new LabelDataSourceFromDelegates<>( source, frag );
		return delegated;
	}

	private static boolean isLabelType( final DataType type )
	{
		return isLabelMultisetType( type ) || isIntegerType( type );
	}

	private static boolean isLabelMultisetType( final DataType type )
	{
		return false;
	}

	private static boolean isIntegerType( final DataType type )
	{
		switch ( type )
		{
		case INT8:
		case INT16:
		case INT32:
		case INT64:
		case UINT8:
		case UINT16:
		case UINT32:
		case UINT64:
			return true;
		default:
			return false;
		}
	}

	@Override
	public DoubleProperty resolutionX()
	{
		return this.resX;
	}

	@Override
	public DoubleProperty resolutionY()
	{
		return this.resY;
	}

	@Override
	public DoubleProperty resolutionZ()
	{
		return this.resZ;
	}

	@Override
	public DoubleProperty offsetX()
	{
		return this.offX;
	}

	@Override
	public DoubleProperty offsetY()
	{
		return this.offY;
	}

	@Override
	public DoubleProperty offsetZ()
	{
		return this.offZ;
	}

}