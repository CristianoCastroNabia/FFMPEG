package com.mobvcasting.mjpegffmpeg;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.mobvcasting.mjpegffmpeg.MJPEGCamera.MJPEGCameraProcessMovieProgressListener;
import com.mobvcasting.mjpegffmpeg.MJPEGCamera.MJPEGCameraRecordProgressListener;

public class MJPEGFFMPEGTest extends Activity implements OnClickListener, MJPEGCameraProcessMovieProgressListener, MJPEGCameraRecordProgressListener { 
	
	public static final String LOGTAG = "MJPEG_FFMPEG";

	
	private int FRAMES = 300,DESIRED_WIDTH = 640,DESIRED_HEIGHT = 480;
	
	boolean recording = false;
	boolean previewRunning = false;	
	
	int width;
	int height;
	int bufferSize;
	
	Button recordButton;
	
	Camera.Parameters p;

	MJPEGCamera mjpegCam = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);

		setContentView(R.layout.main);
		
		recordButton = (Button) this.findViewById(R.id.RecordButton);
		recordButton.setOnClickListener(this);

		SurfaceView cameraView = (SurfaceView) findViewById(R.id.CameraView);
		
		mjpegCam = new MJPEGCamera(this, cameraView, this, this);


		cameraView.setClickable(true);
		cameraView.setOnClickListener(this);		

    }
    
    
    public void processVideo(){
    	String outFile = Environment.getExternalStorageDirectory().getPath() + "/com.mobvcasting.mjpegffmpeg/video.mp4";
    	mjpegCam.processMovieToFile(outFile, null);
    }

	public void onClick(View v) {
		Log.d("XXX", "click!");
		if (recording) 
		{
			recording = false;	
			Log.v(LOGTAG, "Recording Stopped");
			mjpegCam.endRecording();
			// Convert to video
			processVideo();
		} 
		else
		{
			recording = true;
			mjpegCam.startRecording();
			Log.v(LOGTAG, "Recording Started");
		}
	}

    @Override
    public void onConfigurationChanged(Configuration conf) 
    {
        super.onConfigurationChanged(conf);
    }	

	@Override
	public void recordingTimeChanged(int frame, int maxFrames, float seconds) {
		// TODO Auto-generated method stub
		Log.v(LOGTAG, "Recorded "+seconds+"s ("+frame+"/"+maxFrames+")");
	}


	@Override
	public void maxRecordTimeReached() {
		// TODO Auto-generated method stub
		recording = false;	
		Log.v(LOGTAG, "Recording Stopped due to max time reached");
		// Convert to video
		processVideo();
	}

	@Override
	public void rawImageToJPEGProgressUpdate(float percentage, int frame,
			int maxFrames) {
		// TODO Auto-generated method stub
		Log.v(LOGTAG, "Processing raw to jpeg "+percentage+"% ("+frame+"/"+maxFrames+")");
	}


	@Override
	public void movieEncodeProgressUpdate(float percentage, int frame,
			int maxFrames) {
		// TODO Auto-generated method stub
		Log.v(LOGTAG, "Encoding movie "+percentage+"% ("+frame+"/"+maxFrames+")");

	}


	@Override
	public void startedProcessingMovie() {
		// TODO Auto-generated method stub
		Log.v(LOGTAG, "Started processing movie");
		Toast.makeText(this, "Started Video Processing", Toast.LENGTH_LONG).show();
	}


	@Override
	public void finishedProcessingMovie(String outFile) {
		// TODO Auto-generated method stub
		Log.v(LOGTAG, "Finished processing movie to "+outFile);
		Toast.makeText(this, "Finished Video Processing", Toast.LENGTH_LONG).show();
	}
	
	

 }