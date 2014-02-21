package com.mobvcasting.mjpegffmpeg;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.List;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.CameraProfile;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputFilter;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.Toast;

public class MJPEGFFMPEGTest extends Activity implements OnClickListener, 
									SurfaceHolder.Callback, Camera.PreviewCallback { 
	
	public static final String LOGTAG = "MJPEG_FFMPEG";
	
	private SurfaceHolder holder;
	private CamcorderProfile camcorderProfile;
	private Camera camera;	
	
	byte[] previewCallbackBuffer;
	
	private int FRAMES = 300,DESIRED_WIDTH = 640,DESIRED_HEIGHT = 480;
	
	boolean recording = false;
	boolean previewRunning = false;	
	
	int width;
	int height;
	int bufferSize;
	
	int dim = 720;
	int previewHeight;
	int previewWidth;
	
	int cropStartX;
	int cropStartY;
	int cropEndX;
	int cropEndY;
	
	
	File bFile;
	FileOutputStream fos; 
	BufferedOutputStream bos = null;

	MappedByteBuffer mbb = null;
	RandomAccessFile raf = null;
	FileChannel fc = null;
	
//	File jpegFile;			
	int fileCount = 0;
	
//	FileOutputStream fos;
//	BufferedOutputStream bos;
	Button recordButton;
	
	Camera.Parameters p;
	
	NumberFormat fileCountFormatter = new DecimalFormat("00000");	
	NumberFormat fileCountFormatter2 = new DecimalFormat("00000");

//	String formattedFileCount;
	
	ProcessVideo processVideo;
		
	String[] libraryAssets = {"ffmpeg",
			"libavcodec.so", "libavcodec.so.52", "libavcodec.so.52.99.1",
			"libavcore.so", "libavcore.so.0", "libavcore.so.0.16.0",
			"libavdevice.so", "libavdevice.so.52", "libavdevice.so.52.2.2",
			"libavfilter.so", "libavfilter.so.1", "libavfilter.so.1.69.0",
			"libavformat.so", "libavformat.so.52", "libavformat.so.52.88.0",
			"libavutil.so", "libavutil.so.50", "libavutil.so.50.34.0",
			"libswscale.so", "libswscale.so.0", "libswscale.so.0.12.0"
	};


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        

        
        for (int i = 0; i < libraryAssets.length; i++) {
			try {
				InputStream ffmpegInputStream = this.getAssets().open(libraryAssets[i]);
		        FileMover fm = new FileMover(ffmpegInputStream,"/data/data/com.mobvcasting.mjpegffmpeg/" + libraryAssets[i]);
		        fm.moveIt();
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
        

        //fout init was here
        
        
        Process process = null;
        
        try {
        	String[] args = {"/system/bin/chmod", "755", "/data/data/com.mobvcasting.mjpegffmpeg/ffmpeg"};
        	process = new ProcessBuilder(args).start();        	
        	try {
				process.waitFor();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
        	process.destroy();
        	 			
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		File savePath = new File(Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/");
		savePath.mkdirs();
		
		
		if (savePath.isDirectory()) {
	        String[] children = savePath.list();
	        for (int i = 0; i < children.length; i++) {
	            //new File(savePath, children[i]).delete();
	        }
	    }
		
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);

		setContentView(R.layout.main);
		
		recordButton = (Button) this.findViewById(R.id.RecordButton);
		recordButton.setOnClickListener(this);
		
		camcorderProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_LOW);
		
		Log.d("XXX", "videoFrameWidth " + camcorderProfile.videoFrameWidth);
		Log.d("XXX", "videoFrameHeight " + camcorderProfile.videoFrameHeight);
		Log.d("XXX", "videoFrameRate " + camcorderProfile.videoFrameRate);

		SurfaceView cameraView = (SurfaceView) findViewById(R.id.CameraView);
		
		holder = cameraView.getHolder();
		holder.addCallback(this);
		
//		holder.setFixedSize(720, 720);

		cameraView.setClickable(true);
		cameraView.setOnClickListener(this);		

    }
    
    
    public void processVideo(){
		processVideo = new ProcessVideo();
		processVideo.execute();
    }

	public void onClick(View v) {
		Log.d("XXX", "click!");
		if (recording) 
		{
			recording = false;	
			Log.v(LOGTAG, "Recording Stopped");
			/*
			try {
				bos.flush();
				bos.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			*/
			/*
			mbb.force();
			mbb = null;
			try {
				raf.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			raf = null;
			*/
			try {
				fc.force(false);
				fc.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// Convert to video
			processVideo();
		} 
		else
		{
			recording = true;
			Log.v(LOGTAG, "Recording Started");
		}
	}

	public void surfaceCreated(SurfaceHolder holder) {
		Log.v(LOGTAG, "surfaceCreated");
		
		camera = Camera.open();
		
			
		/*
		try {
			camera.setPreviewDisplay(holder);
			camera.startPreview();
			previewRunning = true;
		}
		catch (IOException e) {
			Log.e(LOGTAG,e.getMessage());
			e.printStackTrace();
		}	
		*/
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.v(LOGTAG, "surfaceChanged");
		
		Log.d("XXX", "cameraView Width " + width);
		Log.d("XXX", "cameraView Height " + height);

		if (!recording) {
			if (previewRunning){
				camera.stopPreview();
			}

			try {
				p = camera.getParameters();
				setSupportedFormats();

//				p.setPreviewSize(camcorderProfile.videoFrameWidth, camcorderProfile.videoFrameHeight);
//			    p.setPreviewFrameRate(camcorderProfile.videoFrameRate);
				
//				p.setPreviewSize(width/2, height/2);
//				p.setPreviewSize(176, 144);
//				p.setPreviewFormat(ImageFormat.NV21);
//				p.setPreviewFormat(	camera.getParameters().getSupportedPreviewFormats().get(0) );
				p.setPreviewSize(DESIRED_WIDTH,DESIRED_HEIGHT);
//				p.setPreviewSize(540, 540);
				p.setPreviewFrameRate(30);
				p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
				
				previewHeight = p.getPreviewSize().height;
				previewWidth = p.getPreviewSize().width;
				
				cropStartX = 0 + (previewWidth - dim)/2;
				cropStartY = 0 + (previewHeight - dim)/2;
				cropEndX = dim + (previewWidth - dim)/2;
				cropEndY = dim + (previewHeight - dim)/2;

				camera.setParameters(p);
						
				Log.d("XXX", "parameters Width " + p.getPreviewSize().width);
				Log.d("XXX", "parameters Height " + p.getPreviewSize().height);
				Log.d("XXX", "parameters framerate " + p.getPreviewFrameRate());
				
				Log.v(LOGTAG,"Setting up preview callback buffer");
				bufferSize = (p.getPreviewSize().width * p.getPreviewSize().height * ImageFormat.getBitsPerPixel(p.getPreviewFormat()) / 8);
				Log.d("ZZZ", "bufferSize = " + bufferSize);		
				
				bos = null;
		        try {
		        	//RandomAccessFile memoryMappedFile = new RandomAccessFile("memoryBytes", "rw");
		    		//mbb = memoryMappedFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, bufferSize*FRAMES);
		    		
		    		bFile = new File(Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_buffer.buf");
		    		bFile.delete();
		    		bFile.createNewFile();
					fos = new FileOutputStream(bFile);
		    		//raf = new RandomAccessFile(bFile, "rw");
		    	    //raf.seek(raf.length());
					fc = fos.getChannel();//raf.getChannel();
					//mbb = fc.map(FileChannel.MapMode.READ_WRITE, 0, bufferSize*FRAMES);
					//fc.close();
					
					
					//bos = new BufferedOutputStream(fos);
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

				
//				for (int i = 0; i< frames.length; i++)
//					frames[i] = new byte[bufferSize];
				
//				previewCallbackBuffer = new byte[(camcorderProfile.videoFrameWidth * camcorderProfile.videoFrameHeight * 
//													ImageFormat.getBitsPerPixel(p.getPreviewFormat()) / 8)];
//				previewCallbackBuffer = new byte[(p.getPreviewSize().width * p.getPreviewSize().height * 
//						ImageFormat.getBitsPerPixel(p.getPreviewFormat()))];
				Log.v(LOGTAG,"setPreviewCallbackWithBuffer");
//				camera.addCallbackBuffer(previewCallbackBuffer);
				
				camera.setPreviewCallbackWithBuffer(this);
				for (int i = 0; i < 5; i++) {
	                byte [] cameraBuffer = new byte[bufferSize];
	                camera.addCallbackBuffer(cameraBuffer);
	            }
				
				camera.setPreviewDisplay(holder);

				
				//camera.setPreviewCallback(this);
				
				Log.v(LOGTAG,"startPreview");
				camera.startPreview();
				previewRunning = true;
			}
			catch (IOException e) {
				Log.e(LOGTAG,e.getMessage());
				e.printStackTrace();
			}	
		}
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.v(LOGTAG, "surfaceDestroyed");
		if (recording) {
			recording = false;

//			try {
//				bos.flush();
//				bos.close();
//			} catch (IOException e) {
//				e.printStackTrace();
//			}
		}

		previewRunning = false;
		camera.release();
		finish();
	}

	public void onPreviewFrame(byte[] b, Camera c) {
//		Log.v(LOGTAG,"onPreviewFrame");
		if (fileCount >= FRAMES && recording){
			recording = false;
			/*
			try {
				bos.flush();
				bos.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			*/
			/*
			mbb.force();
			mbb = null;
			try {
				raf.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			raf = null;
			*/
			try {
				fc.force(false);
				fc.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			//TODO: clear preview buffers
			processVideo();
			return;
		}
			
		if (recording) {
			

			// Assuming ImageFormat.NV21
//			if (p.getPreviewFormat() == ImageFormat.NV21) {
				Log.v(LOGTAG,"Started Writing Frame");
				
				try {
//					frames[fileCount] = b.clone();
					
//					System.arraycopy(b, 0, frames[fileCount], 0, b.length);
					
//					formattedFileCount = fileCountFormatter.format(fileCount);  
//					bFile = new File(Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_" + formattedFileCount + ".buf");
			fileCount++;
									
//					fos = new FileOutputStream(bFile);
//					bos = new BufferedOutputStream(fos);
					
//					Log.d("XXX", "camera preview size " + 
//							camera.getParameters().getPreviewSize().height 
//							+ " " +
//							camera.getParameters().getPreviewSize().width 
//							+ " " +
//							camera.getParameters().getPreviewFrameRate()
//							);
					

					
//					Log.d("XXX", "fame dimensions " + previewWidth + " " + + previewHeight);
	
					
//					
//					YuvImage im = new YuvImage(b, camera.getParameters().getPreviewFormat(), previewWidth, previewHeight, null);
//					Rect r = new Rect(cropStartX, cropStartY, cropEndX, cropEndY);
//					im.compressToJpeg(r, 100, bos);
//					
					
//			        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//			        im.compressToJpeg(r, 100, baos);

					//bos.write(baos.toByteArray());

//					Bitmap x = BitmapFactory.decodeByteArray(b, 0, b.length);
//					x.compress(Bitmap.CompressFormat.PNG, 85, fos);

					//bos.write(b);
					//mbb.put(b);
			fc.write(ByteBuffer.wrap(b));

//					bos.flush();
//					bos.close();
//					fos.flush();
//					fos.close();
				} catch (Exception e) {
					Log.e(LOGTAG, "CRASHED!");
					e.printStackTrace();
				}
				
				Log.v(LOGTAG,"Finished Writing Frame");
//			} else {
//				Log.v(LOGTAG,"NOT THE RIGHT FORMAT");
//			}
			

		}
		camera.addCallbackBuffer(b);

	}
	
    @Override
    public void onConfigurationChanged(Configuration conf) 
    {
        super.onConfigurationChanged(conf);
    }	
	    
	private class ProcessVideo extends AsyncTask<Void, Integer, Void> {
		
		@Override
		protected void onPreExecute() {
			// TODO Auto-generated method stub
			super.onPreExecute();
			Toast.makeText(getApplicationContext(), "Started Video Processing", Toast.LENGTH_LONG).show();
		}
		
		@Override
		protected void onPostExecute(Void result) {
			// TODO Auto-generated method stub
			Toast.makeText(getApplicationContext(), "Finished Video Processing", Toast.LENGTH_LONG).show();
			super.onPostExecute(result);
		}
		
		@Override
		protected Void doInBackground(Void... params) {
			
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			

			File bFile;
			File jpegFile;
			String formattedFileCount;
			
			File inputFile = new File(Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_buffer.buf");
			try {
				FileInputStream fis = new FileInputStream(inputFile);
				BufferedInputStream bis = new BufferedInputStream(fis);
				byte[] buffer = new byte[bufferSize];
				int byteOffset = 0;
				int i = 0;
				int bread = 0;
				
				String basePath = Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_";
				int previewFormat = camera.getParameters().getPreviewFormat();
				Rect r = new Rect(0,0,previewWidth,previewHeight);//cropStartX, cropStartY, cropEndX, cropEndY);
				while((bread = bis.read(buffer, 0, bufferSize))>0){
					Log.v(LOGTAG,"Read "+bread + " bytes from buffer file ("+i+"/"+fileCount+")");
					formattedFileCount = fileCountFormatter2.format(i); 
					jpegFile = new File(basePath + formattedFileCount + ".jpg");
					fos = new FileOutputStream(jpegFile);
//					bos = new BufferedOutputStream(fos);
					YuvImage im = new YuvImage(buffer, previewFormat, previewWidth, previewHeight, null);
					
					im.compressToJpeg(r, 90, fos);
					fos.flush();
					fos.close();
					
					//byteOffset+=bufferSize;
					i++;

				}
			} catch (FileNotFoundException e2) {
				// TODO Auto-generated catch block
				e2.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
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
	        	
	        	Log.d("XXX", "ffmpeg framerate " + p.getPreviewFrameRate());
	        	
	        	String videoFile = Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/video.mp4";
	        	
	        	new File(videoFile).delete();
	        	
				//String[] args2 = {"/data/data/com.mobvcasting.ffmpegcommandlinetest/ffmpeg", "-y", "-i", "/data/data/com.mobvcasting.ffmpegcommandlinetest/", "-vcodec", "copy", "-acodec", "copy", "-f", "flv", "rtmp://192.168.43.176/live/thestream"};
				String[] ffmpegCommand = {"/data/data/com.mobvcasting.mjpegffmpeg/ffmpeg", "-r", ""+p.getPreviewFrameRate(), 
										"-b", "" + 200000*8, "-vcodec", "mjpeg", 
										"-i", Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/frame_%05d.jpg", 
										videoFile};
				
				
				
				ffmpegProcess = new ProcessBuilder(ffmpegCommand).redirectErrorStream(true).start();         	
				
				OutputStream ffmpegOutStream = ffmpegProcess.getOutputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getInputStream()));

				String line;
				
				Log.v(LOGTAG,"***Starting FFMPEG***");
				while ((line = reader.readLine()) != null)
				{
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
		
		
	     protected void onPostExecute(Void... result) {
	    	 Toast toast = Toast.makeText(MJPEGFFMPEGTest.this, "Done Processing Video", Toast.LENGTH_LONG);
	    	 toast.show();
	     }
	}
	
	
	public void setSupportedFormats(){
        List<Integer> supportedPreviewFormats = p.getSupportedPreviewFormats();
        Iterator<Integer> supportedPreviewFormatsIterator = supportedPreviewFormats.iterator();
        while(supportedPreviewFormatsIterator.hasNext()){
            Integer previewFormat =supportedPreviewFormatsIterator.next();
            // 16 ~ NV16 ~ YCbCr
            // 17 ~ NV21 ~ YCbCr ~ DEFAULT
            // 4  ~ RGB_565
            // 256~ JPEG
            // 20 ~ YUY2 ~ YcbCr ...
            // 842094169 ~ YV12 ~ 4:2:0 YCrCb comprised of WXH Y plane, W/2xH/2 Cr & Cb. see documentation
            Log.v("CameraTest","Supported preview format:"+previewFormat);
            if (true || previewFormat == ImageFormat.YUY2) {
                p.setPreviewFormat(previewFormat);
                Log.v("CameraTest","SETTING FANCY YV12 FORMAT");
                return;
            }
        }
		
		
	}
 }