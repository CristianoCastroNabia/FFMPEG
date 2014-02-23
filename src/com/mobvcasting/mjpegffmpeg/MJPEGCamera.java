package com.mobvcasting.mjpegffmpeg;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class MJPEGCamera implements SurfaceHolder.Callback, Camera.PreviewCallback {
	
	public static final String LOGTAG = "MJPEGCamera";
	
	public boolean mRecording = false, mPreviewRunning = false, mPrepared = false;
	public int mMaxRecordingDurationInSeconds, mJpegQuality;
	
	private Camera mCamera = null;
	private Camera.Parameters mCameraParameters = null;
	private int mDesiredWidth, mDesiredHeight, mDesiredFPS, mBufferSize = 0, mFrameCount = 0, mMaxFrames = 0;
	//values for variable amount of buffers
	private int mMaxBuffersSize = 5 * 1024 * 1024 /* 5 MB */, mMinBuffers = 2, mMaxBuffers = 5;

	private String mAssetDirectory = "/data/data/com.mobvcasting.mjpegffmpeg/";
	private String mCacheDirectory = null;
	private String mJPEGBasePath = null;
	
	private MJPEGCameraProcessVideo mProcessVideo = null;
	
	private String[] mLibraryAssets = {"ffmpeg",
			"libavcodec.so", "libavcodec.so.52", "libavcodec.so.52.99.1",
			"libavcore.so", "libavcore.so.0", "libavcore.so.0.16.0",
			"libavdevice.so", "libavdevice.so.52", "libavdevice.so.52.2.2",
			"libavfilter.so", "libavfilter.so.1", "libavfilter.so.1.69.0",
			"libavformat.so", "libavformat.so.52", "libavformat.so.52.88.0",
			"libavutil.so", "libavutil.so.50", "libavutil.so.50.34.0",
			"libswscale.so", "libswscale.so.0", "libswscale.so.0.12.0"
	};
	
	private Context mContext = null;
	
	interface MJPEGCameraRecordProgressListener {
		
		public void recordingTimeChanged(int frame, int maxFrames, float seconds);
		public void maxRecordTimeReached();
		
	}
	
	interface MJPEGCameraProcessMovieProgressListener {
		
		public void startedProcessingMovie();
		public void finishedProcessingMovie(String outFile);
		public void rawImageToJPEGProgressUpdate(float percentage, int frame, int maxFrames);
		public void movieEncodeProgressUpdate(float percentage, int frame, int maxFrames);
		
	}
	
	private MJPEGCameraRecordProgressListener mRecordProgressListener = null;
	private MJPEGCameraProcessMovieProgressListener mProcessMovieProgressListener = null;
	
	private NumberFormat mFileCountFormatter = null;
	private Rect mImageRect = null;
	private BufferedOutputStream[] mJPEGOutputStreams = null;
	private File[] mJPEGFiles = null;
	private BufferedOutputStream mFFMpegStream = null;
	
	public MJPEGCamera(Context context, SurfaceView surfaceView, MJPEGCameraParameters cameraParameters, MJPEGCameraRecordProgressListener recordProgressListener, MJPEGCameraProcessMovieProgressListener processMovieProgressListener) {
		super();
		mContext = context;
		mCacheDirectory = Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/";
		mRecordProgressListener = recordProgressListener;
		mProcessMovieProgressListener = processMovieProgressListener;
		mFileCountFormatter = new DecimalFormat("00000");
		
		moveAssetsIfNeeded();
		setupFFMpegPermissions();
		setupAndCleanCacheDir();
		setDesiredParameters(cameraParameters);
		
		surfaceView.getHolder().addCallback(this);
	}
	
	private void moveAssetsIfNeeded(){
		AssetManager assetManager = mContext.getAssets();
		for (String asset : mLibraryAssets) {
			InputStream ffmpegInputStream  = null;
			try {
				String outPath = mAssetDirectory + asset;
				//only move/copy asset if it doesn't exist in the cache directory already
				if(!(new File(outPath)).exists()){
					ffmpegInputStream = assetManager.open(asset);
					MJPEGCameraFileMover fm = new MJPEGCameraFileMover(ffmpegInputStream,outPath);
			        fm.moveIt();
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if(null!=ffmpegInputStream){
					try {
						ffmpegInputStream.close();
					} catch(IOException e){
						e.printStackTrace();
					}
				}
			}
        }
	}
	
	private void setupFFMpegPermissions() {
		
		Process process = null;
        
        try {
        	String[] args = {"/system/bin/chmod", "755", mAssetDirectory+"ffmpeg"};
        	process = new ProcessBuilder(args).start();        	
        	process.waitFor();		
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			if(null!=process){
				process.destroy();
			}
		}
		
	}
	
	private void setupAndCleanCacheDir() {
		
		File savePath = new File(mCacheDirectory);
		savePath.mkdirs();
		
		if (savePath.isDirectory()) {
	        for (String child : savePath.list()) {
	            (new File(savePath, child)).delete();
	        }
	    }
	}
	
	private String getFrameBufferFileName() {
		return mCacheDirectory + "frame_buffer.buf";
	}
	
	private void setDesiredParameters(MJPEGCameraParameters params){
		
		//set default params if none given
		if(null==params){
			params = new MJPEGCameraParameters();
		}
		
		mDesiredWidth = params.width;
		mDesiredHeight = params.height;
		mDesiredFPS = params.fps;
		mMaxRecordingDurationInSeconds = params.maxDuration;
		mJpegQuality = params.JpegQuality;
	}
	
	//TODO: refactor/enhance this method
	private void setSupportedFormats(){
        List<Integer> supportedPreviewFormats = mCameraParameters.getSupportedPreviewFormats();
        Iterator<Integer> supportedPreviewFormatsIterator = supportedPreviewFormats.iterator();
        while(supportedPreviewFormatsIterator.hasNext()){
            Integer previewFormat =supportedPreviewFormatsIterator.next();
            // 16 ~ NV16 ~ YCbCr
            // 17 ~ NV21 ~ YCbCr ~ DEFAULT
            // 4  ~ RGB_565
            // 256~ JPEG
            // 20 ~ YUY2 ~ YcbCr ...
            // 842094169 ~ YV12 ~ 4:2:0 YCrCb comprised of WXH Y plane, W/2xH/2 Cr & Cb. see documentation
            Log.v(LOGTAG,"Supported preview format:"+previewFormat);
            if (true || previewFormat == ImageFormat.YUY2) {
                mCameraParameters.setPreviewFormat(previewFormat);
                Log.v(LOGTAG,"SETTING FANCY YV12 FORMAT");
                return;
            }
        }
	}
	
	@SuppressLint("NewApi")
	private int[] checkBestSuitedFPSRange(){
		int[] bestSuited = null;
		int minDesired = mDesiredFPS*1000;
		int maxDesired = minDesired;
		for(int[] ranges : mCameraParameters.getSupportedPreviewFpsRange()){
			if(null==bestSuited){
				bestSuited = ranges;
			}
			else {
				int minR = ranges[0];
				int maxR = ranges[1];
				//if its the range we are looking for then set it and exit loop
				if(minR==minDesired && maxR==maxDesired){
					bestSuited = ranges;
					break;
				}
				else {
					int minOld = bestSuited[0];
					int maxOld = bestSuited[1];
					
					//otherwise try the one with range more close to ours
					if(minR >= minDesired){
						//if the max is ok...
						if(maxR >= maxDesired){
							//...but previous wasn't...
							if(maxOld < maxDesired){
								bestSuited = ranges;
							}
							else {
								//...or of we are closer to what we want
								if(maxR < maxOld){
									bestSuited = ranges;
								}
							}
						}
						else {
							//we only have a change of being better if previos max wasn't ok...
							if(maxOld < maxDesired){
								//...and we are closer
								if(maxR>maxOld){
									bestSuited = ranges;
								}
							}
						}
					}
					else {
						
						//if the minimum is greater than what we had use this range
						if(minR>minOld){
							bestSuited = ranges;
						}
						//otherwise only use it if minimum is at least what we had...
						else if(minR==minOld){
							//...if so, then this is best suited than before if:
							//1 - the max is what we want
							//2 - the max is greater than what we want but previous max wasn't
							//3 - the max is greater than what we want but closer that previous max
							//4 - the max is lower than what we want but greater than previous one
							if((maxR == maxDesired)
								||(maxR > maxDesired && maxOld < maxDesired)
								||(maxR > maxDesired && maxR < maxOld)
								||(maxR < maxDesired && maxR > maxOld)){
								bestSuited = ranges;
							}
						}
					}
				}
			}
		}
		return bestSuited;
	}
	
	public void surfaceCreated(SurfaceHolder holder) {
		mCamera = Camera.open();
	}
	
	@SuppressLint("NewApi")
	public void surfaceChanged(final SurfaceHolder holder, int format, int width, int height) {
		if (!mRecording) {
			if (mPreviewRunning){
				mCamera.stopPreview();
			}
			try {
				mCameraParameters = mCamera.getParameters();
				setSupportedFormats();
				mCameraParameters.setPreviewSize(mDesiredWidth, mDesiredHeight);
				mCameraParameters.setPreviewFrameRate(mDesiredFPS);
				//TODO: add setting for this
				//Log.v(LOGTAG, "Focus "+mCameraParameters.getFocusMode()+" WB "+mCameraParameters.getWhiteBalance()+" scene "+mCameraParameters.getSceneMode());
				//mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
				
				
				if (Build.VERSION.SDK_INT >= 9) {
					mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
					int[] bestFPSRange = checkBestSuitedFPSRange();
					if(null!=bestFPSRange){
						mCameraParameters.setPreviewFpsRange(mDesiredFPS*1000,mDesiredFPS*1000);//bestFPSRange[0], bestFPSRange[1]);
					}
				}
				
				if (Build.VERSION.SDK_INT >= 14) {
					mCameraParameters.setRecordingHint(true);
					/*
					if(mCameraParameters.isAutoWhiteBalanceLockSupported()){
						mCameraParameters.setAutoWhiteBalanceLock(true);
					}
					if(mCameraParameters.isAutoExposureLockSupported()){
						mCameraParameters.setAutoExposureLock(true);
					}
					*/
				}
				
				
				try {
					mCamera.setParameters(mCameraParameters);
				}
				catch(Exception e){
					e.printStackTrace();
				}
						
				Log.d(LOGTAG, "parameters Width " + mCameraParameters.getPreviewSize().width);
				Log.d(LOGTAG, "parameters Height " + mCameraParameters.getPreviewSize().height);
				Log.d(LOGTAG, "parameters framerate " + mCameraParameters.getPreviewFrameRate());
				
				Log.v(LOGTAG,"Setting up preview callback buffer");
				mBufferSize = (mCameraParameters.getPreviewSize().width * mCameraParameters.getPreviewSize().height * ImageFormat.getBitsPerPixel(mCameraParameters.getPreviewFormat()) / 8);
				Log.d(LOGTAG, "bufferSize = " + mBufferSize);		
				
		        mImageRect = new Rect(0,0,mDesiredWidth,mDesiredHeight);

				Log.v(LOGTAG,"setPreviewCallbackWithBuffer");
				
				mCamera.setPreviewCallbackWithBuffer(this);
				
				int nBuffers = (mMaxBuffersSize / mBufferSize);
				if(nBuffers<mMinBuffers){
					nBuffers = mMinBuffers;
				}
				else if(nBuffers > mMaxBuffers){
					nBuffers = mMaxBuffers;
				}

				for (int i = 0; i < nBuffers; i++) {
	                byte [] cameraBuffer = new byte[mBufferSize];
	                mCamera.addCallbackBuffer(cameraBuffer);
	            }
				
				mCamera.setPreviewDisplay(holder);
				
				mJPEGBasePath = mCacheDirectory + "frame_";

				Log.v(LOGTAG,"startPreview");
				mCamera.startPreview();
				mPreviewRunning = true;
			}
			catch (IOException e) {
				Log.e(LOGTAG,e.getMessage());
				e.printStackTrace();
			}	
			
			//TODO: testing ffmpeg stdin
			String outFile = Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/video.mp4";
	    	processMovieToFile(outFile, null);
			
		}
	}

	//TODO: improve cleanup code
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.v(LOGTAG, "surfaceDestroyed");
		if (mRecording) {
			mRecording = false;
		}

		mPreviewRunning = false;
		mCamera.release();
	}
	
	private boolean checkIfMaxRecordReached(){
		if (mFrameCount >= mMaxFrames && mRecording){
			if(null!=mRecordProgressListener){
				mRecordProgressListener.maxRecordTimeReached();
			}
			endRecording();
			return true;
		}
		return false;
	}

	public void onPreviewFrame(byte[] b, Camera c) {
		
		//check if we have reached max frames (we will check again after writing one)
				if (checkIfMaxRecordReached()){
					return;
				}
					
				if (mRecording) {
					Log.v(LOGTAG,"Started Writing Frame");
					
					int previewFormat = mCameraParameters.getPreviewFormat();
					Size size = mCameraParameters.getPreviewSize();
					Rect r = mImageRect;
					
					try {
						
						boolean useFFMpegStream = (mFFMpegStream!=null);
						BufferedOutputStream bos = (useFFMpegStream?mFFMpegStream:mJPEGOutputStreams[mFrameCount]);
						Log.v(LOGTAG,"Creating yuv image");
						YuvImage im = new YuvImage(b, previewFormat, size.width,size.height, null);
						Log.v(LOGTAG,"compressing to jpeg on "+(useFFMpegStream?"memory":"disk"));
						
						im.compressToJpeg(r, mJpegQuality, bos);
						//Log.v(LOGTAG,"Closing output stream");
						//bos.flush();
						//bos.close();
						//mJPEGOutputStreams[mFrameCount] = null;
						
						if(!useFFMpegStream){
							mJPEGFiles[mFrameCount] = null;
						}
						
						
						//mFrameBufferFileChannel.write(ByteBuffer.wrap(b));
						
						
						
						Log.v(LOGTAG,"Finished Writing Frame");
					} catch (Exception e) {
						Log.e(LOGTAG, "Failed to write frame "+mFrameCount);
						e.printStackTrace();
						//ffmpeg stdin test
						endRecording();
					}
					mFrameCount++;
					
					if(null!=mRecordProgressListener){
						mRecordProgressListener.recordingTimeChanged(mFrameCount, mMaxFrames, (float)(mFrameCount*mMaxRecordingDurationInSeconds)/(float)mMaxFrames);
					}
				}
				mCamera.addCallbackBuffer(b);
				
				//check if we have reached max frames: we checked above but we check here too
				//we want to finish here but checking above ensures that even if this callback is called after we finish we don't write it again
				if (checkIfMaxRecordReached()){
					return;
				}
		
		mCamera.addCallbackBuffer(b);
	}
	
	public boolean canRecord(/* settings */){
		//check if the camera can record with the specified settings
		//don't forget to check for free space
		return true;
	}
	
	public void prepare(){
		
		if(mPrepared){
			return;
		}
		
		mMaxFrames = mMaxRecordingDurationInSeconds * mCameraParameters.getPreviewFrameRate();

		if(null!=mProcessVideo){
			mFFMpegStream = new BufferedOutputStream(mProcessVideo.ffmpegProcess.getOutputStream());
			mJPEGOutputStreams = null;
			mJPEGFiles = null;
		}
		else {
			mFFMpegStream = null;
			try {
				mJPEGOutputStreams = new BufferedOutputStream[mMaxFrames];
				mJPEGFiles = new File[mMaxFrames];
				for(int i = 0; i < mMaxFrames; i++){
					File jpegFile = new File(mJPEGBasePath + mFileCountFormatter.format(i) + ".jpg");
					jpegFile.delete();
					jpegFile.createNewFile();
					mJPEGFiles[i] = jpegFile;
					mJPEGOutputStreams[i] = new BufferedOutputStream(new FileOutputStream(jpegFile));
				}
			} catch(Exception e){
				e.printStackTrace();
			}
		}
		
		System.gc();
	}
	
	public void startRecording() {
		if(!mRecording){
			if(!mPrepared){
				prepare();
			}
			mFrameCount = 0;
			if(null!=mRecordProgressListener){
				mRecordProgressListener.recordingTimeChanged(0, mMaxFrames, 0.0f);
			}
			mRecording = true;
		}
		
	}
	
	public void resumeRecording(){
		mRecording = true;
		//check to pause processing raw frames to jpeg
	}

	public void pauseRecording() {
		mRecording = false;
		//check to start processing raw frames to jpeg
	}
	
	public void endRecording() {
		if(mRecording){
			mRecording = false;
			mPrepared = false;
			mCamera.stopPreview();
			mPreviewRunning = false;
			
			if(null!=mFFMpegStream){
				try {
					mFFMpegStream.flush();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				try {
					mFFMpegStream.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				mFFMpegStream = null;
				
			}
			else {
				for(int i = 0; i < mMaxFrames; i++){
					BufferedOutputStream bos = mJPEGOutputStreams[i];
					try {
						bos.flush();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						bos.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					bos = null;
					mJPEGOutputStreams[i]=null;
					
					if(null!=mJPEGFiles[i]){
						mJPEGFiles[i].delete();
						mJPEGFiles[i] = null;
					}
				}
				mJPEGOutputStreams = null;
				mJPEGFiles = null;
			}

		}
	}
	
	public void processMovieToFile(String outFile, String audioFileToAppend){
		//TODO: testing ffmpeg stdin
		if(null==mProcessVideo){
			mProcessVideo = new MJPEGCameraProcessVideo(this, outFile, audioFileToAppend);
			mProcessVideo.execute();
		}
	} 
	
	static public class MJPEGCameraParameters {
		
		public int width = 640, height = 480, fps = 30, maxDuration = 9, JpegQuality = 80;
		
		public MJPEGCameraParameters(){
			super();
		}
		
	}
	
	private class MJPEGCameraFileMover {

		private InputStream inputStream = null;
		private String destination = null;
		
		public MJPEGCameraFileMover(InputStream _inputStream, String _destination) {
			inputStream = _inputStream;
			destination = _destination;
		}
		
		public void moveIt() throws IOException {
		
			File destinationFile = new File(destination);
			OutputStream destinationOut = new BufferedOutputStream(new FileOutputStream(destinationFile));
				
			int numRead;
			byte[] buf = new byte[1024];
			while ((numRead = inputStream.read(buf) ) >= 0) {
				destinationOut.write(buf, 0, numRead);
			}
			    
			destinationOut.flush();
			destinationOut.close();
		}
	}
	
	private class MJPEGCameraProcessVideo extends AsyncTask<Void, Integer, Void> {
		
		private MJPEGCamera mCamera = null;
		private MJPEGCameraProcessMovieProgressListener mListener = null;
		private String mOutFile = null, mAudioFile = null;
		public Process ffmpegProcess = null;
		
		public MJPEGCameraProcessVideo(MJPEGCamera camera, String outFile, String audioFile){
			super();
			mCamera = camera;
			mListener = camera.mProcessMovieProgressListener;
			mOutFile = outFile;
			mAudioFile = audioFile;
		}
		
		@Override
		protected void onPreExecute() {
			// TODO Auto-generated method stub
			super.onPreExecute();
			/*
			if(null!=mListener){
				mListener.startedProcessingMovie();
			}
			*/
		}
		
		@Override
		protected void onPostExecute(Void result) {
			if(null!=mListener){
				mListener.finishedProcessingMovie(mOutFile);
			}
			super.onPostExecute(result);
		}
		
		@Override
		protected Void doInBackground(Void ... params) {

			Camera.Parameters camParams = mCamera.mCameraParameters;

	        ffmpegProcess = null;

	        try {
	        	
	        	//ffmpeg -r 10 -b 1800 -i %03d.jpg test1800.mp4
	        	// 00000
	        	// /data/data/com.mobvcasting.ffmpegcommandlinetest/ffmpeg -r p.getPreviewFrameRate() -b 1000 -i frame_%05d.jpg video.mov
	        	
	        	Log.d("XXX", "ffmpeg framerate " + camParams.getPreviewFrameRate());
	        	
	        	//TODO: encode together with audio
	        	String videoFile = mOutFile;
	        	
	        	//delete any previous contents of video
	        	(new File(videoFile)).delete();
	        	
				//String[] args2 = {"/data/data/com.mobvcasting.ffmpegcommandlinetest/ffmpeg", "-y", "-i", "/data/data/com.mobvcasting.ffmpegcommandlinetest/", "-vcodec", "copy", "-acodec", "copy", "-f", "flv", "rtmp://192.168.43.176/live/thestream"};
				/*
	        	String[] ffmpegCommand = {mCamera.mAssetDirectory+"ffmpeg", "-r", ""+camParams.getPreviewFrameRate(), 
										"-b", "" + 200000*8, "-vcodec", "mjpeg", 
										"-i", mCamera.mCacheDirectory + "frame_%05d.jpg", 
										videoFile};
				*/

	        	String[] ffmpegCommand = {mCamera.mAssetDirectory+"ffmpeg", 
	        			"-f","image2pipe",
	        			"-vcodec", "mjpeg", 
	        			"-r", ""+camParams.getPreviewFrameRate(), 
	        			"-s",""+camParams.getPreviewSize().width+"x"+camParams.getPreviewSize().height,
	        			"-analyzeduration","0",
	        			"-i","-",
	        			
						//"-f", "mp4",
						"-vcodec", "mjpeg",
						"-r", ""+camParams.getPreviewFrameRate(), 
						//"-b", "" + 200000*8, 
						videoFile};
				
				
				
				ffmpegProcess = new ProcessBuilder(ffmpegCommand).redirectErrorStream(true).start();         	
				
				if(null!=mListener){
					new Handler(Looper.getMainLooper()).post(new Runnable() {
						
						@Override
						public void run() {
							if(null!=mListener){
								mListener.startedProcessingMovie();
							}
						}
					});
				}
				
				
				//OutputStream ffmpegOutStream = ffmpegProcess.getOutputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()));

				String line;
				
				Log.v(LOGTAG,"***Starting FFMPEG***");
				while ((line = reader.readLine()) != null)
				{
					
					//02-22 12:37:39.319: V/MJPEGCamera(4269): ***frame=  128 fps= 16 q=2.0 Lsize=     505kB time=4.27 bitrate= 969.1kbits/s    ***
					/*
					if(null!=mListener && line.startsWith("frame=")){
						int fpsIdx = line.indexOf("fps=");
						if(fpsIdx != -1){
							int frameCount = mCamera.mFrameCount;
							int frame = Math.min(Integer.parseInt(line.substring(6, fpsIdx-1).trim()),frameCount);
							mListener.movieEncodeProgressUpdate((float)frame/frameCount, frame, frameCount);
						}
						else {
							Log.v(LOGTAG,"***"+line+"***");
						}
					}
					*/
					Log.v(LOGTAG,"***"+line+"***");
				}
				Log.v(LOGTAG,"***Ending FFMPEG***");
	
	    
	        } catch (IOException e) {
	        	e.printStackTrace();
	        }
	        
	        if (ffmpegProcess != null) {
	        	ffmpegProcess.destroy();        
	        }
     
	        return null;
		}

	}
}
