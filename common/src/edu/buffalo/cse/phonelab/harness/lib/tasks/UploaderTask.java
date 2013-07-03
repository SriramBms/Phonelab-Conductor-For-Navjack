package edu.buffalo.cse.phonelab.harness.lib.tasks;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.BitmapDrawable;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.R;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderClient;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderFileDescription;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicParameters;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicState;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicTask;
import edu.buffalo.cse.phonelab.harness.lib.util.Util;

public class UploaderTask extends PeriodicTask<UploaderParameters, UploaderState> {
	
	private final Integer UPLOAD_NOTIFICATION_ID = 1;
	
	public final IBinder uploaderBinder = new LoggerBinder();
	public class LoggerBinder extends Binder {
		public UploaderTask getService() {
			return UploaderTask.this;
		}
	}
	
	public UploaderTask(Context context) {
		super(context, "UploaderService");
		startPeriodicTimer = false;
		addAction(Intent.ACTION_POWER_CONNECTED);
		addAction(Intent.ACTION_POWER_DISCONNECTED);
		addAction(ConnectivityManager.CONNECTIVITY_ACTION);
	}

	private boolean taskRunning = false;
	
	@Override
	public void start() {
		taskRunning = true;
		super.start();
	}
	
	@Override
	public void stop() {
		synchronized (parameterLock) {
			disableUpload(parameters);
			taskRunning = false;
		}
		super.stop();
	}

	private boolean uploadEnabled = false;
	
	@Override
	public void check(UploaderParameters parameters) {
		if (canUpload(parameters) == true) {
			Log.v(TAG, "Enabling upload.");
			enableUpload(parameters);
		} else {
			Log.v(TAG, "Disabling upload.");
			disableUpload(parameters);
		}
	}
	
	private synchronized void enableUpload(UploaderParameters parameters) {
		if (uploadEnabled == false) {
			startPeriodic(parameters.checkInterval);
		}
		if (scheduledUploadTask != null && scheduledUploadTask.isCancelled()) {
			Log.v(TAG, "Waiting on cancelling upload task.");
			try {
				scheduledUploadTask.get();
			} catch (Exception e) { };
		}
		if (scheduledUploadTask == null || scheduledUploadTask.getStatus() == AsyncTask.Status.FINISHED) {
			Log.v(TAG, "Starting upload task.");
			scheduledUploadTask = new Upload().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		} else {
			Log.v(TAG, "Not restarting running upload.");
		}
		uploadEnabled = true;
	}
	
	private synchronized void disableUpload(UploaderParameters parameters) {
		if (uploadEnabled == true) {
			stopAlarm();
		}
		if (scheduledUploadTask != null && scheduledUploadTask.getStatus() != AsyncTask.Status.FINISHED) {
			Log.v(TAG, "Cancelling running upload task.");
			scheduledUploadTask.cancel(true);
		}
		uploadEnabled = false;
	}
	
	private AsyncTask<Void, Void, Void> scheduledUploadTask = null;
	private Integer uploadedBytes = 0;
	
	public class Upload extends AsyncTask<Void, Void, Void> {
		
		UploaderParameters currentParameters;
		Integer localFailureCount;
		
		public Upload() {
			this.localFailureCount = 0;
			synchronized (parameterLock) {
				currentParameters = parameters;
			}
		}
		
		@Override
		protected Void doInBackground(Void...voids) {
			
			UploaderTask.acquireLock(UploaderTask.this.context);
			
			Integer totalBytes = 0;
			
			NotificationManager notificationManager = (NotificationManager) UploaderTask.this.context.getSystemService(Context.NOTIFICATION_SERVICE);
			
			UploaderIterable uploaderIterable = new UploaderIterable(UploaderTask.this);
			try {
				Notification.Builder builder = new Notification.Builder(UploaderTask.this.context);
				builder.setContentTitle("PhoneLab");
				builder.setContentText("Uploading experiment data.");
				builder.setSmallIcon(R.drawable.status_icon_small);
				builder.setLargeIcon(((BitmapDrawable) UploaderTask.this.context.getResources().getDrawable(R.drawable.ic_launcher)).getBitmap());
				builder.setOngoing(true);
				builder.setProgress((int) uploaderIterable.totalBytes, 0, false);
				builder.setTicker("Uploading experiment data.");
				
				for (UploaderFileDescription uploaderFileDescription : uploaderIterable) {
					
					Log.v(TAG, "Starting upload.");
					
					synchronized (parameterLock) {
						currentParameters = parameters;
					}
					if (canUpload(currentParameters) == false) {
						Log.v(TAG, "Completing upload early due to parameter change.");
						break;
					}
					if (this.isCancelled()) {
						Log.v(TAG, "Task cancelled. Exiting.");
						break;
					}
					URL url;
					try {
						url = new URL(currentParameters.loggerURL + 
									  Util.getVersionName(context) + 
									  "/" + Util.getDeviceID(UploaderTask.this.context) +
									  "/" + uploaderFileDescription.packagename +
									  "/" + uploaderFileDescription.filename);
						
					} catch (Exception e) {
						Log.e(TAG, "Unable to construct URL: " + e);
						break;
					}
					
					Log.v(TAG, "Uploading to " + url + " with parameters " + currentParameters);
					
					builder.setProgress((int) uploaderIterable.totalBytes, totalBytes, false);
					notificationManager.notify(UPLOAD_NOTIFICATION_ID, builder.build());
					
					HttpURLConnection connection;
					try {	
						BufferedInputStream src = new BufferedInputStream(new FileInputStream(new File(uploaderFileDescription.src)));
						connection = Util.upload(url, src, true,
												 currentParameters.chunkedTransferMode, currentParameters.chunkSizeKB, currentParameters.connectionTimeoutSec);
						connection.getInputStream();
						connection.disconnect();
						if (connection.getResponseCode() != 200) {
							Log.e(TAG, "Upload failed: " + connection.getResponseCode());
							throw new Exception("Failure error code");
						} else {
							UploaderTask.this.uploadedBytes += (int) uploaderFileDescription.len;
							totalBytes += (int) uploaderFileDescription.len;
							Log.v(TAG, "Uploaded " + uploaderFileDescription.len + " bytes successfully. Continuing.");
							uploaderFileDescription.uploader.complete(uploaderFileDescription, true);
						}
					} catch (Exception e) {
						Log.w(TAG, "Upload failed: " + e + ". Continuing with next file.");
						uploaderFileDescription.uploader.complete(uploaderFileDescription, false);
						if (localFailureCount++ > currentParameters.failureCount) {
							break;
						}
					}
				}
			} catch (NoSuchElementException e) {
				Log.e(TAG, "Exception during upload loop: " + e);
			} finally {
				notificationManager.cancel(1);
				Log.v(TAG, "Completed upload loop.");
			}
			UploaderTask.releaseLock();
			return null;
		}
		
		@Override
		protected void onPostExecute(Void result) {
			if (localFailureCount > currentParameters.failureCount) {
				Log.i(TAG, "Not attempting to reschedule upload task due to failures.");
				synchronized (UploaderTask.this.stateLock) {
					UploaderTask.this.state.lastUpload = new Date();
					UploaderTask.this.state.uploadedBytes = UploaderTask.this.uploadedBytes;
					UploaderTask.this.uploadedBytes = 0;
				}
			} else {
				UploaderIterable uploaderIterable = new UploaderIterable(UploaderTask.this);
				if (uploaderIterable.iterator().hasNext()) {
					Log.v(TAG, "Immediately rescheduling upload task due to waiting streams.");
					scheduledUploadTask = new Upload().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
				} else {
					UploaderTask.this.state.lastUpload = new Date();
					UploaderTask.this.state.uploadedBytes = UploaderTask.this.uploadedBytes;
					UploaderTask.this.uploadedBytes = 0;
				}
			}
			Log.v(TAG, "Completed upload.");
			super.onPostExecute(result);
		}
	}
	
	private boolean canUpload(UploaderParameters parameters) {
		
		synchronized (stateLock) {
			
			if (this.taskRunning == false) {
				return false;
			}
			
			try {
				if (((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo().isConnected() == false) {	
					Log.v(TAG, "Network not connected.");
					state.network = false;
				}
				state.network = true;
			} catch (NullPointerException e) {
				Log.v(TAG, "No network connection.");
				state.network = false;
			}
			
			Log.v(TAG, "Network state is " + state.network + ". Checking for plug.");
			
			// 28 Sep 2012 : GWA : Do we have a plug?
			
			IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
			Intent batteryStatus = context.registerReceiver(null, filter);
			int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
			boolean charging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
			
			int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
			boolean chargingUSB = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
			boolean chargingPlug = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;
			
			Log.v(TAG, parameters.powerpolicy + " " + charging + " " + chargingUSB + " " + chargingPlug);
			
			if (parameters.powerpolicy.equals("acplug") && (charging == true) && (chargingPlug == true)) {
				state.power = true;
			} else if (parameters.powerpolicy.equals("usbplug") && (charging == true) && (chargingUSB == true || chargingPlug == true)) {
				state.power = true;
			} else {
				state.power = false;
			}
			Log.v(TAG, "Power state is " + state.power + ".");
		}
		Log.v(TAG, "Power and network combine to give us " + (state.network && state.power));
		
		if (state.power == true) {
			addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		} else {
			removeAction(ConnectivityManager.CONNECTIVITY_ACTION);
		}
		return state.network && state.power;
	}
	
	@Override
	public UploaderParameters newParameters() {
		return new UploaderParameters();
	}
	
	@Override
	public UploaderParameters newParameters(UploaderParameters parameters) {
		return new UploaderParameters(parameters);
	}
	
	@Override
	public Class<UploaderParameters> parameterClass() {
		return UploaderParameters.class;
	}

	private HashMap<UploaderRecord, Integer> uploaderHash = new HashMap<UploaderRecord, Integer>();
	
	public synchronized void registerLogger(UploaderClient uploader, Integer priority) {
		if (uploaderHash.containsKey(uploader) == true) {
			Log.e(TAG, "Duplicate uploader. Ignoring.");
			return;
		}
		UploaderRecord newUploader = new UploaderRecord(uploader, priority);
		uploaderHash.put(newUploader, priority);
		synchronized (stateLock) {
			state.orderedUploaders = getOrderedUploaders();
		}
		scheduleCheckTask();
		Log.v(TAG, "Registered " + uploader.getClass().getSimpleName() + " with LoggerService with priority " + priority + ".");
	}
	
	public synchronized void unregisterLogger(UploaderClient uploader) {
		assert uploaderHash.containsValue(uploader) == true : uploader;
		uploaderHash.remove(uploader);
		synchronized (stateLock) {
			state.orderedUploaders = getOrderedUploaders();
		}
		Log.v(TAG, "Unregistered " + uploader.getClass().getSimpleName() + " with LoggerService.");
	}

	private ArrayList<UploaderRecord> getOrderedUploaders() {
		ArrayList<UploaderRecord> orderedUploaders = new ArrayList<UploaderRecord>(uploaderHash.keySet());
		Collections.sort(orderedUploaders, new Comparator<UploaderRecord>() {
			@Override
			public int compare(UploaderRecord u1, UploaderRecord u2) {
				return u1.priority.compareTo(u2.priority);
			}
		});
		return orderedUploaders;
	}
	
	private class UploaderIterable implements Iterable<UploaderFileDescription> {
		
		private ArrayList<UploaderRecord> orderedUploaders;
		public long totalBytes;
		
		public UploaderIterable(UploaderTask uploaderService) {
			synchronized (UploaderTask.this) {
				orderedUploaders = UploaderTask.this.state.orderedUploaders;
			}
			totalBytes = 0;
			for (UploaderRecord uploader : orderedUploaders) {
				uploader.uploaderClient.prepare();
				totalBytes += uploader.uploaderClient.bytesAvailable();
			}
		}
		
		@Override
		public Iterator<UploaderFileDescription> iterator() {
			return new UploaderIterator(orderedUploaders);
		}
		
		public class UploaderIterator implements Iterator<UploaderFileDescription> {
			private Integer position;
			private ArrayList<UploaderRecord> orderedUploaders;
			
			public UploaderIterator(ArrayList<UploaderRecord> orderedUploaders) {
				this.position = 0;
				this.orderedUploaders = orderedUploaders;
			}
				
			@Override
			public boolean hasNext() {
				if (position == orderedUploaders.size()) {
					return false;
				}
				for ( ; position < orderedUploaders.size(); position++) {
					if (orderedUploaders.get(position).uploaderClient.hasNext() == true) {
						return true;
					}
				}
				return false;
			}

			@Override
			public UploaderFileDescription next() {
				if (hasNext() == false) {
					throw new NoSuchElementException();
				}	
				UploaderFileDescription next = orderedUploaders.get(position).uploaderClient.next();	
				if (next == null) {
					throw new NoSuchElementException();
				}
				next.uploader = orderedUploaders.get(position).uploaderClient;
				
				if (next.packagename == null) {
					next.packagename = next.uploader.getClass().getName();
				}
				return next;
			}

			@Override
			public void remove() { 
				throw new UnsupportedOperationException();
			}
		}
	}

	@Override
	public UploaderState newState() {
		return new UploaderState();
	}
}

@Root(name="UploaderClient")
class UploaderRecord {
	@Element
	String name;
	
	@Element
	Integer priority;
	
	public UploaderClient uploaderClient;

	public UploaderRecord(UploaderClient uploaderClient, Integer priority) {
		this.uploaderClient = uploaderClient;
		this.priority = priority;
		this.name = uploaderClient.getClass().getName();
	}
}

@Root(name="LoggerService")
class UploaderParameters extends PeriodicParameters {
	
	@Element
	public Integer connectionTimeoutSec;
	
	@Element
	public String loggerURL;
	
	@Element
	public String powerpolicy;
	
	@Element
	public Integer failureCount;
	
	@Element
	public Boolean chunkedTransferMode;
	
	@Element
	public Integer chunkSizeKB;
	
	public UploaderParameters() {
		super();
		checkInterval = AlarmManager.INTERVAL_FIFTEEN_MINUTES / 1000L;
		loggerURL = "http://backend.phone-lab.org/uploader/";
		powerpolicy = "usbplug";
		connectionTimeoutSec = 10;
		failureCount = 8;
		chunkedTransferMode = false;
		chunkSizeKB = 32;
	}
	
	public UploaderParameters(UploaderParameters parameters) {
		super(parameters);
		loggerURL = parameters.loggerURL;
		powerpolicy = parameters.powerpolicy;
		connectionTimeoutSec = parameters.connectionTimeoutSec;
		failureCount = parameters.failureCount;
		chunkedTransferMode = parameters.chunkedTransferMode;
		chunkSizeKB = parameters.chunkSizeKB;
	}
	
	@Override
	public String toString() {
		return "UploaderParameters [connectionTimeoutSec="
				+ connectionTimeoutSec + ", loggerURL=" + loggerURL
				+ ", powerpolicy=" + powerpolicy + ", failureCount="
				+ failureCount + ", chunkedTransferMode=" + chunkedTransferMode
				+ ", chunkSizeKB=" + chunkSizeKB + ", checkInterval="
				+ checkInterval + "]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result
				+ ((chunkSizeKB == null) ? 0 : chunkSizeKB.hashCode());
		result = prime
				* result
				+ ((chunkedTransferMode == null) ? 0 : chunkedTransferMode
						.hashCode());
		result = prime
				* result
				+ ((connectionTimeoutSec == null) ? 0 : connectionTimeoutSec
						.hashCode());
		result = prime * result
				+ ((failureCount == null) ? 0 : failureCount.hashCode());
		result = prime * result
				+ ((loggerURL == null) ? 0 : loggerURL.hashCode());
		result = prime * result
				+ ((powerpolicy == null) ? 0 : powerpolicy.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		UploaderParameters other = (UploaderParameters) obj;
		if (chunkSizeKB == null) {
			if (other.chunkSizeKB != null)
				return false;
		} else if (!chunkSizeKB.equals(other.chunkSizeKB))
			return false;
		if (chunkedTransferMode == null) {
			if (other.chunkedTransferMode != null)
				return false;
		} else if (!chunkedTransferMode.equals(other.chunkedTransferMode))
			return false;
		if (connectionTimeoutSec == null) {
			if (other.connectionTimeoutSec != null)
				return false;
		} else if (!connectionTimeoutSec.equals(other.connectionTimeoutSec))
			return false;
		if (failureCount == null) {
			if (other.failureCount != null)
				return false;
		} else if (!failureCount.equals(other.failureCount))
			return false;
		if (loggerURL == null) {
			if (other.loggerURL != null)
				return false;
		} else if (!loggerURL.equals(other.loggerURL))
			return false;
		if (powerpolicy == null) {
			if (other.powerpolicy != null)
				return false;
		} else if (!powerpolicy.equals(other.powerpolicy))
			return false;
		return true;
	}
}

@Root(name="UploaderService")
class UploaderState extends PeriodicState {
	@Element
	Date lastUpload;
	
	@Element
	Integer uploadedBytes;
	
	@ElementList
	ArrayList<UploaderRecord> orderedUploaders;
	
	@Element
	Boolean network;
	
	@Element
	Boolean power;
	
	public UploaderState() {
		super();
		lastUpload = new Date(0L);
		uploadedBytes = 0;
		orderedUploaders = new ArrayList<UploaderRecord>();
		network = false;
		power = false;
	}
}