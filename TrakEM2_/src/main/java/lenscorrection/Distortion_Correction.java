/**

ImageJ plugin
Copyright (C) 2008 Verena Kaynig.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 **/


/* **************************************************************************** *
 * This ImageJ Plugin estimates a non linear lens distortion in                 *
 * the images given and corrects all images with the same transformation        *
 *                                                                              *
 *                                                                              *
 * TODO:                                                                        *
 * 	- show histogram of beta coefficients to evaluate kernel dimension needed   *
 *  - show SIFT matches before and after correction?                            *
 *  - give numerical xcorr results in a better manner                           *
 *  - show visual impression of the stitching ?                                 *
 *  - DOKUMENTATION                                                             *
 *  - do mask images properly to incorporate into TrakEM                        *
 *                                                                              *
 *  Author: Verena Kaynig                                                       *
 *  Kontakt: verena.kaynig@inf.ethz.ch                                          *
 *                                                                              *
 *  SIFT implementation: Stephan Saalfeld                                       *
 * **************************************************************************** */

package lenscorrection;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import mpi.fruitfly.general.MultiThreading;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractAffineModel2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;

import Jama.Matrix;


public class Distortion_Correction implements PlugIn{

	static public class PointMatchCollectionAndAffine
	{
		final public AffineTransform affine;
		final public Collection< PointMatch > pointMatches;
		public PointMatchCollectionAndAffine( final AffineTransform affine, final Collection< PointMatch > pointMatches )
		{
			this.affine = affine;
			this.pointMatches = pointMatches;
		}
	}

	static public class BasicParam
	{
		final public FloatArray2DSIFT.Param sift = new FloatArray2DSIFT.Param();

		/**
		 * Closest/next closest neighbour distance ratio
		 */
		public float rod = 0.92f;

		/**
		 * Maximal allowed alignment error in px
		 */
		public float maxEpsilon = 32.0f;

		/**
		 * Inlier/candidates ratio
		 */
		public float minInlierRatio = 0.2f;

		/**
		 * Implemeted transformation models for choice
		 */
		final static public String[] modelStrings = new String[]{ "Translation", "Rigid", "Similarity", "Affine" };
		public int expectedModelIndex = 1;

		/**
		 * Order of the polynomial kernel
		 */
		public int dimension = 5;

		/**
		 * Regularization factor
		 */
		public double lambda = 0.01;

		public BasicParam()
		{
			sift.fdSize = 8;
			sift.maxOctaveSize = 1600;
			sift.minOctaveSize = 400;
		}

		public void addFields( final GenericDialog gd )
		{
			SIFT.addFields( gd, sift );

			gd.addNumericField( "closest/next_closest_ratio :", rod, 2 );

			gd.addMessage( "Geometric Consensus Filter :" );
			gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
			gd.addNumericField( "inlier_ratio :", minInlierRatio, 2 );
			gd.addChoice( "expected_transformation :", modelStrings, modelStrings[ expectedModelIndex ] );

			gd.addMessage( "Lens Model :" );
			gd.addNumericField( "power_of_polynomial_kernel :", dimension, 0 );
			gd.addNumericField( "lambda :", lambda, 6 );
		}

		public boolean readFields( final GenericDialog gd )
		{
			SIFT.readFields( gd, sift );

			rod = ( float )gd.getNextNumber();

			maxEpsilon = ( float )gd.getNextNumber();
			minInlierRatio = ( float )gd.getNextNumber();
			expectedModelIndex = gd.getNextChoiceIndex();

			dimension = ( int )gd.getNextNumber();
			lambda = ( double )gd.getNextNumber();

			return !gd.invalidNumber();
		}

		public boolean setup( final String title )
		{
			final GenericDialog gd = new GenericDialog( title );
			addFields( gd );
			do
			{
				gd.showDialog();
				if ( gd.wasCanceled() ) return false;
			}
			while ( !readFields( gd ) );

			return true;
		}

		@Override
		public BasicParam clone()
		{
			final BasicParam p = new BasicParam();
			p.sift.set( sift );
			p.dimension = dimension;
			p.expectedModelIndex = expectedModelIndex;
			p.lambda = lambda;
			p.maxEpsilon = maxEpsilon;
			p.minInlierRatio = minInlierRatio;
			p.rod = rod;

			return p;
		}
	}

	static public class PluginParam extends BasicParam
	{
		public String source_dir = "";
		public String target_dir = "";
		public String saveFileName = "distCorr.txt";

		public boolean applyCorrection = true;
		public boolean visualizeResults = true;
		public boolean outputGreyscale = false;
		public boolean outputRGB = true;

		public int numberOfImages = 9;
		public int firstImageIndex = 0;

		/**
		 * Original and calibrated images are supposed to have the same names,
		 * but are in different directories
		 */
		public String[] names;

		public int saveOrLoad = 0;

		@Override
		public void addFields( final GenericDialog gd )
		{
			super.addFields( gd );
		}

		@Override
		public boolean readFields( final GenericDialog gd )
		{
			super.readFields( gd );

			return !gd.invalidNumber();
		}

		/**
		 * Setup as a three step dialog.
		 */
		@Override
		public boolean setup( final String title )
		{
			source_dir = "";
			while ( source_dir == "" )
			{
				final DirectoryChooser dc = new DirectoryChooser( "Calibration Images" );
				source_dir = dc.getDirectory();
				if ( null == source_dir ) return false;

				source_dir = source_dir.replace( '\\', '/' );
				if ( !source_dir.endsWith( "/" ) ) source_dir += "/";
			}

			final String exts = ".tif.jpg.png.gif.tiff.jpeg.bmp.pgm";
			names = new File( source_dir ).list(
					new FilenameFilter()
					{
						@Override
                        public boolean accept( final File dir, final String name )
						{
							final int idot = name.lastIndexOf( '.' );
							if ( -1 == idot ) return false;
							return exts.contains( name.substring( idot ).toLowerCase() );
						}
					} );
			Arrays.sort( names );

			final GenericDialog gd = new GenericDialog( title );

			gd.addNumericField( "number_of_images :", 9, 0 );
			gd.addChoice( "first_image :", names, names[ 0 ] );
			gd.addNumericField( "power_of_polynomial_kernel :", dimension, 0 );
			gd.addNumericField( "lambda :", lambda, 6 );
			gd.addCheckbox( "apply_correction_to_images", applyCorrection );
			gd.addCheckbox( "visualize results", visualizeResults );
			gd.addCheckbox( "greyscale output", outputGreyscale );
			gd.addCheckbox( "RGB output", outputRGB );
			final String[] options = new String[]{ "save", "load" };
			gd.addChoice( "What to do? ", options, options[ saveOrLoad ] );
			gd.addStringField( "file_name: ", saveFileName );
			gd.showDialog();

			if (gd.wasCanceled()) return false;

			numberOfImages = ( int )gd.getNextNumber();
			firstImageIndex = gd.getNextChoiceIndex();
			dimension = ( int )gd.getNextNumber();
			lambda = gd.getNextNumber();
			applyCorrection = gd.getNextBoolean();
			visualizeResults = gd.getNextBoolean();
			outputGreyscale = gd.getNextBoolean();
			outputRGB = gd.getNextBoolean();
			saveOrLoad = gd.getNextChoiceIndex();
			saveFileName = gd.getNextString();

			if ( saveOrLoad == 0 || visualizeResults )
			{
				final GenericDialog gds = new GenericDialog( title );
				SIFT.addFields( gds, sift );

				gds.addNumericField( "closest/next_closest_ratio :", rod, 2 );

				gds.addMessage( "Geometric Consensus Filter:" );
				gds.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
				gds.addNumericField( "inlier_ratio :", minInlierRatio, 2 );
				gds.addChoice( "expected_transformation :", modelStrings, modelStrings[ expectedModelIndex ] );

				gds.showDialog();
				if ( gds.wasCanceled() ) return false;

				SIFT.readFields( gds, sift );

				rod = ( float )gds.getNextNumber();

				maxEpsilon = ( float )gds.getNextNumber();
				minInlierRatio = ( float )gds.getNextNumber();
				expectedModelIndex = gds.getNextChoiceIndex();

				return !( gd.invalidNumber() || gds.invalidNumber() );
			}

			return !gd.invalidNumber();
		}
	}

	static final public BasicParam p = new BasicParam();
	static final public PluginParam sp = new PluginParam();

	NonLinearTransform nlt = new NonLinearTransform();
	AbstractAffineModel2D< ? >[] models;

	@Override
    public void run(final String arg)
	{
		if ( !sp.setup( "Lens Correction" ) ) return;

		List< List< PointMatch > > inliers = null;
		if (sp.saveOrLoad == 0) {

			//IJ.log( sp.source_dir + sp.names[ 0 ] );
			final ImagePlus imgTmp = new Opener().openImage( sp.source_dir + sp.names[ 0 ] );
			final int imageWidth = imgTmp.getWidth(), imageHeight=imgTmp.getHeight();
			/** imgTmp was just needed to get width and height of the images */
			imgTmp.flush();

			final List< Feature >[] siftFeatures = extractSIFTFeaturesThreaded( sp.numberOfImages, sp.source_dir, sp.names );

			final List< PointMatch >[] inliersTmp = new ArrayList[ sp.numberOfImages * ( sp.numberOfImages - 1 ) ];
			models = new AbstractAffineModel2D[ sp.numberOfImages * ( sp.numberOfImages - 1 ) ];

			IJ.showStatus( "Estimating Correspondences" );
			for( int i = 0; i < sp.numberOfImages; ++i )
			{
				IJ.log( "Estimating correspondences of image " + i );
				IJ.showProgress( ( i + 1 ), sp.numberOfImages );
				extractSIFTPointsThreaded( i, siftFeatures, inliersTmp, models );
			}

			int wholeCount = 0;
			inliers = new ArrayList< List< PointMatch > >();
			for ( int i = 0; i < inliersTmp.length; i++ )
			{
				// if vector at inliersTmp[i] contains only one null element,
				// its size is still 1
				if (inliersTmp[i].size() > 10){
					wholeCount += inliersTmp[i].size();
				}
				//if I do not do this then the models have not the
				//right index corresponding to the inliers positions in the vector
				inliers.add(inliersTmp[i]);
			}

			//this is really really ugly
			final double[][] tp = new double[wholeCount][6];
			final double h1[][] = new double[wholeCount][2];
			final double h2[][] = new double[wholeCount][2];
			int count = 0;
			for ( int i = 0; i < inliers.size(); ++i )
			{
				if ( inliers.get(i).size() > 10 )
				{
					final double[][] points1 = new double[inliers.get(i).size()][2];
					final double[][] points2 = new double[inliers.get(i).size()][2];

					for (int j=0; j < inliers.get(i).size(); j++){

						final double[] tmp1 = ((PointMatch) inliers.get(i).get(j)).getP1().getL();
						final double[] tmp2 = ((PointMatch) inliers.get(i).get(j)).getP2().getL();

						points1[j][0] = (double) tmp1[0];
						points1[j][1] = (double) tmp1[1];
						points2[j][0] = (double) tmp2[0];
						points2[j][1] = (double) tmp2[1];

						h1[count] = new double[] {(double) tmp1[0], (double) tmp1[1]};
						h2[count] = new double[] {(double) tmp2[0], (double) tmp2[1]};

						models[i].createAffine().getMatrix(tp[count]);
						count++;
					}
				}
			}

			//if ( sp.saveOrLoad == 0 )
				//{
			nlt = distortionCorrection( h1, h2, tp, sp.dimension, sp.lambda, imageWidth, imageHeight );
			nlt.visualizeSmall( sp.lambda );


			while( true )
			{
				final GenericDialog gdl = new GenericDialog( "New lambda?" );
				gdl.addMessage( "If the distortion field shows a clear translation, \n it is likely that you need to increase lambda." );
				gdl.addNumericField( "lambda :", sp.lambda, 6 );
				gdl.showDialog();
				if ( gdl.wasCanceled() ) break;
				sp.lambda = gdl.getNextNumber();
				nlt = distortionCorrection( h1, h2, tp, sp.dimension, sp.lambda, imageWidth, imageHeight );
				nlt.visualizeSmall( sp.lambda );
			}
			nlt.save( sp.source_dir + sp.saveFileName );
		}
		//after all preprocessing is done, estimate the distortion correction transform


		if ( sp.saveOrLoad == 1 )
		{
			nlt.load( sp.source_dir + sp.saveFileName );
			nlt.print();
		}

		if ( sp.applyCorrection || sp.visualizeResults )
		{
			sp.target_dir = correctImages();
		}

		if ( sp.visualizeResults && (sp.saveOrLoad == 0))
		{
			IJ.log( "call nlt.visualize()" );
			nlt.visualize();
			if(null != sp.target_dir)
			{
				IJ.log( "Evaluating distortion correction..." );
				evaluateCorrection( inliers );
			}
		}
		IJ.log( " Done " );
	}

	public void evaluateCorrection( final List< List< PointMatch > > inliers)
	{
		IJ.showStatus("Evaluating Distortion Correction");
		final double[][] original = new double[ sp.numberOfImages][ 2 ];
		final double[][] corrected = new double[ sp.numberOfImages ][ 2 ];


		for ( int i = sp.firstImageIndex; i < sp.numberOfImages; i++ )
		{
			original[ i ] = evaluateCorrectionXcorr( i, sp.source_dir );
			corrected[ i ] = evaluateCorrectionXcorr( i, sp.target_dir );
		}
		final DefaultCategoryDataset dataset = new DefaultCategoryDataset();
		final DefaultCategoryDataset datasetGain = new DefaultCategoryDataset();
		final DefaultCategoryDataset datasetGrad = new DefaultCategoryDataset();

		for ( int i = 0; i < ( original.length ); ++i )
		{
			dataset.setValue(Math.abs(original[i][0]), "before", "image" + i);
			dataset.setValue(Math.abs(corrected[i][0]), "after", "image" + i);
			datasetGrad.setValue(Math.abs(original[i][1]), "before", "image" + i);
			datasetGrad.setValue(Math.abs(corrected[i][1]), "after", "image" + i);

			datasetGain.setValue(Math.abs(corrected[i][0]) - Math.abs(original[i][0]), "gray","image" + i);
			datasetGain.setValue(Math.abs(corrected[i][1]) - Math.abs(original[i][1]), "grad","image" + i);
		}

		final JFreeChart chart = ChartFactory.createBarChart(
				"Xcorr before and after correction",
				"ImageNumber",
				"Xcorr",
				dataset,
				PlotOrientation.VERTICAL,
				false,
				true, false);
		final ImagePlus imp = new ImagePlus( "Xcorr before and after correction Plot", chart.createBufferedImage( 500, 300 ) );
		imp.show();

		final JFreeChart chartGrad = ChartFactory.createBarChart(
				"XcorrGradient before and after correction",
				"ImageNumber",
				"Xcorr",
				datasetGrad,
				PlotOrientation.VERTICAL,
				false,
				true, false);
		final ImagePlus impGrad = new ImagePlus( "XcorrGradient before and after correction Plot", chartGrad.createBufferedImage( 500, 300 ) );
		impGrad.show();

		final JFreeChart chartGain = ChartFactory.createBarChart(
				"Gain in Xcorr",
				"ImageNumber",
				"Xcorr",
				datasetGain,
				PlotOrientation.VERTICAL,
				false,
				true, false);
		final ImagePlus impGain = new ImagePlus( "Gain in Xcorr Plot", chartGain.createBufferedImage( 500, 300 ) );
		impGain.show();

		visualizePoints( inliers );

		//write xcorr data to file
		String original0 = "", original1 = "", corrected0 = "", corrected1 = "", gain0 = "", gain1 = "";
		for ( int i = 0; i < ( original.length ); ++i )
		{
			original0 =  original0 + Double.toString(original[i][0]) + "; ";
			original1 =  original1 + Double.toString(original[i][1]) + "; ";
			corrected0 =  corrected0 + Double.toString(corrected[i][0]) + "; ";
			corrected1 =  corrected1 + Double.toString(corrected[i][1]) + "; ";
			gain0 = gain0 + Double.toString(Math.abs(corrected[i][0]) - Math.abs(original[i][0])) + "; ";
			gain1 = gain1 + Double.toString(Math.abs(corrected[i][1]) - Math.abs(original[i][1])) + "; ";
		}

		try
		{
			final BufferedWriter out = new BufferedWriter(new FileWriter( sp.source_dir + "xcorrData.log" ) );

			out.write( original0);
			out.newLine();
			out.newLine();
			out.write(original1);
			out.newLine();
			out.newLine();
			out.write(corrected0);
			out.newLine();
			out.newLine();
			out.write(corrected1);
			out.newLine();
			out.newLine();
			out.write(gain0);
			out.newLine();
			out.newLine();
			out.write(gain1);
			out.newLine();
			out.close();
		}
		catch (final Exception e){System.err.println("Error: " + e.getMessage());};
	}


	protected void extractSIFTPoints(
			final int index,
			final List< Feature >[] siftFeatures,
			final List< List< PointMatch > > inliers,
			final List< AbstractAffineModel2D< ? > > models )
	{

		//save all matching candidates
		final List< List< PointMatch > > candidates = new ArrayList< List< PointMatch > >();

		for ( int j = 0; j < siftFeatures.length; j++ )
		{
			if ( index == j ) continue;
			candidates.add( FloatArray2DSIFT.createMatches( siftFeatures[ index ], siftFeatures[ j ], 1.5f, null, Float.MAX_VALUE, 0.5f ) );
		}

		//get rid of the outliers and save the transformations to match the inliers
		for ( int i = 0; i < candidates.size(); ++i )
		{
			final List< PointMatch > tmpInliers = new ArrayList< PointMatch >();

			final AbstractAffineModel2D< ? > m;
			switch ( sp.expectedModelIndex )
			{
			case 0:
				m = new TranslationModel2D();
				break;
			case 1:
				m = new RigidModel2D();
				break;
			case 2:
				m = new SimilarityModel2D();
				break;
			case 3:
				m = new AffineModel2D();
				break;
			default:
				return;
			}

			try{
				m.filterRansac(
						candidates.get( i ),
						tmpInliers,
						1000,
						sp.maxEpsilon,
						sp.minInlierRatio,
						10 );
			}
			catch( final NotEnoughDataPointsException e ) { e.printStackTrace(); }

			inliers.add( tmpInliers );
			models.add( m );
		}
	}

	/**
	 * Estimates a polynomial distortion model for a set of
	 * {@linkplain PointMatch corresponding points} and returns the inverse of
	 * this model which then may be used to undo the distortion.
	 *
	 * @param matches
	 * @param affines
	 *   a list of affines in the same order as matches that transfer a match
	 *   collection into a common image frame
	 * @param dimension the order of the polynomial model
	 * @param lambda regularization factor
	 * @param imageWidth
	 * @param imageHeight
	 *
	 * @return a polynomial distortion model to undo the distortion
	 */
	static public NonLinearTransform createInverseDistortionModel(
			final Collection< PointMatchCollectionAndAffine > pointMatches,
			final int dimension,
			final double lambda,
			final int imageWidth,
			final int imageHeight )
	{
		int wholeCount = 0;
		for ( final PointMatchCollectionAndAffine pma : pointMatches )
			if ( pma.pointMatches.size() > 10 )
				wholeCount += pma.pointMatches.size();

		final double[][] tp = new double[ wholeCount ][ 6 ];
		final double h1[][] = new double[ wholeCount ][ 2 ];
		final double h2[][] = new double[ wholeCount ][ 2 ];
		int count = 0;
		for ( final PointMatchCollectionAndAffine pma : pointMatches )
		{
			if ( pma.pointMatches.size() > 10 )
			{
				int i = 0;
				for ( final PointMatch match : pma.pointMatches )
				{
					final double[] tmp1 = match.getP1().getL();
					final double[] tmp2 = match.getP2().getL();

					h1[ count ] = new double[]{ ( double ) tmp1[ 0 ], ( double ) tmp1[ 1 ] };
					h2[ count ] = new double[]{ ( double ) tmp2[ 0 ], ( double ) tmp2[ 1 ] };

					pma.affine.getMatrix( tp[ count ] );
					count++;

					++i;
				}
			}
		}

		final NonLinearTransform nlt = distortionCorrection(h1, h2, tp, dimension, lambda, imageWidth, imageHeight);
		nlt.inverseTransform( h1 );

		return nlt;
	}

	static protected NonLinearTransform distortionCorrection( final double hack1[][], final double hack2[][], final double transformParams[][], final int dimension, final double lambda, final int w, final int h )
	{
		IJ.showStatus( "Getting the Distortion Field" );
		final NonLinearTransform nlt = new NonLinearTransform( dimension, w, h );
		nlt.estimateDistortion( hack1, hack2, transformParams, lambda, w, h );

		nlt.inverseTransform( hack1 );
		return nlt;
	}

	protected String correctImages()
	{
		if ( !sp.applyCorrection )
		{
			sp.target_dir = System.getProperty( "user.dir" ).replace( '\\', '/' ) + "/distCorr_tmp/";
			System.out.println( "Tmp target directory: " + sp.target_dir );

			if ( new File( sp.target_dir ).exists() )
			{
				System.out.println( "removing old tmp directory!" );

				final String[] filesToDelete = new File( sp.target_dir ).list();
				for ( int i = 0; i < filesToDelete.length; i++ )
				{
					System.out.println( filesToDelete[ i ] );
					final boolean deleted = new File( sp.target_dir + filesToDelete[ i ] ).delete();
					if ( !deleted ) IJ.log( "Error: Could not remove temporary directory!" );
				}
				new File( sp.target_dir ).delete();
			}
			try
			{
				// Create one directory
				final boolean success = ( new File( sp.target_dir ) ).mkdir();
				if ( success ) new File( sp.target_dir ).deleteOnExit();
			}
			catch ( final Exception e )
			{
				IJ.showMessage( "Error! Could not create temporary directory. " + e.getMessage() );
			}
		}
		// if ( sp.target_dir == "" || null == sp.target_dir )
		// {
			final DirectoryChooser dc = new DirectoryChooser( "Target Directory" );
			sp.target_dir = dc.getDirectory();
			if ( null == sp.target_dir ) return null;
			sp.target_dir = sp.target_dir.replace( '\\', '/' );
			if ( !sp.target_dir.endsWith( "/" ) ) sp.target_dir += "/";
		// }

		final String[] namesTarget = new File( sp.target_dir ).list( new FilenameFilter()
		{
			@Override
            public boolean accept( final File dir, final String namesTarget )
			{
				final int idot = namesTarget.lastIndexOf( '.' );
				if ( -1 == idot ) return false;
				return namesTarget.contains( namesTarget.substring( idot ).toLowerCase() );
			}
		} );

		if ( namesTarget.length > 0 ) IJ.showMessage( "Overwrite Message", "There  are already images in that directory. These will be used for evaluation." );
		else
		{

			IJ.showStatus( "Correcting Images" );

			final Thread[] threads = MultiThreading.newThreads();
			final AtomicInteger ai = new AtomicInteger( sp.applyCorrection ? 0 : sp.firstImageIndex );

			for ( int ithread = 0; ithread < threads.length; ++ithread )
			{
				threads[ ithread ] = new Thread()
				{
					@Override
                    public void run()
					{
						setPriority( Thread.NORM_PRIORITY );

						for ( int i = ai.getAndIncrement(); i < ( sp.applyCorrection ? sp.names.length : ( sp.firstImageIndex + sp.numberOfImages ) ); i = ai.getAndIncrement() )
						{
							IJ.log( "(updated) Correcting image " + sp.names[ i ] );
							String extension = "";
							int index = sp.names[ i ].lastIndexOf('.');
							if (index > 0) {
							    extension = sp.names[ i ].substring(index);
							}
							final ImagePlus imps = new Opener().openImage( sp.source_dir + sp.names[ i ] );
							if(sp.outputRGB){
								imps.setProcessor( imps.getTitle(), imps.getProcessor().convertToRGB( ) );
								final ImageProcessor[] transErg = nlt.transform( imps.getProcessor() );
								imps.setProcessor( imps.getTitle(), transErg[ 0 ] );
								new FileSaver( imps ).saveAsTiff( sp.target_dir + sp.names[ i ].replace(extension, ".TIFF") );
								new FileSaver( imps ).saveAsJpeg( sp.target_dir + sp.names[ i ].replace(extension, ".jpg") );
							}

							if(sp.outputGreyscale){
								imps.setProcessor( imps.getTitle(), imps.getProcessor().convertToShort( false ) );
								final ImageProcessor[] transErg = nlt.transform( imps.getProcessor() );
								imps.setProcessor( imps.getTitle(), transErg[ 0 ] );
								if ( !sp.applyCorrection ) new File( sp.target_dir + sp.names[ i ] ).deleteOnExit();
								new FileSaver( imps ).saveAsTiff( sp.target_dir + sp.names[ i ].replace(extension, "_16bit.TIFF") );
								new FileSaver( imps ).saveAsJpeg( sp.target_dir + sp.names[ i ].replace(extension, "_16bit.jpg") );
							}
						}
					}
				};
			}
			MultiThreading.startAndJoin( threads );
		}
		return sp.target_dir;
	}

	protected double[] evaluateCorrectionXcorr( final int index, final String directory )
	{
		final ImagePlus im1 = new Opener().openImage( directory + sp.names[ index ] );
		im1.setProcessor( sp.names[ index ], im1.getProcessor().convertToShort( false ) );

		int count = 0;
		final ArrayList< Double > xcorrVals = new ArrayList< Double >();
		final ArrayList< Double > xcorrValsGrad = new ArrayList< Double >();

		for ( int i = 0; i < sp.numberOfImages; i++ )
		{
			if ( i == index )
			{
				continue;
			}
			if ( models[ index * ( sp.numberOfImages - 1 ) + count ] == null )
			{
				count++;
				continue;
			}

			final ImagePlus newImg = new Opener().openImage( directory + sp.names[ i + sp.firstImageIndex ] );
			newImg.setProcessor( newImg.getTitle(), newImg.getProcessor().convertToShort( false ) );

			newImg.setProcessor( sp.names[ i + sp.firstImageIndex ], applyTransformToImageInverse( models[ index * ( sp.numberOfImages - 1 ) + count ], newImg.getProcessor() ) );

			// If you want to see the stitching improvement run this
			// ImageProcessor testIp = im1.getProcessor().duplicate();

			// for ( int x=0; x < testIp.getWidth(); x++){
			// for (int y=0; y < testIp.getHeight(); y++){
			// testIp.set(x, y, Math.abs(im1.getProcessor().get(x,y) -
			// newImg.getProcessor().get(x,y)));
			// }
			// }

			// ImagePlus testImg = new ImagePlus(sp.names[index] + " minus " +
			// sp.names[i], testIp);
			// testImg.show();
			// im1.show();
			// newImg.show();

			xcorrVals.add( getXcorrBlackOut( im1.getProcessor(), newImg.getProcessor() ) );

			xcorrValsGrad.add( getXcorrBlackOutGradient( im1.getProcessor(), newImg.getProcessor() ) );
			count++;
		}

		Collections.sort( xcorrVals );
		Collections.sort( xcorrValsGrad );

		//		double[] medians = { xcorrVals.get( xcorrVals.size() / 2 ), xcorrValsGrad.get( xcorrValsGrad.size() / 2 ) };

		double m1 = 0.0, m2 = 0.0;
		for ( int i = 0; i < xcorrVals.size(); i++ )
		{
			m1 += xcorrVals.get( i );
			m2 += xcorrValsGrad.get( i );
		}

		m1 /= xcorrVals.size();
		m2 /= xcorrVals.size();

		final double[] means = { m1, m2 };

		return means;
		//return medians;
	}

	ImageProcessor applyTransformToImageInverse(
			final AbstractAffineModel2D< ? > a, final ImageProcessor ip){
		final ImageProcessor newIp = ip.duplicate();
		newIp.max(0.0);

		for (int x=0; x<ip.getWidth(); x++){
			for (int y=0; y<ip.getHeight(); y++){
				final double[] position = new double[]{x,y};
				//				float[] newPosition = a.apply(position);
				double[] newPosition = new double[]{0,0};
				try
				{
					newPosition = a.applyInverse(position);
				}
				catch ( final NoninvertibleModelException e ) {}

				final int xn = (int) newPosition[0];
				final int yn = (int) newPosition[1];

				if ( (xn >= 0) && (yn >= 0) && (xn < ip.getWidth()) && (yn < ip.getHeight()))
					newIp.set(xn,yn,ip.get(x,y));

			}
		}
		return newIp;
	}

	double getXcorrBlackOutGradient(final ImageProcessor ip1, final ImageProcessor ip2){
		final ImageProcessor ip1g = getGradientSobel(ip1);
		final ImageProcessor ip2g = getGradientSobel(ip2);

		return getXcorrBlackOut(ip1g, ip2g);
	}

	//this blends out gradients that include black pixels to make the sharp border caused
	//by the nonlinear transformation not disturb the gradient comparison
	//FIXME: this should be handled by a mask image!
	ImageProcessor getGradientSobel(final ImageProcessor ip){
		final ImageProcessor ipGrad = ip.duplicate();
		ipGrad.max(0.0);

		for (int i=1; i<ipGrad.getWidth()-1; i++){
			for (int j=1; j<ipGrad.getHeight()-1; j++){
				if(ip.get(i-1,j-1)==0 || ip.get(i-1,j)==0 || ip.get(i-1,j+1)==0 ||
						ip.get(i,j-1)==0 || ip.get(i,j)==0 || ip.get(i,j+1)==0 ||
						ip.get(i+1,j-1)==0 || ip.get(i+1,j)==0 || ip.get(i+1,j+1)==0 )
					continue;

				final double gradX = (double) -ip.get(i-1, j-1) - 2* ip.get(i-1,j) - ip.get(i-1,j+1)
				+ip.get(i+1, j-1) + 2* ip.get(i+1,j) + ip.get(i+1,j+1);

				final double gradY = (double) -ip.get(i-1, j-1) - 2* ip.get(i,j-1) - ip.get(i+1,j-1)
				+ip.get(i-1, j+1) + 2* ip.get(i,j+1) + ip.get(i+1,j+1);

				final double mag = Math.sqrt(gradX*gradX + gradY*gradY);
				ipGrad.setf(i,j,(float) mag);
			}
		}
		return ipGrad;
	}


	//tested the result against matlab routine, this worked fine
	static	double getXcorrBlackOut(ImageProcessor ip1, ImageProcessor ip2){

		ip1 = ip1.convertToFloat();
		ip2 = ip2.convertToFloat();

		//If this is not done, the black area from the transformed image influences xcorr result
		//better alternative would be to use mask images and only calculate xcorr of
		//the region present in both images.
		for (int i=0; i<ip1.getWidth(); i++){
			for (int j=0; j<ip1.getHeight(); j++){
				if (ip1.get(i,j) == 0)
					ip2.set(i,j,0);
				if (ip2.get(i,j) == 0)
					ip1.set(i,j,0);
			}
		}

		//		FloatProcessor ip1f = (FloatProcessor)ip1.convertToFloat();
		//		FloatProcessor ip2f = (FloatProcessor)ip2.convertToFloat();

		final float[] data1 = ( float[] )ip1.getPixels();
		final float[] data2 = ( float[] )ip2.getPixels();

		final double[] data1b = new double[data1.length];
		final double[] data2b = new double[data2.length];

		int count = 0;
		double mean1 = 0.0, mean2 = 0.0;

		for (int i=0; i < data1.length; i++){
			//if ((data1[i] == 0) || (data2[i] == 0))
			//continue;
			data1b[i] = data1[i];
			data2b[i] = data2[i];
			mean1 += data1b[i];
			mean2 += data2b[i];
			count++;
		}

		mean1 /= (double) count;
		mean2 /= (double) count;

		double L2_1 = 0.0, L2_2 = 0.0;
		for (int i=0; i < count; i++){
			L2_1 += (data1b[i] - mean1) * (data1b[i] - mean1);
			L2_2 += (data2b[i] - mean2) * (data2b[i] - mean2);
		}

		L2_1 = Math.sqrt(L2_1);
		L2_2 = Math.sqrt(L2_2);

		double xcorr = 0.0;
		for (int i=0; i < count; i++){
			xcorr += ((data1b[i]-mean1) / L2_1) * ((data2b[i]-mean2) / L2_2);
		}

		//System.out.println("XcorrVal: " + xcorr);
		return xcorr;
	}


	void visualizePoints( final List< List< PointMatch > > inliers)
	{
		final ColorProcessor ip = new ColorProcessor(nlt.getWidth(), nlt.getHeight());
		ip.setColor(Color.red);

		ip.setLineWidth(5);
		for (int i=0; i < inliers.size(); i++){
			for (int j=0; j < inliers.get(i).size(); j++){
				final double[] tmp1 = inliers.get(i).get(j).getP1().getW();
				final double[] tmp2 = inliers.get(i).get(j).getP2().getL();
				ip.setColor(Color.red);
				ip.drawDot((int) tmp2[0], (int) tmp2[1]);
				ip.setColor(Color.blue);
				ip.drawDot((int) tmp1[0], (int) tmp1[1]);
			}
		}

		final ImagePlus points = new ImagePlus("Corresponding Points after correction", ip);
		points.show();
	}

	public void getTransform(final double[][] points1, final double[][] points2, final double[][] transformParams){
		final double[][] p1 = new double[points1.length][3];
		final double[][] p2 = new double[points2.length][3];

		for (int i=0; i < points1.length; i++){
			p1[i][0] = points1[i][0];
			p1[i][1] = points1[i][1];
			p1[i][2] = 100.0;

			p2[i][0] = points2[i][0];
			p2[i][1] = points2[i][1];
			p2[i][2] = 100.0;
		}


		final Matrix s1 = new Matrix(p1);
		final Matrix s2 = new Matrix(p2);
		Matrix t = (s1.transpose().times(s1)).inverse().times(s1.transpose()).times(s2);
		t = t.inverse();
		for (int i=0; i < transformParams.length; i++){
			if (transformParams[i][0] == -10){
				transformParams[i][0] = t.get(0,0);
				transformParams[i][1] = t.get(0,1);
				transformParams[i][2] = t.get(1,0);
				transformParams[i][3] = t.get(1,1);
				transformParams[i][4] = t.get(2,0);
				transformParams[i][5] = t.get(2,1);
			}
		}

		t.print(1, 1);


	}


	static List< Feature >[] extractSIFTFeaturesThreaded(
			final int numberOfImages, final String directory,
			final String[] names ){
		//extract all SIFT Features

		final List< Feature >[] siftFeatures = new ArrayList[numberOfImages];
		final Thread[] threads = MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger(0); // start at second slice

		IJ.showStatus("Extracting SIFT Features");
		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread] = new Thread() {
				@Override
                public void run() {
					for (int i = ai.getAndIncrement(); i < numberOfImages; i = ai.getAndIncrement())
					{
						final ArrayList< Feature > fs = new ArrayList< Feature >();
						final ImagePlus imps = new Opener().openImage(directory + names[i + sp.firstImageIndex]);
						imps.setProcessor(imps.getTitle(), imps.getProcessor().convertToFloat());

						final FloatArray2DSIFT sift = new FloatArray2DSIFT( sp.sift.clone() );
						final SIFT ijSIFT = new SIFT( sift );

						ijSIFT.extractFeatures( imps.getProcessor(), fs );

						Collections.sort( fs );
						IJ.log("Extracting SIFT of image: "+i);

						siftFeatures[i]=fs;

					}
				}
			};
		}
		MultiThreading.startAndJoin(threads);

		return  siftFeatures;
	}

	static protected void extractSIFTPointsThreaded(
			final int index,
			final List< Feature >[] siftFeatures,
			final List< PointMatch >[] inliers,
			final AbstractAffineModel2D< ? >[] models )
	{

		// save all matching candidates
		final List< PointMatch >[] candidates = new List[ siftFeatures.length - 1 ];

		final Thread[] threads = MultiThreading.newThreads();
		final AtomicInteger ai = new AtomicInteger( 0 ); // start at second
		// slice

		for ( int ithread = 0; ithread < threads.length; ++ithread )
		{
			threads[ ithread ] = new Thread()
			{
				@Override
                public void run()
				{
					setPriority( Thread.NORM_PRIORITY );

					for ( int j = ai.getAndIncrement(); j < candidates.length; j = ai.getAndIncrement() )
					{
						final int i = ( j < index ? j : j + 1 );
						candidates[ j ] = FloatArray2DSIFT.createMatches( siftFeatures[ index ], siftFeatures[ i ], 1.5f, null, Float.MAX_VALUE, 0.5f );
					}
				}
			};
		}

		MultiThreading.startAndJoin( threads );

		// get rid of the outliers and save the rigid transformations to match
		// the inliers

		final AtomicInteger ai2 = new AtomicInteger( 0 );
		for ( int ithread = 0; ithread < threads.length; ++ithread )
		{
			threads[ ithread ] = new Thread()
			{
				@Override
                public void run()
				{
					setPriority( Thread.NORM_PRIORITY );
					for ( int i = ai2.getAndIncrement(); i < candidates.length; i = ai2.getAndIncrement() )
					{

						final List< PointMatch > tmpInliers = new ArrayList< PointMatch >();
						// RigidModel2D m =
						// RigidModel2D.estimateBestModel(candidates.get(i),
						// tmpInliers, sp.min_epsilon, sp.max_epsilon,
						// sp.min_inlier_ratio);

						final AbstractAffineModel2D< ? > m;
						switch ( sp.expectedModelIndex )
						{
						case 0:
							m = new TranslationModel2D();
							break;
						case 1:
							m = new RigidModel2D();
							break;
						case 2:
							m = new SimilarityModel2D();
							break;
						case 3:
							m = new AffineModel2D();
							break;
						default:
							return;
						}

						boolean modelFound = false;
						try
						{
							modelFound = m.filterRansac( candidates[ i ], tmpInliers, 1000, sp.maxEpsilon, sp.minInlierRatio, 10 );
						}
						catch ( final NotEnoughDataPointsException e )
						{
							modelFound = false;
						}

						if ( modelFound )
							IJ.log( "Model found:\n  " + candidates[ i ].size() + " candidates\n  " + tmpInliers.size() + " inliers\n  " + String.format( "%.2f", m.getCost() ) + "px average displacement" );
						else
							IJ.log( "No Model found." );

						inliers[ index * ( sp.numberOfImages - 1 ) + i ] = tmpInliers;
						models[ index * ( sp.numberOfImages - 1 ) + i ] = m;
						// System.out.println("**** MODEL ADDED: " +
						// (index*(sp.numberOfImages-1)+i));
					}

				}
			};
		}
		MultiThreading.startAndJoin( threads );

	}

}

