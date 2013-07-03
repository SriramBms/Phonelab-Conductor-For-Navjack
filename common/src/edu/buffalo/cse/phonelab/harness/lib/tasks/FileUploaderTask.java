package edu.buffalo.cse.phonelab.harness.lib.tasks;

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

public class FileUploaderTask extends PeriodicTask<FileUploaderParameters, FileUploaderState> implements UploaderClient {
    private final String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();

    private final String PREFS_NAME = this.getClass().getName() + ".FILES_TO_UPLOAD";
    private final String PREFS_KEY = this.getClass().getName() + ".PATH_LIST";

    public final String ACTION_UPLOAD = this.getClass().getName() + ".UPLOAD";
    public final String ACTION_UPLOAD_COMPLETED = this.getClass().getName() + ".UPLOAD_COMPLETED";
    
    public final String BUNDLE_KEY_PATH_LIST = this.getClass().getName() + ".PATH_LIST";
    public final String BUNDLE_KEY_PACKAGENAME = this.getClass().getName() + ".PACKAGENAME";

    private Set<UploaderFileDescription> uploadFiles;
    private SharedPreferences sharedPreferences;
    private Context context;
    private FileUploaderReceiver fileUploaderReceiver;
    private UploaderService uploaderService;

    private ServiceConnection uploaderServiceConnection = new ServiceConnection() {
        
        @Override
        public void onServiceConnected(ComponentName className, IBinder iBinder) {
            Log.v(TAG, "Connecting to uploader service.");
            LoggerBinder binder = (LoggerBinder) iBinder;
            uploaderService = binder.getService();
            uploaderService.registerLogger(FileUploaderTask.this, UploaderService.PRIORITY_LOW);
        }
        
        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.w(TAG, "Uploader service disconnected.");
            persistUploadFileList();
        }
    };
    
    public FileUploaderTask(Context context) {
        super(context, "FileUploaderService");
        this.context = context;

        uploadFiles = Collections.synchronizedSet(new HashSet<UploaderFileDescription>());
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        recoverUploadFileList();
        
        context.bindService(new Intent(context, UploaderService.class), uploaderServiceConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter(ACTION_UPLOAD);
        fileUploaderReceiver = new FileUploaderReceiver();
        context.registerReceiver(fileUploaderReceiver, intentFilter);
    }
        
    @Override
    protected void check(FileUploaderParameters parameters) {
        updateUploadFiles();
        Log.v(TAG, uploadFiles.size() + " files to be uploaded");
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
    public FileUploaderParameters newParameters() {
        return new FileUploaderParameters();
    }

    @Override
    public FileUploaderParameters newParameters(FileUploaderParameters parameters) {
        return new FileUploaderParameters(parameters);
    }

    @Override
    public FileUploaderState newState() {
        return new FileUploaderState();
    }

    @Override
    public Class<FileUploaderParameters> parameterClass() {
        return FileUploaderParameters.class;
    }

    @Override
    public long bytesAvailable() {
        return state.bytesAvailable;
    }

    @Override
    public void complete(UploaderFileDescription uploaderFileDescription,
            boolean success) {
        if (success) {
            String path = uploaderFileDescription.src;
            Intent intent = new Intent(ACTION_UPLOAD_COMPLETED);
            ArrayList<String> completedFileList = new ArrayList<String>();
            completedFileList.add(path);
            intent.putStringArrayListExtra(BUNDLE_KEY_PATH_LIST, completedFileList);
            Log.v(TAG, "Broadcasting upload complete intent for file " + path);
            context.sendBroadcast(intent);

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

    @Override
    public synchronized void stop() {
        persistUploadFileList();
        context.unregisterReceiver(fileUploaderReceiver);
        super.stop();
    }

    @Override
    public String getState() {
        updateUploadFiles();
        return super.getState();
    }

    class FileUploaderReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
        	
            if (intent.getAction().equals(ACTION_UPLOAD)) {
                Bundle bundle = intent.getExtras();
                String packagename;
                ArrayList<String> paths;
                
                if (bundle.containsKey(BUNDLE_KEY_PACKAGENAME)) {
                	packagename = bundle.getString(BUNDLE_KEY_PACKAGENAME);
                } else {
                	Log.e(TAG, "Bundle doesn't contain packagename.");
                	return;
                }
                
                if (bundle.containsKey(BUNDLE_KEY_PATH_LIST)) {
                    paths = bundle.getStringArrayList(BUNDLE_KEY_PATH_LIST);
                } else {
                	Log.e(TAG, "Bundle doesn't contain file list.");
                	return;
                }
                
                for (String path : paths) {
                	UploaderFileDescription uploadFile;
                	try {
                		uploadFile = new UploaderFileDescription(path, new File(path).getName(), packagename);
                	} catch (Exception e) {
                		Log.w(TAG, "Couldn't create upload file from path " + path + ": " + e);
                		continue;
                	}
                	uploadFiles.add(uploadFile);
                }
                
                persistUploadFileList();
                return;
            }
        }
    }
}

@Root(name="FileUploaderService")
class FileUploaderParameters extends PeriodicParameters {

    /**
     *
     */
    public FileUploaderParameters() {
        super();

    }

    public FileUploaderParameters(FileUploaderParameters parameters) {
        super(parameters);

    }

    public String toString() {
        return this.getClass().getSimpleName() + "[]";
    }

    public int hashCode() {
        // final int prime = 31;
        int result = super.hashCode();

        return result;
    }

    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        // FileUploaderParameters other = (FileUploaderParameters) obj;


        return true;
    }
}

@Root(name="FileUploaderService")
class FileUploaderState extends PeriodicState {
    @Element
    public long bytesAvailable;

    @Element
    public int filesAvailable;
    /**
     *
     */
    public FileUploaderState() {
        super();
        bytesAvailable = 0L;
        filesAvailable = 0;
    }
}
