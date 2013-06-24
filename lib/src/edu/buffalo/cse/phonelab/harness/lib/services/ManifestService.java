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
    UsageTrackingTask usageTrackingTask;
    FileUploaderTask fileUploaderTask;

    
    @Override
    public void onCreate() {
        try {
            manifestTask = new ManifestTask(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Error creating manifest task : " + e);
            return;
        }
        logcatTask = new LogcatTask(getApplicationContext());
        launcherTask = new LauncherTask(getApplicationContext());
        usageTrackingTask = new UsageTrackingTask(getApplicationContext());
        fileUploaderTask = new FileUploaderTask(getApplicationContext());
    }

    private boolean started = false;
    
    @Override
    public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (started == true) {
            Log.v(TAG, "Not restarting manifest service");
            return START_STICKY;
        }
        Log.v(TAG, "-------------- STARTING MANIFEST SERVICE ---------------");
        started = true;
        super.onStartCommand(intent, flags, startId);
        
        manifestTask.start();
        logcatTask.start();
        launcherTask.start();
        usageTrackingTask.start();
        fileUploaderTask.start();
        
        registerReceiver(stopReceiver, stopIntentFilter);       
        return START_STICKY;
    }
    
    @Override
    public synchronized void onDestroy() {
        unregisterReceiver(stopReceiver);
        
        launcherTask.stop();
        launcherTask = null;
        logcatTask.stop();
        logcatTask = null;
        
        Intent intent = new Intent(UploaderService.class.getName());
        getApplicationContext().stopService(intent);
        
        usageTrackingTask.stop();
        usageTrackingTask = null;

        fileUploaderTask.stop();
        fileUploaderTask = null;

        manifestTask.stop();
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
