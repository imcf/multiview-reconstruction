package net.preibisch.mvrecon.process.splitting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.iterator.LocalizingZeroMinIntervalIterator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

public class SplittingTools
{
	public static SpimData2 splitImages( final SpimData2 spimData, final long[] overlapPx, final long[] targetSize )
	{
		final TimePoints timepoints = spimData.getSequenceDescription().getTimePoints();

		final List< ViewSetup > oldSetups = new ArrayList<>();
		oldSetups.addAll( spimData.getSequenceDescription().getViewSetups().values() );
		Collections.sort( oldSetups );

		final ViewRegistrations oldRegistrations = spimData.getViewRegistrations();

		final ImgLoader underlyingImgLoader = spimData.getSequenceDescription().getImgLoader();
		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		final ArrayList< ViewSetup > newSetups = new ArrayList<>();
		final Map< ViewId, ViewRegistration > newRegistrations = new HashMap<>();
		int newId = 0;
		int newTileId = 0;

		for ( final ViewSetup oldSetup : oldSetups )
		{
			final int oldID = oldSetup.getId();
			final Tile oldTile = oldSetup.getTile();

			final Angle angle = oldSetup.getAngle();
			final Channel channel = oldSetup.getChannel();
			final Illumination illum = oldSetup.getIllumination();
			final VoxelDimensions voxDim = oldSetup.getVoxelSize();

			final Interval input = new FinalInterval( oldSetup.getSize() );
			final ArrayList< Interval > intervals = distributeIntervalsFixedOverlap( input, overlapPx, targetSize );

			for ( int i = 0; i < intervals.size(); ++i )
			{
				final Interval interval = intervals.get( i );

				// from the new ID get the old ID and the corresponding interval
				new2oldSetupId.put( newId, oldID );
				newSetupId2Interval.put( newId, interval );

				final long[] size = new long[ interval.numDimensions() ];
				interval.dimensions( size );
				final Dimensions newDim = new FinalDimensions( size );

				final double[] location = oldTile.getLocation() == null ? new double[ interval.numDimensions() ] : oldTile.getLocation().clone();
				for ( int d = 0; d < interval.numDimensions(); ++d )
					location[ d ] += interval.min( d );

				final Tile newTile = new Tile( newTileId, Integer.toString( newTileId ), location );
				final ViewSetup newSetup = new ViewSetup( newId, null, newDim, voxDim, newTile, channel, angle, illum );
				newSetups.add( newSetup );

				// update registrations for all timepoints
				for ( final TimePoint t : timepoints.getTimePointsOrdered() )
				{
					final ViewRegistration oldVR = oldRegistrations.getViewRegistration( new ViewId( t.getId(), oldSetup.getId() ) );
					final ArrayList< ViewTransform > transformList = new ArrayList<>( oldVR.getTransformList() );

					final AffineTransform3D translation = new AffineTransform3D();
					translation.set( 1.0f, 0.0f, 0.0f, interval.min( 0 ),
							0.0f, 1.0f, 0.0f, interval.min( 1 ),
							0.0f, 0.0f, 1.0f, interval.min( 2 ) );

					final ViewTransformAffine transform = new ViewTransformAffine( "Image Splitting", translation );
					transformList.add( transform );

					final ViewId newViewId = new ViewId( t.getId(), newSetup.getId() );
					final ViewRegistration newVR = new ViewRegistration( newViewId.getTimePointId(), newViewId.getViewSetupId(), transformList );
					newRegistrations.put( newViewId, newVR );
				}

				newId++;
				newTileId++;
			}
		}

		final MissingViews oldMissingViews = spimData.getSequenceDescription().getMissingViews();
		final HashSet< ViewId > missingViews = new HashSet< ViewId >();

		if ( oldMissingViews != null && oldMissingViews.getMissingViews() != null )
			for ( final ViewId id : oldMissingViews.getMissingViews() )
				for ( final int newSetupId : new2oldSetupId.keySet() )
					if ( new2oldSetupId.get( newSetupId ) == id.getViewSetupId() )
						missingViews.add( new ViewId( id.getTimePointId(), newSetupId ) );

		// instantiate the sequencedescription
		final SequenceDescription sequenceDescription = new SequenceDescription( timepoints, newSetups, null, new MissingViews( missingViews ) );
		final ImgLoader imgLoader = new SplitImgLoader( underlyingImgLoader, new2oldSetupId, newSetupId2Interval );
		sequenceDescription.setImgLoader( imgLoader );

		// TODO: create the initial view interest point object
		final Map< ViewId, ViewInterestPointLists > vipl = new HashMap<>();

		for ( final ViewId viewId : sequenceDescription.getViewDescriptions().values() )
			vipl.put( viewId, new ViewInterestPointLists( viewId.getTimePointId(), viewId.getViewSetupId() ) );

		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints( vipl );

		// TODO: fix point spread functions
		// TODO: fix intensity adjustments?

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimDataNew = new SpimData2( spimData.getBasePath(), sequenceDescription, new ViewRegistrations( newRegistrations ), viewInterestPoints, spimData.getBoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );

		return spimDataNew;
	}

	public static ArrayList< Interval > distributeIntervalsFixedOverlap( final Interval input, final long[] overlapPx, final long[] targetSize )
	{
		final ArrayList< ArrayList< Pair< Long, Long > > > intervalBasis = new ArrayList<>();

		for ( int d = 0; d < input.numDimensions(); ++d )
		{
			final ArrayList< Pair< Long, Long > > dimIntervals = new ArrayList<>();
	
			final long length = input.dimension( d );

			// can I use just 1 block?
			if ( length <= targetSize[ d ] )
			{
				final long min = input.min( d );
				final long max = input.max( d );

				dimIntervals.add( new ValuePair< Long, Long >( min, max ) );
				System.out.println( "one block from " + min + " to " + max );
			}
			else
			{
				final double l = length;
				final double s = targetSize[ d ];
				final double o = overlapPx[ d ];
	
				final double numCenterBlocks = ( l - 2.0 * ( s-o ) - o ) / ( s - 2.0 * o + o );
				final long numCenterBlocksInt;

				if ( numCenterBlocks <= 0.0 )
					numCenterBlocksInt = 0;
				else
					numCenterBlocksInt = Math.round( numCenterBlocks );

				final double n = numCenterBlocksInt;

				final double newSize = ( l + o + n * o ) / ( 2.0 + n );
				final long newSizeInt = Math.round( newSize );

				System.out.println( "numCenterBlocks: " + numCenterBlocks );
				System.out.println( "numCenterBlocksInt: " + numCenterBlocksInt );
				System.out.println( "numBlocks: " + (numCenterBlocksInt + 2) );
				System.out.println( "newSize: " + newSize );
				System.out.println( "newSizeInt: " + newSizeInt );

				System.out.println();
				//System.out.println( "block 0: " + input.min( d ) + " " + (input.min( d ) + Math.round( newSize ) - 1) );

				for ( int i = 0; i <= numCenterBlocksInt; ++i )
				{
					final long from = Math.round( input.min( d ) + i * newSize - i * o );
					final long to = from + newSizeInt - 1;

					System.out.println( "block " + (numCenterBlocksInt) + ": " + from + " " + to );
					dimIntervals.add( new ValuePair< Long, Long >( from, to ) );
				}

				final long from = ( input.max( d ) - Math.round( newSize ) + 1 );
				final long to = input.max( d );
	
				System.out.println( "block " + (numCenterBlocksInt + 1) + ": " + from + " " + to );
				dimIntervals.add( new ValuePair< Long, Long >( from, to ) );
			}

			intervalBasis.add( dimIntervals );
		}

		final long[] numIntervals = new long[ input.numDimensions() ];

		for ( int d = 0; d < input.numDimensions(); ++d )
			numIntervals[ d ] = intervalBasis.get( d ).size();

		final LocalizingZeroMinIntervalIterator cursor = new LocalizingZeroMinIntervalIterator( numIntervals );
		final ArrayList< Interval > intervalList = new ArrayList<>();

		final int[] currentInterval = new int[ input.numDimensions() ];

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.localize( currentInterval );

			final long[] min = new long[ input.numDimensions() ];
			final long[] max = new long[ input.numDimensions() ];

			for ( int d = 0; d < input.numDimensions(); ++d )
			{
				final Pair< Long, Long > minMax = intervalBasis.get( d ).get( currentInterval[ d ] );
				min[ d ] = minMax.getA();
				max[ d ] = minMax.getB();
			}

			intervalList.add( new FinalInterval( min, max ) );
		}

		return intervalList;
	}

	public static void main( String[] args )
	{
		Interval input = new FinalInterval( new long[]{ 0 }, new long[] { 1915 - 1 } );
		long[] overlapPx = new long[] { 10 };
		long[] targetSize = new long[] { 500 };

		ArrayList< Interval > intervals = distributeIntervalsFixedOverlap( input, overlapPx, targetSize );

		System.out.println();

		for ( final Interval interval : intervals )
			System.out.println( Util.printInterval( interval ) );
	}
}
