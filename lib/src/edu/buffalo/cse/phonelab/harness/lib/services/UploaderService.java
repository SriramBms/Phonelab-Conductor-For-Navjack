package edu.buffalo.cse.phonelab.harness.lib.services;

import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderClient;
import edu.buffalo.cse.phonelab.harness.lib.tasks.UploaderTask;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class UploaderService extends Service {
	public String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();
	
	public static final Integer PRIORITY_LOGCAT = 0;
	public static final Integer PRIORITY_HIGH = 1;
	public static final Integer PRIORITY_LOW = 2;
	
	private boolean started = false;
	
	private final IBinder uploaderBinder = new LoggerBinder();
	public class LoggerBinder extends Binder {
		public UploaderService getService() {
			return UploaderService.this;
		}
	}
	
	@Override
	public IBinder onBind(Intent arg0) {
		return uploaderBinder;
	}
		
	public void registerLogger(UploaderClient uploader, Integer priority) {
		if ((started == true) &&
			(uploaderTask != null)) {
			uploaderTask.registerLogger(uploader, priority);
		} else {
			Log.w(TAG, "Logger tried to register while shut down.");
		}
	}
	
	public void unregisterLogger(UploaderClient uploader) {
		if ((started == true) &&
			(uploaderTask != null)) {
			uploaderTask.unregisterLogger(uploader);
		} else {
			Log.w(TAG, "Logger tried to unregister while shut down.");
		}
	}
	
	private UploaderTask uploaderTask;
	
	@Override
	public synchronized int onStartCommand(Intent intent, int flags, int startId) {
		if (started == true) {
			Log.v(TAG, "Not restarting running service.");
			return START_STICKY;
		}
		
		started = true;
		super.onStartCommand(intent, flags, startId);
		
		try {
			uploaderTask = new UploaderTask(getApplicationContext());
			uploaderTask.start();
		} catch (Exception e) {
			started = false;
			Log.e(TAG, e.toString());
		}
		
		return START_STICKY;
	}
	
	@Override
	public synchronized void onDestroy() {
		if (started == false) {
			Log.v(TAG, "Not stopping stopped service.");
			return;
		}
		super.onDestroy();
		uploaderTask.stop();
		uploaderTask = null;
		started = false;
	}
}