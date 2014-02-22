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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.List;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class MJPEGCamera implements SurfaceHolder.Callback, Camera.PreviewCallback {
	
	public static final String LOGTAG = "MJPEGCamera";
	
	public boolean mCanProcessFramesWhilePaused = true, mRecording = false, mPreviewRunning = false;
	public int mMaxRecordingDurationInSeconds = 10;
	
	private Camera mCamera = null;
	private Camera.Parameters mCameraParameters = null;
	private int mDesiredWidth = 640, mDesiredHeight = 480, mDesiredFPS = 30, mBufferSize = 0, mFrameCount = 0, mMaxFrames = 0;
	//values for variable amount of buffers
	private int mMaxBuffersSize = 5 * 1024 * 1024 /* 5 MB */, mMinBuffers = 2, mMaxBuffers = 5;
	private File mFrameBufferFile = null;
	private FileOutputStream mFrameBufferFileOutputStream = null;
	private FileChannel mFrameBufferFileChannel = null;
	private String mAssetDirectory = "/data/data/com.mobvcasting.mjpegffmpeg/";
	private String mCacheDirectory = null;
	
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
	
	public MJPEGCamera(Context context, SurfaceView surfaceView, MJPEGCameraRecordProgressListener recordProgressListener, MJPEGCameraProcessMovieProgressListener processMovieProgressListener) {
		super();
		mContext = context;
		mCacheDirectory = Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/";
		mRecordProgressListener = recordProgressListener;
		mProcessMovieProgressListener = processMovieProgressListener;
		
		moveAssetsIfNeeded();
		setupFFMpegPermissions();
		setupAndCleanCacheDir();
		
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
	
	public void surfaceCreated(SurfaceHolder holder) {
		mCamera = Camera.open();
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
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
				mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
				
				mCamera.setParameters(mCameraParameters);
						
				Log.d(LOGTAG, "parameters Width " + mCameraParameters.getPreviewSize().width);
				Log.d(LOGTAG, "parameters Height " + mCameraParameters.getPreviewSize().height);
				Log.d(LOGTAG, "parameters framerate " + mCameraParameters.getPreviewFrameRate());
				
				Log.v(LOGTAG,"Setting up preview callback buffer");
				mBufferSize = (mCameraParameters.getPreviewSize().width * mCameraParameters.getPreviewSize().height * ImageFormat.getBitsPerPixel(mCameraParameters.getPreviewFormat()) / 8);
				Log.d(LOGTAG, "bufferSize = " + mBufferSize);		
				
		        try {
		        	//delete any previous frame buffer if exists and create a new empty one
		        	mFrameBufferFile = new File(getFrameBufferFileName());
		        	mFrameBufferFile.delete();
		        	mFrameBufferFile.createNewFile();
		        	mFrameBufferFileOutputStream = new FileOutputStream(mFrameBufferFile);
		        	mFrameBufferFileChannel = mFrameBufferFileOutputStream.getChannel();
				} catch (FileNotFoundException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

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

				Log.v(LOGTAG,"startPreview");
				mCamera.startPreview();
				mPreviewRunning = true;
			}
			catch (IOException e) {
				Log.e(LOGTAG,e.getMessage());
				e.printStackTrace();
			}	
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
			
			try {
				mFrameBufferFileChannel.write(ByteBuffer.wrap(b));
				Log.v(LOGTAG,"Finished Writing Frame");
			} catch (Exception e) {
				Log.e(LOGTAG, "Failed to write frame "+mFrameCount);
				e.printStackTrace();
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
		
	}
	
	public boolean canRecord(/* settings */){
		//check if the camera can record with the specified settings
		//don't forget to check for free space
		return true;
	}
	
	public void prepare(/* settings */){
		//clear previous data (image buffers, etc..)
		//set settings
	}
	
	public void startRecording() {
		if(!mRecording){
			mMaxFrames = mMaxRecordingDurationInSeconds * mCameraParameters.getPreviewFrameRate();
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
			try {
				mFrameBufferFileChannel.force(false);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				try {
					mFrameBufferFileChannel.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	
	public void processMovieToFile(String outFile, String audioFileToAppend){
		//don't forget to clean files after use
		mProcessVideo = new MJPEGCameraProcessVideo(this, outFile, audioFileToAppend);
		mProcessVideo.execute();
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
		private NumberFormat mFileCountFormatter = null;
		private int mCompressionQuality = 90;
		
		public MJPEGCameraProcessVideo(MJPEGCamera camera, String outFile, String audioFile){
			super();
			mCamera = camera;
			mListener = camera.mProcessMovieProgressListener;
			mFileCountFormatter = new DecimalFormat("00000");
			mOutFile = outFile;
			mAudioFile = audioFile;
		}
		
		@Override
		protected void onPreExecute() {
			// TODO Auto-generated method stub
			super.onPreExecute();
			if(null!=mListener){
				mListener.startedProcessingMovie();
			}
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
			
			//TODO: why the sleep?
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			

			File jpegFile = null;
			String formattedFileCount = null;
			FileOutputStream fos = null;
			
			File inputFile = mCamera.mFrameBufferFile;
			FileInputStream fis = null;
			BufferedInputStream bis = null;
			
			NumberFormat fileCountFormatter = mFileCountFormatter;
			String basePath = mCamera.mCacheDirectory + "frame_";
			Camera.Parameters camParams = mCamera.mCameraParameters;
			int frameCount = mCamera.mFrameCount-1;
			
			try {
				fis = new FileInputStream(inputFile);
				bis = new BufferedInputStream(fis);
				int bufferSize = mCamera.mBufferSize;
				byte[] buffer = new byte[bufferSize];
				int i = 0;
				int bRead = 0;
				
				int previewFormat = camParams.getPreviewFormat();
				Size size = camParams.getPreviewSize();
				Rect r = new Rect(0,0,size.width,size.height);
				int compressionQuality = mCompressionQuality;
				while((bRead = bis.read(buffer, 0, bufferSize))>0){
					Log.v(LOGTAG,"Read "+bRead + " bytes from buffer file ("+i+"/"+frameCount+")");
					formattedFileCount = fileCountFormatter.format(i); 
					jpegFile = new File(basePath + formattedFileCount + ".jpg");
					fos = new FileOutputStream(jpegFile);
					YuvImage im = new YuvImage(buffer, previewFormat, size.width,size.height, null);
					im.compressToJpeg(r, compressionQuality, fos);
					fos.flush();
					fos.close();
					if(null!=mListener){
						mListener.rawImageToJPEGProgressUpdate((float)i/frameCount, i, frameCount);
					}
					i++;
				}
			} catch (FileNotFoundException e2) {
				// TODO Auto-generated catch block
				e2.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} finally {
				if(null!=fos){
					try {
						fos.flush();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					try {
						fos.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if(null!=bis){
					try {
						bis.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if(null!=fis){
					try {
						fis.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			inputFile.delete();
			inputFile = null;
			/*
			for( int i = 0; i < FRAMES; i++){
				try{
					formattedFileCount = fileCountFormatter2.format(i);  
					bFile = new File(Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_" + formattedFileCount + ".buf");
					if (!bFile.exists()){
//						Log.d("XXX", "File not found " + formattedFileCount);
						continue;	
					}
					Log.d("XXX", "processing frame " + i);

					
					byte[] buffer = new byte[bufferSize];
							
					BufferedInputStream buf = new BufferedInputStream(new FileInputStream(bFile));
					buf.read(buffer, 0, bufferSize);
					

					jpegFile = new File(Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_" + formattedFileCount + ".jpg");
					fos = new FileOutputStream(jpegFile);
//					bos = new BufferedOutputStream(fos);
					YuvImage im = new YuvImage(buffer, camera.getParameters().getPreviewFormat(), previewWidth, previewHeight, null);
					Rect r = new Rect(0,0,previewWidth,previewHeight);//cropStartX, cropStartY, cropEndX, cropEndY);
					im.compressToJpeg(r, 100, fos);
					fos.flush();
					fos.close();
				}
				catch (Exception e){
					Log.e("YYY", "Could not save frame!");
					e.printStackTrace();
				}
				
			}
			*/

	        Process ffmpegProcess = null;
	        //TODO: why sleep?
	        try {
				Thread.sleep(2000);
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
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
				String[] ffmpegCommand = {mCamera.mAssetDirectory+"ffmpeg", "-r", ""+camParams.getPreviewFrameRate(), 
										"-b", "" + 200000*8, "-vcodec", "mjpeg", 
										"-i", mCamera.mCacheDirectory + "frame_%05d.jpg", 
										videoFile};
				
				
				
				ffmpegProcess = new ProcessBuilder(ffmpegCommand).redirectErrorStream(true).start();         	
				
				//OutputStream ffmpegOutStream = ffmpegProcess.getOutputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()));

				String line;
				
				Log.v(LOGTAG,"***Starting FFMPEG***");
				while ((line = reader.readLine()) != null)
				{
					
					//02-22 12:37:39.319: V/MJPEGCamera(4269): ***frame=  128 fps= 16 q=2.0 Lsize=     505kB time=4.27 bitrate= 969.1kbits/s    ***
					if(null!=mListener && line.startsWith("frame=")){
						int fpsIdx = line.indexOf("fps=");
						if(fpsIdx != -1){
							int frame = Math.min(Integer.parseInt(line.substring(6, fpsIdx-1).trim()),frameCount);
							mListener.movieEncodeProgressUpdate((float)frame/frameCount, frame, frameCount);
						}
						else {
							Log.v(LOGTAG,"***"+line+"***");
						}
					}
				}
				Log.v(LOGTAG,"***Ending FFMPEG***");
	
	    
	        } catch (IOException e) {
	        	e.printStackTrace();
	        }
	        
	        if (ffmpegProcess != null) {
	        	ffmpegProcess.destroy();        
	        }
	        
	        //clear the temporary jpeg frames
	        while(frameCount>=0){
				formattedFileCount = fileCountFormatter.format(frameCount); 
				jpegFile = new File(basePath + formattedFileCount + ".jpg");
				jpegFile.delete();
				frameCount--;
			}
	        	        
	        return null;
		}

	}
}
