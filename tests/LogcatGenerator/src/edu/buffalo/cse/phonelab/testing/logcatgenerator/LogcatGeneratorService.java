package edu.buffalo.cse.phonelab.testing.logcatgenerator;

import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import edu.buffalo.cse.phonelab.lib.PeriodicTask;

public class LogcatGeneratorService extends Service {
	
	private final String TAG = "PhoneLabTesting-LogcatGeneratorService";
	private boolean started = false;
	
	@Override
	public synchronized int onStartCommand(Intent intent, int flags, int startId) {
		if (started == true) {
			return START_STICKY;
		}
		started = true;
		
		LogcatGeneratorTask logcatGeneratorTask = new LogcatGeneratorTask(getApplicationContext(), TAG, AlarmManager.INTERVAL_FIFTEEN_MINUTES);
		logcatGeneratorTask.start();
		
		return START_STICKY;
	}
	
	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}
}

class LogcatGeneratorTask extends PeriodicTask {
	private final String COUNT_PREFERENCES_KEY = "count";
	
	private Long count;
	
	public LogcatGeneratorTask(Context context, String logTag, Long interval) {
		super(context, logTag, interval);
		SharedPreferences sharedPreferences = context.getSharedPreferences(TAG, 0);
		count = sharedPreferences.getLong(COUNT_PREFERENCES_KEY, 0);
	}

	@Override
	protected synchronized void check() {
		try {
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("count", count);
			jsonObject.put("version", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
			Log.v(TAG, jsonObject.toString());
		} catch (Exception e) {
			Log.e(TAG, e.toString());
			return;
		}
		count += 1;
		SharedPreferences.Editor sharedPreferencesEditor = context.getSharedPreferences(TAG, 0).edit();
		sharedPreferencesEditor.putLong(COUNT_PREFERENCES_KEY, count);
		sharedPreferencesEditor.commit();
	}
}