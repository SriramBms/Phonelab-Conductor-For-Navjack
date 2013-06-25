package edu.buffalo.cse.phonelab.harness.lib.examples;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

/* Periodically generate dummy files to upload */
public class TestFileUploader extends Activity
{
    private final String TAG = this.getClass().getSimpleName();
    private final String ACTION_UPLOAD = "edu.buffalo.cse.phonelab.harness.lib.tasks.FileUploaderTask.UPLOAD";
    private final String ACTION_UPLOAD_COMPLETED = "edu.buffalo.cse.phonelab.harness.lib.tasks.FileUploaderTask.UPLOAD_COMPLETED";
    private final String BUNDLE_KEY_PACKAGENAME = "edu.buffalo.cse.phonelab.harness.lib.tasks.FileUploaderTask.PACKAGENAME";
    private final String BUNDLE_KEY_PATH_LIST = "edu.buffalo.cse.phonelab.harness.lib.tasks.FileUploaderTask.PATH_LIST";
    private int counter;
    /* maxium file number generated during an internval */
    private final int MAX_FILE_NUM = 5;
    private int intervalMS = 60*1000;


    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        counter = 0;

        getApplicationContext().registerReceiver(new UploadCompletedReceiver(), new IntentFilter(ACTION_UPLOAD_COMPLETED));

        (new Thread(new WorkerThread(getApplicationContext()))).start();
    }

    @SuppressLint("WorldReadableFiles")
	class WorkerThread implements Runnable {
        private final String FILE_NAME_BASE = "TEST_FILE_UPLOAD.";
        private Context context;

        public WorkerThread(Context context) {
            this.context = context;
        }

        private void collectAndSend() {
            ArrayList<String> fileList = new ArrayList<String>();
            String[] files = context.fileList();
            for (String fileName : files) {
                fileList.add(context.getFileStreamPath(fileName).getAbsolutePath());
            }
            Intent uploadIntent = new Intent(ACTION_UPLOAD);
            uploadIntent.putExtra(BUNDLE_KEY_PACKAGENAME, TestFileUploader.this.getClass().getName());
            uploadIntent.putStringArrayListExtra(BUNDLE_KEY_PATH_LIST, fileList);
            context.sendBroadcast(uploadIntent);

            Log.i(TAG, "Send " + fileList.size() + " files to upload.");
        }

        @Override
        public void run() {

            while (true) {
                int fileNumber = (int)(Math.random()*MAX_FILE_NUM) % MAX_FILE_NUM;
                if (fileNumber == 0) {
                    continue;
                }

                try {
                    for (int i = 0; i < fileNumber; i++) {
                        String fileName = FILE_NAME_BASE + counter;
                        /* this will create a file under /data/data/$YOUR_PACKAGE_NAME/files/$FILE_NAME for you to write */
                        FileOutputStream fileOutputStream = context.openFileOutput(fileName, Context.MODE_WORLD_READABLE);
                        fileOutputStream.write(new String("Hello world!").getBytes());
                        fileOutputStream.close();
                        Log.v(TAG, "Generated file " + fileName);
                        counter++;
                    }

                    collectAndSend();

                    Thread.sleep(intervalMS);
                }
                catch (Exception e) {
                    Log.e(TAG, "Error generating upload files " + e);
                }
            }

        }

    }

    /* Delete uploaded files */
    class UploadCompletedReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(ACTION_UPLOAD_COMPLETED)) {
                Log.e(TAG, "Unknow intent " + intent);
                return;
            }

            Bundle bundle = intent.getExtras();
            if (bundle == null) {
                Log.e(TAG, "No bundle found.");
                return;
            }
            if (!bundle.containsKey(BUNDLE_KEY_PATH_LIST)) {
                Log.e(TAG, "No file list associated.");
                return;
            }

            Set<String> currentFile = new HashSet<String>();
            Collections.addAll(currentFile, context.fileList());

            ArrayList<String> uploadedFileList = bundle.getStringArrayList(BUNDLE_KEY_PATH_LIST);
            for (String path : uploadedFileList) {
                String fileName = (new File(path)).getName();
                if (!currentFile.contains(fileName)) {
                    continue;
                }
                if (context.deleteFile(fileName)) {
                    Log.i(TAG, "Deleted uploaded file " + path);
                }
                else {
                    Log.e(TAG, "Fail to delete uploaded file " + path);
                }
            }

        }
    }

}
