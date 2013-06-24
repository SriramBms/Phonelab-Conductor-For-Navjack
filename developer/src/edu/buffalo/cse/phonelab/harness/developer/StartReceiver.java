package edu.buffalo.cse.phonelab.harness.developer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.services.ManifestService;

public class StartReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.v("PhoneLabServices", "Starting ManifestService from StartReceiver.");
		context.startService(new Intent(context, ManifestService.class));
	}
}