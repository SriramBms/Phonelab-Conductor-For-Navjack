package edu.buffalo.cse.phonelab.harness.lib.services;

import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderClient;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderFileDescription;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicParameters;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicState;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicTask;
import edu.buffalo.cse.phonelab.harness.lib.services.UploaderService;
import edu.buffalo.cse.phonelab.harness.lib.services.UploaderService.LoggerBinder;

public class FileUploaderService extends Service implements UploaderClient {
    private final String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();

    private final String PREFS_NAME = this.getClass().getName() + ".FILES_TO_UPLOAD";
    private final String PREFS_KEY = this.getClass().getName() + ".PATH_LIST";

    private final int MSG_UPLOAD_FILE = 1;

    private Set<UploaderFileDescription> uploadFiles;
    private SharedPreferences sharedPreferences;
    private Context context;
    private FileUploaderReceiver fileUploaderReceiver;
    private UploaderService uploaderService;

    private started = false;

    private ServiceConnection uploaderServiceConnection = new ServiceConnection() {
        
        @Override
        public void onServiceConnected(ComponentName className, IBinder iBinder) {
            Log.v(TAG, "Connected to uploader service.");
            LoggerBinder binder = (LoggerBinder) iBinder;
            uploaderService = binder.getService();
            uploaderService.registerLogger(FileUploaderService.this, UploaderService.PRIORITY_LOW);
        }
        
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.w(TAG, "Disconnected to uploader service.");
            persistUploadFileList();
        }
    };
    
	@Override
	public synchronized int onStartCommand(Intent intent, int flags, int startId) {
		if (started == true) {
			Log.v(TAG, "Not restarting running service.");
			return START_STICKY;
		}
		
        Log.v(TAG, "-------------- STARTING FILE UPLOADER SERVICE ---------------");
		super.onStartCommand(intent, flags, startId);
	
        uploadFiles = Collections.synchronizedSet(new HashSet<UploaderFileDescription>());
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        recoverUploadFileList();
        
        bindService(new Intent(this, UploaderService.class), uploaderServiceConnection, Context.BIND_AUTO_CREATE);
        
        started = true;
    }
        
    private void updateUploadFiles() {
    	
        state.bytesAvailable = 0;
        state.filesAvailable = 0;
        Iterator<UploaderFileDescription> iterator = uploadFiles.iterator();

        while (iterator.hasNext()) {
            UploaderFileDescription uploadFile = iterator.next();
            if (uploadFile.exists() == true) {
                state.bytesAvailable += uploadFile.len;
                state.filesAvailable++;
            } else {
                iterator.remove();
            }
        }
        persistUploadFileList();
    }

    private synchronized void recoverUploadFileList() {
        if (sharedPreferences == null) {
            Log.w(TAG, "sharedPreferences not initiated.");
            return;
        }
        
        for (String serializedUpload : sharedPreferences.getStringSet(PREFS_KEY, new HashSet<String>())) {
        	UploaderFileDescription uploadFile;
        	try {
        		uploadFile = new Persister().read(UploaderFileDescription.class, serializedUpload);
        	} catch (Exception e) {
        		Log.e(TAG, "Problem deserializing upload file from string " + serializedUpload + ": " + e);
        		continue;
        	}
        	uploadFiles.add(uploadFile);
        }
        Log.v(TAG, "Successfully deserialized upload files.");
    }

    private synchronized void persistUploadFileList() {
        if (sharedPreferences == null) {
            Log.w(TAG, "sharedPreferences not initiated.");
            return;
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();
        Set<String> serializedUploadFiles = new HashSet<String>();
        
        for (UploaderFileDescription uploadFile : uploadFiles) {
        	StringWriter stringWriter = new StringWriter();
        	Serializer serializer = new Persister();
        	try {
        		serializer.write(uploadFile, stringWriter);
        	} catch (Exception e) {
        		Log.e(TAG, "Problem serializing upload file: " + e);
        		continue;
        	}
        	String serializedUpload = stringWriter.toString();
        	serializedUploadFiles.add(serializedUpload);
        }
        
        editor.putStringSet(PREFS_KEY, serializedUploadFiles);
        Log.v(TAG, "Successfully serialized upload files.");
        editor.commit();
    }

    @Override
    public long bytesAvailable() {
        return state.bytesAvailable;
    }

    @Override
    public void complete(UploaderFileDescription uploaderFileDescription,
            boolean success) {
        if (success) {
            File file = new File(uploaderFileDescription.src);
            /* truncate sent files */
            if (file.canWrite()) {
                try {
                    RandomAccessFile raf = new RandomAccessFile(file, "rw");
                    raf.setLength(0);
                    raf.close();
                }
                catch (Exception e) {
                    Log.w("Failed to truncate sent file " + file.getName() + ": " + e);
                }
            }
            uploadFiles.remove(path);
            persistUploadFileList();
        }
    }

    @Override
    public boolean hasNext() {
        return !uploadFiles.isEmpty();
    }

    @Override
    public UploaderFileDescription next() {
        Iterator<UploaderFileDescription> iterator = uploadFiles.iterator();
        while (iterator.hasNext()) {
        	UploaderFileDescription uploadFile = iterator.next();
            if (uploadFile.exists() == true) {
            	return uploadFile;
            } else {
            	iterator.remove();
            }
        }
        return null;
    }

    @Override
    public void prepare() {
        updateUploadFiles();
    }

    class MessageHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPLOAD_FILE: 
                    ArrayList<String> content = (ArrayList<String>)msg.obj;
                    String packageName = content.get(0);
                    ArrayList<String> paths = content.subList(1, content.size());

                    for (String path : paths) {
                        UploaderFileDescription uploadFile;
                        try {
                            /* TODO 
                             * may need to further check if pathName in
                             * path
                             */
                            uploadFile = new UploaderFileDescription(path, (new File(path)).getName(), packageName);
                        } catch (Exception e) {
                            Log.w(TAG, "Couldn't create upload file from path " + path + ": " + e);
                            continue;
                        }
                        uploadFiles.add(uploadFile);
                    }
                    
                    persistUploadFileList();
            }
        }
    }

    final Messenger messenger = new Messenger(new MessageHandler());

    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }
}
