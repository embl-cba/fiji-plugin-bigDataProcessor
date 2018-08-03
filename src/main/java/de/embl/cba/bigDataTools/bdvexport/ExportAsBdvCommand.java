package de.embl.cba.bigDataTools.bdvexport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import ij.plugin.FolderOpener;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import bdv.export.ProposeMipmaps;
import bdv.export.SubTaskProgressWriter;
import bdv.export.WriteSequenceToHdf5;
import bdv.export.WriteSequenceToHdf5.AfterEachPlane;
import bdv.export.WriteSequenceToHdf5.LoopbackHeuristic;
import bdv.ij.export.imgloader.ImagePlusImgLoader;
import bdv.ij.export.imgloader.ImagePlusImgLoader.MinMaxOption;
import bdv.ij.util.PluginHelper;
import bdv.ij.util.ProgressWriterIJ;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.hdf5.Partition;
import bdv.spimdata.SequenceDescriptionMinimal;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;

import ij.IJ;
import ij.ImagePlus;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import org.scijava.widget.FileWidget;

/**
 * ImageJ plugin to export the current image to xml/hdf5.
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
@Plugin(type = Command.class,
		menuPath = "Plugins>BigDataTools>Create BigDataViewer XML/HDF5")
public class ExportAsBdvCommand implements Command
{


	public static final String VIRTUAL_STACK = "Virtual Stack";
	@Parameter ( choices = { VIRTUAL_STACK } )
	String importModality;

	@Parameter ( style = FileWidget.OPEN_STYLE )
	File inputPath;

	@Parameter ( style = FileWidget.SAVE_STYLE )
	File xmlOutputPath;


	@Override
	public void run()
	{
		if ( ij.Prefs.setIJMenuBar )
			System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		final ImagePlus imp;

		if ( importModality.equals( VIRTUAL_STACK ) )
		{
			final FolderOpener folderOpener = new FolderOpener();
			folderOpener.openAsVirtualStack( true );
			imp = folderOpener.openFolder( inputPath.getParent() );
		}
		else
		{
			imp = null;
			return;
		}

		// check the image type
		switch ( imp.getType() )
		{
			case ImagePlus.GRAY8:
			case ImagePlus.GRAY16:
			case ImagePlus.GRAY32:
				break;
			default:
				IJ.showMessage( "Only 8, 16, 32-bit images are supported currently!" );
				return;
		}

		// check the image dimensionality
		if ( imp.getNDimensions() < 3 )
		{
			IJ.showMessage( "Image must be at least 3-dimensional!" );
			return;
		}

		// get calibration and image size
		final double pw = imp.getCalibration().pixelWidth;
		final double ph = imp.getCalibration().pixelHeight;
		final double pd = imp.getCalibration().pixelDepth;
		String punit = imp.getCalibration().getUnit();
		if ( punit == null || punit.isEmpty() )
			punit = "px";
		final FinalVoxelDimensions voxelSize = new FinalVoxelDimensions( punit, pw, ph, pd );
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getNSlices();
		final FinalDimensions size = new FinalDimensions( new int[] { w, h, d } );

		// propose reasonable mipmap settings
		final ExportMipmapInfo autoMipmapSettings = ProposeMipmaps.proposeMipmaps( new BasicViewSetup( 0, "", size, voxelSize ) );

		// get output paths, resolutions, subdivisions, min-max option
		final Parameters params = getParametersAutomated( imp.getBitDepth(), autoMipmapSettings, xmlOutputPath.getAbsolutePath() );

		final String autoSubsampling = ProposeMipmaps.getArrayString( autoMipmapSettings.getExportResolutions() );
		final String autoChunkSizes = ProposeMipmaps.getArrayString( autoMipmapSettings.getSubdivisions() );

		final ProgressWriter progressWriter = new ProgressWriterIJ();

		progressWriter.out().println( "Subsampling: " + autoSubsampling );
		progressWriter.out().println( "Chunking: " + autoChunkSizes );
		progressWriter.out().println( "starting export..." );

		// create ImgLoader wrapping the image
		final ImagePlusImgLoader< ? > imgLoader;
		switch ( imp.getType() )
		{
			case ImagePlus.GRAY8:
				imgLoader = ImagePlusImgLoader.createGray8( imp, params.minMaxOption, params.rangeMin, params.rangeMax );
				break;
			case ImagePlus.GRAY16:
				imgLoader = ImagePlusImgLoader.createGray16( imp, params.minMaxOption, params.rangeMin, params.rangeMax );
				break;
			case ImagePlus.GRAY32:
			default:
				imgLoader = ImagePlusImgLoader.createGray32( imp, params.minMaxOption, params.rangeMin, params.rangeMax );
				break;
		}

		final int numTimepoints = imp.getNFrames();
		final int numSetups = imp.getNChannels();

		// create SourceTransform from the images calibration
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		sourceTransform.set( pw, 0, 0, 0, 0, ph, 0, 0, 0, 0, pd, 0 );

		// write hdf5
		final HashMap< Integer, BasicViewSetup > setups = new HashMap<>( numSetups );
		for ( int s = 0; s < numSetups; ++s )
		{
			final BasicViewSetup setup = new BasicViewSetup( s, String.format( "channel %d", s + 1 ), size, voxelSize );
			setup.setAttribute( new Channel( s + 1 ) );
			setups.put( s, setup );
		}
		final ArrayList< TimePoint > timepoints = new ArrayList<>( numTimepoints );
		for ( int t = 0; t < numTimepoints; ++t )
			timepoints.add( new TimePoint( t ) );
		final SequenceDescriptionMinimal seq = new SequenceDescriptionMinimal( new TimePoints( timepoints ), setups, imgLoader, null );

		Map< Integer, ExportMipmapInfo > perSetupExportMipmapInfo;
		perSetupExportMipmapInfo = new HashMap<>();
		final ExportMipmapInfo mipmapInfo = new ExportMipmapInfo( params.resolutions, params.subdivisions );
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
			perSetupExportMipmapInfo.put( setup.getId(), mipmapInfo );

		// LoopBackHeuristic:
		// - If saving more than 8x on pixel reads use the loopback image over
		//   original image
		// - For virtual stacks also consider the cache size that would be
		//   required for all original planes contributing to a "plane of
		//   blocks" at the current level. If this is more than 1/4 of
		//   available memory, use the loopback image.
		final boolean isVirtual = imp.getStack().isVirtual();
		final long planeSizeInBytes = imp.getWidth() * imp.getHeight() * imp.getBytesPerPixel();
		final long ijMaxMemory = IJ.maxMemory();
		final int numCellCreatorThreads = Math.max( 1, PluginHelper.numThreads() - 1 );
		final LoopbackHeuristic loopbackHeuristic = new LoopbackHeuristic()
		{
			@Override
			public boolean decide( final RandomAccessibleInterval< ? > originalImg, final int[] factorsToOriginalImg, final int previousLevel, final int[] factorsToPreviousLevel, final int[] chunkSize )
			{
				if ( previousLevel < 0 )
					return false;

				if ( WriteSequenceToHdf5.numElements( factorsToOriginalImg ) / WriteSequenceToHdf5.numElements( factorsToPreviousLevel ) >= 8 )
					return true;

				if ( isVirtual )
				{
					final long requiredCacheSize = planeSizeInBytes * factorsToOriginalImg[ 2 ] * chunkSize[ 2 ];
					if ( requiredCacheSize > ijMaxMemory / 4 )
						return true;
				}

				return false;
			}
		};

		final AfterEachPlane afterEachPlane = new AfterEachPlane()
		{
			@Override
			public void afterEachPlane( final boolean usedLoopBack )
			{
				if ( !usedLoopBack && isVirtual )
				{
					final long free = Runtime.getRuntime().freeMemory();
					final long total = Runtime.getRuntime().totalMemory();
					final long max = Runtime.getRuntime().maxMemory();
					final long actuallyFree = max - total + free;

					if ( actuallyFree < max / 2 )
						imgLoader.clearCache();
				}
			}

		};

		final ArrayList< Partition > partitions;
		if ( params.split )
		{
			final String xmlFilename = params.seqFile.getAbsolutePath();
			final String basename = xmlFilename.endsWith( ".xml" ) ? xmlFilename.substring( 0, xmlFilename.length() - 4 ) : xmlFilename;
			partitions = Partition.split( timepoints, seq.getViewSetupsOrdered(), params.timepointsPerPartition, params.setupsPerPartition, basename );

			for ( int i = 0; i < partitions.size(); ++i )
			{
				final Partition partition = partitions.get( i );
				final ProgressWriter p = new SubTaskProgressWriter( progressWriter, 0, 0.95 * i / partitions.size() );
				WriteSequenceToHdf5.writeHdf5PartitionFile( seq, perSetupExportMipmapInfo, params.deflate, partition, loopbackHeuristic, afterEachPlane, numCellCreatorThreads, p );
			}
			WriteSequenceToHdf5.writeHdf5PartitionLinkFile( seq, perSetupExportMipmapInfo, partitions, params.hdf5File );
		}
		else
		{
			partitions = null;
			WriteSequenceToHdf5.writeHdf5File( seq, perSetupExportMipmapInfo, params.deflate, params.hdf5File, loopbackHeuristic, afterEachPlane, numCellCreatorThreads, new SubTaskProgressWriter( progressWriter, 0, 0.95 ) );
		}

		// write xml sequence description
		final Hdf5ImageLoader hdf5Loader = new Hdf5ImageLoader( params.hdf5File, partitions, null, false );
		final SequenceDescriptionMinimal seqh5 = new SequenceDescriptionMinimal( seq, hdf5Loader );

		final ArrayList< ViewRegistration > registrations = new ArrayList<>();
		for ( int t = 0; t < numTimepoints; ++t )
			for ( int s = 0; s < numSetups; ++s )
				registrations.add( new ViewRegistration( t, s, sourceTransform ) );

		final File basePath = params.seqFile.getParentFile();
		final SpimDataMinimal spimData = new SpimDataMinimal( basePath, seqh5, new ViewRegistrations( registrations ) );

		try
		{
			new XmlIoSpimDataMinimal().save( spimData, params.seqFile.getAbsolutePath() );
			progressWriter.setProgress( 1.0 );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
		progressWriter.out().println( "done" );
	}

	protected static class Parameters
	{
		final boolean setMipmapManual;

		final int[][] resolutions;

		final int[][] subdivisions;

		final File seqFile;

		final File hdf5File;

		final MinMaxOption minMaxOption;

		final double rangeMin;

		final double rangeMax;

		final boolean deflate;

		final boolean split;

		final int timepointsPerPartition;

		final int setupsPerPartition;

		public Parameters(
				final boolean setMipmapManual, final int[][] resolutions, final int[][] subdivisions,
				final File seqFile, final File hdf5File,
				final MinMaxOption minMaxOption, final double rangeMin, final double rangeMax, final boolean deflate,
				final boolean split, final int timepointsPerPartition, final int setupsPerPartition )
		{
			this.setMipmapManual = setMipmapManual;
			this.resolutions = resolutions;
			this.subdivisions = subdivisions;
			this.seqFile = seqFile;
			this.hdf5File = hdf5File;
			this.minMaxOption = minMaxOption;
			this.rangeMin = rangeMin;
			this.rangeMax = rangeMax;
			this.deflate = deflate;
			this.split = split;
			this.timepointsPerPartition = timepointsPerPartition;
			this.setupsPerPartition = setupsPerPartition;
		}
	}


	protected Parameters getParametersAutomated( final int bitDepth,
												 final ExportMipmapInfo autoMipmapSettings,
												 String xmlExportPath )
	{

		String seqFilename = xmlExportPath;

		final String hdf5Filename = xmlExportPath.substring( 0, seqFilename.length() - 4 ) + ".h5";
		final File hdf5File = new File( hdf5Filename );

		if ( !seqFilename.endsWith( ".xml" ) ) seqFilename += ".xml";
		final File seqFile = new File( seqFilename );
		final File parent = seqFile.getParentFile();
		if ( parent == null || ! parent.exists() || ! parent.isDirectory() )
		{
			IJ.showMessage( "Invalid export filename " + seqFilename );
			return null;
		}

		final Parameters parameters = new Parameters( false,
				autoMipmapSettings.getExportResolutions(),
				autoMipmapSettings.getSubdivisions(),
				seqFile,
				hdf5File,
				MinMaxOption.SET,
				0,
				Math.pow( 2, bitDepth ) - 1.0 ,
				true,
				false,
				0,
				0 );

		return parameters;
	}


	public static void main( final String[] args )
	{
		final net.imagej.ImageJ ij = new net.imagej.ImageJ();
		ij.ui().showUI();

		ij.command().run( ExportAsBdvCommand.class, true );
	}
}
