package edu.buffalo.cse.phonelab.harness.lib.services;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderClient;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderFileDescription;
import edu.buffalo.cse.phonelab.harness.lib.services.UploaderService.LoggerBinder;
import edu.buffalo.cse.phonelab.harness.lib.util.Util;

/* We use a separate FileUploaderService, rather than extending current 
 * UploaderService for three reasons:
 *
 * - UploaderService by design is highly coupled with other components within
 *   collector. For example, you have to register as an UploaderClient when you
 *   bind to it, and you have to handle various things such as prepare, next,
 *   complete, etc. Thus is it's not suitable for outside apps which just want to
 *   upload files using as simple way as possible. 
 *
 * - No need to expose inner interfaces, such as UploaderClient, to public. Apps
 *   just bind to FileUploaderService, send the file's path. And we'll take care
 *   the rest.
 *
 * - There are some extra things to take care of when uploading other app's
 *   files, such as security check, hand-shake to server to avoid redundant
 *   uploads, etc. Extending (rather complex) UploaderSerivce could probably a
 *   pain.
 */

public class FileUploaderService extends Service implements UploaderClient {
    private final String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();

    private final String PREFS_NAME = this.getClass().getName() + ".FILES_TO_UPLOAD";
    private final String PREFS_KEY = this.getClass().getName() + ".PATH_LIST";

    private final int MSG_UPLOAD_FILE = 1;

    private Set<UploaderFileDescription> uploadFiles = Collections.synchronizedSet(new HashSet<UploaderFileDescription>());
    private SharedPreferences sharedPreferences;
    private UploaderService uploaderService;

    private boolean started = false;

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
	
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        recoverUploadFileList();
        
        bindService(new Intent(this, UploaderService.class), uploaderServiceConnection, Context.BIND_AUTO_CREATE);
        
        started = true;

        return START_STICKY;
    }
        
    private int updateUploadFiles() {
    	
        int bytesAvailable = 0;
        boolean listChanged = false;
        Iterator<UploaderFileDescription> iterator = uploadFiles.iterator();

        while (iterator.hasNext()) {
            UploaderFileDescription uploadFile = iterator.next();
            if (uploadFile.exists()) {
                bytesAvailable += uploadFile.len;
            } else {
                iterator.remove();
                listChanged = true;
            }
        }

        if (listChanged) {
            persistUploadFileList();
        }

        return bytesAvailable;
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
        return updateUploadFiles();
    }

    @Override
    public void complete(UploaderFileDescription uploaderFileDescription,
            boolean success) {
        if (success) {
            File file = new File(uploaderFileDescription.src);

            /* first try to delete the file, if fail then truncate sent files */
            if (!file.delete() && file.canWrite()) {
                try {
                    RandomAccessFile raf = new RandomAccessFile(file, "rw");
                    raf.setLength(0);
                    raf.close();
                }
                catch (Exception e) {
                    Log.w(TAG, "Failed to truncate sent file " + file.getName() + ": " + e);
                }
            }
            uploadFiles.remove(uploaderFileDescription);
            /* TODO
             * This is kind of tedious that we have to write the whole upload
             * file list every time the list changes.
             */
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
                    /* TODO
                     * need a more mature protocal
                     */
                    ArrayList<String> content = (ArrayList<String>)msg.obj;
                    String packageName = content.get(0);
                    String path = content.get(1);
                    String hash = content.get(2);

                    try {
                        if (!hash.equals(Util.hashFile(new File(path)))) {
                            Log.w(TAG, "Hash doesn't match of file " + path + ", package name " + packageName);
                            return;
                        }
                    }
                    catch (Exception e) {
                        Log.e(TAG, "Fail to check hash of file " + path + ", package name " + packageName);
                        return;
                    }

                    try {
                        uploadFiles.add(new UploaderFileDescription(path, (new File(path)).getName(), packageName));
                        persistUploadFileList();
                    } catch (Exception e) {
                        Log.w(TAG, "Couldn't create upload file from path " + path + ": " + e);
                    }
            }
        }
    }

    final Messenger messenger = new Messenger(new MessageHandler());

    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }
}
