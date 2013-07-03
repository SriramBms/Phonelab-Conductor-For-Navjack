package edu.buffalo.cse.phonelab.harness.lib.periodictask;

import java.io.StringWriter;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.ManifestClient;
import edu.buffalo.cse.phonelab.harness.lib.services.ManifestService;
import edu.buffalo.cse.phonelab.harness.lib.services.ManifestService.ManifestBinder;

public abstract class PeriodicTask<P extends PeriodicParameters, S extends PeriodicState> implements ManifestClient {
	
	public final String PARAMETER_PREFERENCES_NAME = parameterClass().getSimpleName();
	public static final String PARAMETER_PREFERENCES_KEY = "parameters";
	public static final String MANIFEST_SERVICE = "edu.buffalo.cse.phonelab.services.ManifestService";
	
	public String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();
	
	public abstract P newParameters();
	public abstract P newParameters(P parameters);
	public abstract Class<P> parameterClass();
	public P parameters;
	protected P initialParameters;
	
	protected final ReentrantLock parameterLock = new ReentrantLock();
	
	public abstract S newState();
	public S state = newState();
	protected final ReentrantLock stateLock = new ReentrantLock();
	
	private PendingIntent pendingIntent;
	protected Context context;
	protected String key;
	
	protected IntentFilter intentFilter;
	private boolean started;
	
	public PeriodicTask(Context context, String key) {
		super();
		this.context = context;
		this.key = key;
		this.parameters = null;

		this.started = false;
		
		this.intentFilter = new IntentFilter(CHECK_INTENT_NAME);
		
		SharedPreferences sharedPreferences = context.getSharedPreferences(PARAMETER_PREFERENCES_NAME, 0);
		String savedParameterString = sharedPreferences.getString(PARAMETER_PREFERENCES_KEY, null);
		
		if (savedParameterString != null) {
			Log.v(TAG, "Attempting to retrieve parameters from shared preferences.");
			initialParameters = deserializeParameters(savedParameterString);
		}
		if (initialParameters != null) {
			Log.v(TAG, "Recovered saved parameters.");
		} else {
			Log.w(TAG, "Unable to recover saved parameters. Reinitializing.");
			initialParameters = newParameters();
		}
		Log.v(TAG, "Created task.");
	}
	
	public synchronized void start() {
		Log.v(TAG, "Starting periodic task.");
			
		context.registerReceiver(broadcastReceiver, intentFilter);
		
		pendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(CHECK_INTENT_NAME), PendingIntent.FLAG_UPDATE_CURRENT);
		
		Intent manifestServiceIntent = new Intent(context, ManifestService.class);
		context.bindService(manifestServiceIntent, manifestServiceConnection, Context.BIND_AUTO_CREATE);
				
		updateParameters(initialParameters);

		started = true;
		state.started = new Date();
		Log.v(TAG, "Started task.");	
	}
	
	public synchronized void stop() {
		
		Log.v(TAG, "Stopping alarm.");
		stopAlarm();
		
		Log.v(TAG, "Deregistering from manifest updates.");
		manifestService.discardManifestUpdates(PeriodicTask.this.key);
		context.unbindService(manifestServiceConnection);
		context.unregisterReceiver(broadcastReceiver);
		
		Log.v(TAG, "Shutting down background task executor.");
		checkExecutor.shutdown();
		try {
			checkExecutor.awaitTermination(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			Log.e(TAG, "Exception while shutting down check task executor:" + e);
		}
		
		saveParameters();
		
		Log.v(TAG, "Task stopped.");
	}
	
	public final synchronized boolean addAction(String add) {
		Log.v(TAG, "Trying to add action " + add);
		if (intentFilter.hasAction(add) == true) {
			Log.w(TAG, "Action already in filter. Ignoring.");
			return true;
		}
		if (started == true) {
			Log.v(TAG, "Unregistered receiver");
			context.unregisterReceiver(broadcastReceiver);
		}
		intentFilter.addAction(add);
		if (started == true) {
			Log.v(TAG, "Registering new receiver");
			context.registerReceiver(broadcastReceiver, intentFilter);
		}
		Log.v(TAG, "Added action " + add);
		return true;
	}
	
	public final synchronized boolean removeAction(String remove) {
		Log.v(TAG, "Trying to remove action " + remove);
		if (intentFilter.hasAction(remove) == false) {
			Log.w(TAG, "Action not in filter. Ignoring.");
			return true;
		}
		IntentFilter newIntentFilter = new IntentFilter();
		Iterator<String> actionsIterator = intentFilter.actionsIterator();
		while (actionsIterator.hasNext()) {
			String action = actionsIterator.next();
			if (action.equals(remove) == false) {
				Log.v(TAG, "Retaining " + action + " in filter.");
				newIntentFilter.addAction(action);
			} else {
				Log.v(TAG, "Removing " + action + " from filter.");
			}
		}
		
		Log.v(TAG, "Old intent filter has length " + intentFilter.countActions());
		Log.v(TAG, "New intent filter has length " + newIntentFilter.countActions());
		
		if (started == true) {
			Log.v(TAG, "Unregistered receiver");
			context.unregisterReceiver(broadcastReceiver);
		}
		intentFilter = newIntentFilter;
		if (started == true) {
			Log.v(TAG, "Registering new receiver");
			context.registerReceiver(broadcastReceiver, intentFilter);
		}
		Log.v(TAG, "Remove action " + remove);
		return true;
	}
	
	ManifestService manifestService;
	private ServiceConnection manifestServiceConnection = new ServiceConnection() {	
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			Log.v(TAG, "Connecting to ManifestService.");
			ManifestBinder binder = (ManifestBinder) service;
			manifestService = binder.getService();
			manifestService.receiveManifestUpdates(PeriodicTask.this, PeriodicTask.this.key);
		}
		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			Log.w(TAG, "Manifest service disconnected. Shutting down.");
			PeriodicTask.this.stop();
		}
	};
	
	@Override
	public boolean parametersUpdated(String manifestString) {
		P newParameters;
		Log.v(TAG, "Manifest update: " + manifestString);
		newParameters = deserializeParameters(manifestString);
		if (newParameters == null) {
			return false;
		} else {
			return updateParameters(newParameters);
		}
	}
	
	private P deserializeParameters(String parameterString) {
		try {
			return new Persister().read(parameterClass(), parameterString);
		} catch (Exception e) {
			Log.e(TAG, "Could not deserialize string " + parameterString + ": " + e.toString());
			return null;
		}
	}
	
	private String serializeParameters(P parameters) {
		StringWriter stringWriter = new StringWriter();
		Serializer serializer = new Persister();
		try {
			serializer.write(parameters, stringWriter);
		} catch (Exception e) {
			Log.e(TAG, "Problem serializing parameters: " + e);
			return null;
		}
		String xmlState = stringWriter.toString();
		return xmlState;
	}
	
	@Override
	public String getState() {
		StringWriter stringWriter = new StringWriter();
		Serializer serializer = new Persister();
		synchronized (parameterLock) {
			state.parameters = newParameters(parameters);
		}
		synchronized (stateLock) {
			try {
				serializer.write(state, stringWriter);
			} catch (Exception e) {
				Log.e(TAG, "Problem serializing state: " + e);
				return null;
			}
		}
		String xmlState = stringWriter.toString();
		Log.v(TAG, "Serialized state: " + xmlState);
		return xmlState;
	}
	
	protected boolean startPeriodicTimer = true;
	public boolean updateParameters(P newParameters) {
		
		if (newParameters.equals(parameters)) {
			Log.w(TAG, "Parameters have not changed.");
			return true;
		}
		
		acquireLock(context);
		if (parameters != null) {
			Log.v(TAG, "Old parameters: " + parameters.toString());
		}
		
		synchronized(parameterLock) {
			if (startPeriodicTimer == true) {
				stopAlarm();
			}
			parameters = newParameters;
			scheduleCheckTask();
			if (startPeriodicTimer == true) {
				startPeriodic(parameters.checkInterval);
			}
			saveParameters();
		}
		
		synchronized (stateLock) {
			state.parameterUpdate = new Date();
		}
		
		Log.v(TAG, "New parameters: " + newParameters.toString());
		releaseLock();
		return true;
	}
	
	protected void saveParameters() {
		Log.v(TAG, "Saving parameters as shared preference.");
		synchronized (parameterLock) {
			SharedPreferences.Editor sharedPreferencesEditor = context.getSharedPreferences(PARAMETER_PREFERENCES_NAME, 0).edit();
			sharedPreferencesEditor.putString(PARAMETER_PREFERENCES_KEY, serializeParameters(parameters));
			sharedPreferencesEditor.commit();
		}
	}
	
	public final String CHECK_INTENT_NAME = this.getClass().getName() + ".Check";
	public BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context arg0, Intent arg1) {
			Log.v(TAG, "Alarm fired.");
			scheduleCheckTask();
		}
	};
	
	protected abstract void check(P parameters);
	private ExecutorService checkExecutor = Executors.newSingleThreadExecutor();
	
	public class Check implements Runnable {
		private P taskParameters;
		public Check(P parameters) {
			super();
			this.taskParameters = parameters;
		}
		@Override
		public void run() {
			Log.v(TAG, "Starting check task.");
			acquireLock(PeriodicTask.this.context);
			check(this.taskParameters);
			releaseLock();
			Log.v(TAG, "Check task finished.");
		}
	}
	
	public void scheduleCheckTask() {
		synchronized (parameterLock) {
			checkExecutor.execute(new Check(parameters));
		}
	}
	
	synchronized public final void startPeriodic(long interval) {
		long intervalMS = interval * 1000;
		stopAlarm();
		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		manager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + intervalMS,
				intervalMS, pendingIntent);
	}
	synchronized public final void startOneShot(long interval) {
		long intervalMS = interval * 1000;
		stopAlarm();
		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		manager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + intervalMS, pendingIntent);
	}
	synchronized public final void stopAlarm() {
		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		manager.cancel(pendingIntent);
	}
	
	private static WakeLock lock = null;
	synchronized public final static void acquireLock(Context context) {
		if (lock == null) {
			PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
			lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PeriodicTask.class.getName());
			lock.setReferenceCounted(true);
		}
		lock.acquire();
	}
	synchronized public final static void releaseLock() {
		if (lock != null && lock.isHeld()) {
			lock.release();
		}
	}
}
