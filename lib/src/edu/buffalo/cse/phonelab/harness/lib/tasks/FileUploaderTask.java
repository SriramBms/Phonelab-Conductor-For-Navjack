package edu.buffalo.cse.phonelab.harness.lib.tasks;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;

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

    private Set<String> filesToUpload;
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

        filesToUpload = Collections.synchronizedSet(new LinkedHashSet<String>());
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        /* try to recover upload file list from shared preference */
        recoverUploadFileList();

        /* register for uploader service */
        context.bindService(new Intent(context, UploaderService.class), uploaderServiceConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter(ACTION_UPLOAD);
        fileUploaderReceiver = new FileUploaderReceiver();
        context.registerReceiver(fileUploaderReceiver, intentFilter);

        Log.i(TAG, "Upload intent action is " + ACTION_UPLOAD);
        Log.i(TAG, "Upload complete intent action is " + ACTION_UPLOAD_COMPLETED);
        Log.i(TAG, "Upload intent bundle key for path list is " + BUNDLE_KEY_PATH_LIST);
    }


    @Override
    protected void check(FileUploaderParameters parameters) {
        updateUploadFiles();
        Log.v(TAG, filesToUpload.size() + " files to be uploaded");
    }

    /*
     * return true if file exists, and is a regular file (not directory), and is readable 
     * returns false otherwise
     * */
    private boolean isFileAvailable(String path) {
        File file = new File(path);
        if (!file.exists()) {
            Log.e(TAG, "File " + file.getAbsolutePath() + " doesn't exist.");
            return false;
        }
        if (!file.isFile()) {
            Log.e(TAG, "File " + file.getAbsolutePath() + " is not a regular file.");
            return false;
        }
        if (!file.canRead()) {
            Log.e(TAG, "File " + file.getAbsolutePath() + " is not readable.");
            return false;
        }
        return true;
    }

    private void updateUploadFiles() {
        if (filesToUpload.size() == 0) {
            recoverUploadFileList();
        }

        state.bytesAvailable = 0;
        state.filesAvailable = 0;
        Iterator<String> iterator = filesToUpload.iterator();

        while (iterator.hasNext()) {
            String path = iterator.next();
            if (isFileAvailable(path)) {
                state.bytesAvailable += (new File(path)).length();
                state.filesAvailable++;
            }
            else {
                iterator.remove();
            }
        }
        persistUploadFileList();
    }

    private void recoverUploadFileList() {
        if (sharedPreferences == null) {
            Log.w(TAG, "sharedPreferences not initiated.");
            return;
        }

        Set<String> files = sharedPreferences.getStringSet(PREFS_KEY, null);
        if (files != null) {
            filesToUpload.addAll(files);
        }
    }

    private void persistUploadFileList() {
        if (sharedPreferences == null) {
            Log.w(TAG, "sharedPreferences not initiated.");
            return;
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();

        editor.putStringSet(PREFS_KEY, filesToUpload);
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

            filesToUpload.remove(path);
            persistUploadFileList();
        }
    }

    @Override
    public boolean hasNext() {
        return !filesToUpload.isEmpty();
    }

    @Override
    public UploaderFileDescription next() {
        Iterator<String> iterator = filesToUpload.iterator();
        while (iterator.hasNext()) {
            File file = new File(iterator.next());
            /* last check */
            if (isFileAvailable(file.getAbsolutePath())) {
                return new UploaderFileDescription(file.getAbsolutePath(), file.getAbsolutePath().replace("/data/data/", "").replace("files/", ""), file.length());
            }
            else {
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

                if (bundle.containsKey(BUNDLE_KEY_PATH_LIST)) {
                    ArrayList<String> pathList = bundle.getStringArrayList(BUNDLE_KEY_PATH_LIST);
                    Iterator<String> iterator = pathList.iterator();
                    while (iterator.hasNext()) {
                        String path = iterator.next();
                        if (isFileAvailable(path) && !filesToUpload.contains(path)) {
                            Log.v(TAG, "Adding file " + path);
                            filesToUpload.add(path);
                        }
                    }
                    persistUploadFileList();
                }
                else {
                    Log.e(TAG, "Intent bundle doesn't contain valid key.");
                }
            }
            else {
                Log.e(TAG, "Unknown intent " + intent.toString());
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
