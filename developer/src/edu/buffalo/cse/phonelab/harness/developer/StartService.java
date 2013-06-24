package edu.buffalo.cse.phonelab.harness.developer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.services.ManifestService;

public class StartService extends Service {
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.v("PhoneLabServices", "Starting ManifestService from StartService.");
		startService(new Intent(getApplicationContext(), ManifestService.class));
		this.stopSelf();
		return START_NOT_STICKY;
	}
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}