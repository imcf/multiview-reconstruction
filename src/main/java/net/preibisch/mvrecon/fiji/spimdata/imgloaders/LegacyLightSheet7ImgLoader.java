package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.File;
import java.io.IOException;
import java.util.Date;

import ij.IJ;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.ChannelSeparator;
import loci.formats.FormatHandler;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.in.MetadataOptions;
import loci.formats.in.DynamicMetadataOptions;
import loci.formats.in.ZeissCZIReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.LightSheet7;
import net.preibisch.mvrecon.headless.definedataset.LightSheet7MetaData;
import util.ImgLib2Tools;

public class LegacyLightSheet7ImgLoader extends AbstractImgFactoryImgLoader
{
	final File cziFile;
	final AbstractSequenceDescription<?, ?, ?> sequenceDescription;

	// once the metadata is loaded for one view, it is available for all other ones
	LightSheet7MetaData meta;
	boolean isClosed = true;

	public LegacyLightSheet7ImgLoader(
			final File cziFile,
			final ImgFactory< ? extends NativeType< ? > > imgFactory,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		super();
		this.cziFile = cziFile;
		this.sequenceDescription = sequenceDescription;

		setImgFactory( imgFactory );
	}

	public File getCZIFile() { return cziFile; }

	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage( final ViewId view, final boolean normalize )
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtual( getImage( view ) );
		else
			return ImgLib2Tools.convertVirtual( getImage( view ) );
	}

	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view )
	{
		try
		{
			final Img< UnsignedShortType > img = openCZI( new UnsignedShortType(), view );

			if ( img == null )
				throw new RuntimeException( "Could not load '" + cziFile + "' viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() );

			return img;
		}
		catch ( Exception e )
		{
			throw new RuntimeException( "Could not load '" + cziFile + "' viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() + ": " + e );
		}
	}

	@Override
	protected void loadMetaData( final ViewId view )
	{
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading metadata for Lightsheet 7 imgloader not necessary." );
	}

	@Override
	public void finalize()
	{
		IOFunctions.println( "Closing czi: " + cziFile );

		try
		{
			if ( meta != null && meta.getReader() != null )
			{
				meta.getReader().close();
				isClosed = true;
			}
		}
		catch (IOException e) {}
	}

	protected < T extends RealType< T > & NativeType< T > > Img< T > openCZI( final T type, final ViewId view ) throws Exception
	{
		if ( meta == null )
		{
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Investigating file '" + cziFile.getAbsolutePath() + "' (loading metadata)." );

			meta = new LightSheet7MetaData();

			if ( !meta.loadMetaData( cziFile, true ) )
			{
				IOFunctions.println( "Failed to analyze file: '" + cziFile.getAbsolutePath() + "'." );
				meta = null;
				isClosed = true;
				return null;
			}
			else
			{
				isClosed = false;
			}
		}

		final BasicViewDescription< ? > vd = sequenceDescription.getViewDescriptions().get( view );
		final BasicViewSetup vs = vd.getViewSetup();

		final TimePoint t = vd.getTimePoint();
		final Angle a = getAngle( vd );
		final Channel c = getChannel( vd );
		final Illumination i = getIllumination( vd );
		final Tile tile = getTile( vd );

		final int[] dim;

		if ( vs.hasSize() )
		{
			dim = new int[ vs.getSize().numDimensions() ];
			for ( int d = 0; d < vs.getSize().numDimensions(); ++d )
				dim[ d ] = (int)vs.getSize().dimension( d );
		}
		else
		{
			dim = meta.imageSizes().get( a.getId() );
		}

		final Img< T > img = imgFactory.imgFactory( type ).create( dim, type );

		if ( img == null )
			throw new RuntimeException( "Could not instantiate " + getImgFactory().getClass().getSimpleName() + " for '" + cziFile + "' viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() + ", most likely out of memory." );

		final boolean isLittleEndian = meta.isLittleEndian();
		final boolean isArray = ArrayImg.class.isInstance( img );
		final int pixelType = meta.pixelType();
		final int width = dim[ 0 ];
		final int height = dim[ 1 ];
		final int depth = dim[ 2 ];
		final int numPx = width * height;
		final IFormatReader r;

		// if we already loaded the metadata in this run, use the opened file
		if ( meta.getReader() == null )
			r = LegacyLightSheet7ImgLoader.instantiateImageReader();
		else
			r = meta.getReader();

		final byte[] b = new byte[ numPx * meta.bytesPerPixel() ];

		try
		{
			// open the file if not already done
			try
			{
				if ( meta.getReader() == null )
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Opening '" + cziFile.getName() + "' for reading image data." );
					r.setId( cziFile.getAbsolutePath() );
				}

				// set the right angle
				r.setSeries( tile.getId() );
			}
			catch ( IllegalStateException e )
			{
				r.setId( cziFile.getAbsolutePath() );
				r.setSeries( tile.getId() );
			}

			IOFunctions.println(
					new Date( System.currentTimeMillis() ) + ": Reading image data from '" + cziFile.getName() + "' [" + dim[ 0 ] + "x" + dim[ 1 ] + "x" + dim[ 2 ] +
					" angle=" + a.getName() + " ch=" + c.getName() + " illum=" + i.getName() + " tp=" + t.getName() + " type=" + meta.pixelTypeString() +
					" img=" + img.getClass().getSimpleName() + "<" + type.getClass().getSimpleName() + ">]" );

			// TODO: fix the channel/illum assignments, I think the lower one is correct but need example dataset
			// compute the right channel from channelId & illuminationId
			//int ch = c.getId() * meta.numIlluminations() + i.getId(); // c0( i0, i1 ), c1( i0, i1 ), c2( i0, i1 )
			int ch = i.getId() * meta.numChannels() + c.getId(); // i0( c0, c1, c2 ), i1( c0, c1, c2 )

			for ( int z = 0; z < depth; ++z )
			{
				IJ.showProgress( (double)z / (double)depth );

				final Cursor< T > cursor = Views.iterable( Views.hyperSlice( img, 2, z ) ).localizingCursor();

				r.openBytes( r.getIndex( z, ch, t.getId() ), b );

				if ( pixelType == FormatTools.UINT8 )
				{
					if ( isArray )
						readBytesArray( b, cursor, numPx );
					else
						readBytes( b, cursor, width );
				}
				else if ( pixelType == FormatTools.UINT16 )
				{
					if ( isArray )
						readUnsignedShortsArray( b, cursor, numPx, isLittleEndian );
					else
						readUnsignedShorts( b, cursor, width, isLittleEndian );
				}
				else if ( pixelType == FormatTools.INT16 )
				{
					if ( isArray )
						readSignedShortsArray( b, cursor, numPx, isLittleEndian );
					else
						readSignedShorts( b, cursor, width, isLittleEndian );
				}
				else if ( pixelType == FormatTools.UINT32 )
				{
					//TODO: Untested
					if ( isArray )
						readUnsignedIntsArray( b, cursor, numPx, isLittleEndian );
					else
						readUnsignedInts( b, cursor, width, isLittleEndian );
				}
				else if ( pixelType == FormatTools.FLOAT )
				{
					if ( isArray )
						readFloatsArray( b, cursor, numPx, isLittleEndian );
					else
						readFloats( b, cursor, width, isLittleEndian );
				}
			}

			IJ.showProgress( 1 );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "File '" + cziFile.getAbsolutePath() + "' could not be opened: " + e );
			IOFunctions.println( "Stopping" );

			e.printStackTrace();
			try { r.close(); } catch (IOException e1) { e1.printStackTrace(); }
			return null;
		}

		return img;
	}

	public static final < T extends RealType< T > > void readBytes( final byte[] b, final Cursor< T > cursor, final int width )
	{
		while( cursor.hasNext() )
		{
			cursor.fwd(); // otherwise the position is off below
			cursor.get().setReal( b[ cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ] & 0xff );
		}
	}

	public static final < T extends RealType< T > > void readBytesArray( final byte[] b, final Cursor< T > cursor, final int numPx )
	{
		for ( int i = 0; i < numPx; ++i )
			cursor.next().setReal( b[ i ] & 0xff );
	}

	public static final < T extends RealType< T > > void readUnsignedShorts( final byte[] b, final Cursor< T > cursor, final int width, final boolean isLittleEndian )
	{
		while( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().setReal( LegacyStackImgLoaderLOCI.getShortValueInt( b, ( cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ) * 2, isLittleEndian ) );
		}
	}

	public static final < T extends RealType< T > > void readUnsignedShortsArray( final byte[] b, final Cursor< T > cursor, final int numPx, final boolean isLittleEndian )
	{
		for ( int i = 0; i < numPx; ++i )
			cursor.next().setReal( LegacyStackImgLoaderLOCI.getShortValueInt( b, i * 2, isLittleEndian ) );
	}

	public static final < T extends RealType< T > > void readSignedShorts( final byte[] b, final Cursor< T > cursor, final int width, final boolean isLittleEndian )
	{
		while( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().setReal( LegacyStackImgLoaderLOCI.getShortValue( b, ( cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ) * 2, isLittleEndian ) );
		}
	}

	public static final < T extends RealType< T > > void readSignedShortsArray( final byte[] b, final Cursor< T > cursor, final int numPx, final boolean isLittleEndian )
	{
		for ( int i = 0; i < numPx; ++i )
			cursor.next().setReal( LegacyStackImgLoaderLOCI.getShortValue( b, i * 2, isLittleEndian ) );
	}

	public static final < T extends RealType< T > > void readUnsignedInts( final byte[] b, final Cursor< T > cursor, final int width, final boolean isLittleEndian )
	{
		while( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().setReal( LegacyStackImgLoaderLOCI.getIntValue( b, ( cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ) * 4, isLittleEndian ) );
		}
	}

	public static final < T extends RealType< T > > void readUnsignedIntsArray( final byte[] b, final Cursor< T > cursor, final int numPx, final boolean isLittleEndian )
	{
		for ( int i = 0; i < numPx; ++i )
			cursor.next().setReal( LegacyStackImgLoaderLOCI.getIntValue( b, i * 4, isLittleEndian ) );
	}

	public static final < T extends RealType< T > > void readFloats( final byte[] b, final Cursor< T > cursor, final int width, final boolean isLittleEndian )
	{
		while( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().setReal( LegacyStackImgLoaderLOCI.getFloatValue( b, ( cursor.getIntPosition( 0 ) + cursor.getIntPosition( 1 ) * width ) * 4, isLittleEndian ) );
		}
	}

	public static final < T extends RealType< T > > void readFloatsArray( final byte[] b, final Cursor< T > cursor, final int numPx, final boolean isLittleEndian )
	{
		for ( int i = 0; i < numPx; ++i )
			cursor.next().setReal( LegacyStackImgLoaderLOCI.getFloatValue( b, i * 4, isLittleEndian ) );
	}

	public static IFormatReader instantiateImageReader()
	{
		// should I use the ZeissCZIReader here directly?
		ZeissCZIReader r = new ZeissCZIReader();
		MetadataOptions options = r.getMetadataOptions();
		if (options instanceof DynamicMetadataOptions) {
			((DynamicMetadataOptions) options).setBoolean(
					ZeissCZIReader.ALLOW_AUTOSTITCHING_KEY, false);
			((DynamicMetadataOptions) options).setBoolean(
					ZeissCZIReader.RELATIVE_POSITIONS_KEY, true);
		} else {
			System.out.println("What's wrong?");
		}
		r.setMetadataOptions(options);

		// reader.setMetadataStore(omeMeta);

		return new ChannelSeparator(r);
	}

	public static boolean createOMEXMLMetadata( final IFormatReader r )
	{
		// try
		// {
			// final ServiceFactory serviceFactory = new ServiceFactory();
			// final OMEXMLService service = serviceFactory.getInstance( OMEXMLService.class );
			// final IMetadata omexmlMeta = service.createOMEXMLMetadata();

			// r.setMetadataStore(omexmlMeta);

		final IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
		r.setMetadataStore(omeMeta);

		// }
		// catch (final ServiceException e)
		// {
		// 	e.printStackTrace();
		// 	return false;
		// }
		// catch (final DependencyException e)
		// {
		// 	e.printStackTrace();
		// 	return false;
		// }

		return true;
	}

	protected static Angle getAngle( final AbstractSequenceDescription< ?, ?, ? > seqDesc, final ViewId view )
	{
		return getAngle( seqDesc.getViewDescriptions().get( view ) );
	}

	protected static Angle getAngle( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Angle angle = vs.getAttribute( Angle.class );

		if ( angle == null )
			throw new RuntimeException( "This XML does not have the 'Angle' attribute for their ViewSetup. Cannot continue." );

		return angle;
	}

	protected static Channel getChannel( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Channel channel = vs.getAttribute( Channel.class );

		if ( channel == null )
			throw new RuntimeException( "This XML does not have the 'Channel' attribute for their ViewSetup. Cannot continue." );

		return channel;
	}

	protected static Illumination getIllumination( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Illumination illumination = vs.getAttribute( Illumination.class );

		if ( illumination == null )
			throw new RuntimeException( "This XML does not have the 'Illumination' attribute for their ViewSetup. Cannot continue." );

		return illumination;
	}

	protected static Tile getTile( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Tile tile = vs.getAttribute( Tile.class );

		if ( tile == null )
			throw new RuntimeException( "This XML does not have the 'Illumination' attribute for their ViewSetup. Cannot continue." );

		return tile;
	}

	@Override
	public String toString()
	{
		return new LightSheet7().getTitle() + ", ImgFactory=" + imgFactory.getClass().getSimpleName();
	}
}
