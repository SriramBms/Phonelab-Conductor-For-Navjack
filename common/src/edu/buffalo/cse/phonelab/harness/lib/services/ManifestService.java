package edu.buffalo.cse.phonelab.harness.lib.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import edu.buffalo.cse.phonelab.harness.lib.interfaces.ManifestClient;
import edu.buffalo.cse.phonelab.harness.lib.tasks.FileUploaderTask;
import edu.buffalo.cse.phonelab.harness.lib.tasks.LauncherTask;
import edu.buffalo.cse.phonelab.harness.lib.tasks.LogcatTask;
import edu.buffalo.cse.phonelab.harness.lib.tasks.ManifestTask;
import edu.buffalo.cse.phonelab.harness.lib.tasks.UsageTrackingTask;

public class ManifestService extends Service {
    public String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();
        
    ManifestTask manifestTask;
    LauncherTask launcherTask;
    LogcatTask logcatTask;
    FileUploaderTask fileUploaderTask;

    private boolean started = false;
    
    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (started == true) {
            Log.v(TAG, "Not restarting manifest service");
            return START_STICKY;
        }
        
        Log.v(TAG, "-------------- STARTING MANIFEST SERVICE ---------------");
        super.onStartCommand(intent, flags, startId);
        
        if (manifestTask == null) {
        	try {
                manifestTask = new ManifestTask(getApplicationContext());
            } catch (Exception e) {
                Log.e(TAG, "Error creating manifest task : " + e);
                return START_STICKY;
            }
        }
        
        if (logcatTask == null) {
        	try {
        		logcatTask = new LogcatTask(getApplicationContext());
        	} catch (Exception e) {
        		Log.e(TAG, "Error creating logcat task : " + e);
        		return START_STICKY;
        	}
        }
        
        if (launcherTask == null) {
        	try {
        		launcherTask = new LauncherTask(getApplicationContext());
        	} catch (Exception e) {
        		Log.e(TAG, "Error creating launcher task : " + e);
        		return START_STICKY;
        	}
        }
        
        if (fileUploaderTask == null) {
        	try {
        		fileUploaderTask = new FileUploaderTask(getApplicationContext());
        	} catch (Exception e) {
        		Log.e(TAG, "Error creating file uploader task : " + e);
        		return START_STICKY;
        	}
        }
        
        manifestTask.start();
        logcatTask.start();
        launcherTask.start();
        fileUploaderTask.start();
        
        registerReceiver(stopReceiver, stopIntentFilter);
        started = true;
        return START_STICKY;
    }
    
    @Override
    public synchronized void onDestroy() {
        unregisterReceiver(stopReceiver);
        
        if (launcherTask != null) {
        	launcherTask.stop();
        }
        launcherTask = null;
        
        if (logcatTask != null) {
        	logcatTask.stop();
        }
        logcatTask = null;
        
        if (fileUploaderTask != null) {
        	fileUploaderTask.stop();
        }
        fileUploaderTask = null;

        if (manifestTask != null) {
        	manifestTask.stop();
        }
        manifestTask = null;

        super.onDestroy();
        started = false;
        Log.v(TAG, "-------------- STOPPED MANIFEST SERVICE ---------------");
    }
    
    private final String stopIntentName = this.getClass().getName() + ".StopService";
    private IntentFilter stopIntentFilter = new IntentFilter(stopIntentName);
    private BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            Log.v(TAG, "Received stop command.");
            ManifestService.this.onDestroy();
        }
    };
    
    public final IBinder manifestBinder = new ManifestBinder();
    public class ManifestBinder extends Binder {
        public ManifestService getService() {
            return ManifestService.this;
        }
    }
    @Override
    public IBinder onBind(Intent arg0) {
        return manifestBinder;
    }
    public synchronized void receiveManifestUpdates(ManifestClient receiver, String key) {
        if ((started == true) &&
            (manifestTask != null)) {
            manifestTask.receiveManifestUpdates(receiver, key);
        } else {
            Log.w(TAG, "Manifest update registration received when shut down.");
        }
    }
    
    public synchronized void discardManifestUpdates(String key) {
        if ((started == true) &&
            (manifestTask != null)) {
            manifestTask.discardManifestUpdates(key);
        } else {
            Log.w(TAG, "Manifest deregistration received when shut down.");
        }
    }
}
