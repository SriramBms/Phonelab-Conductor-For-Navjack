package edu.buffalo.cse.phonelab.harness.lib.tasks;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicParameters;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicState;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicTask;

public class LauncherTask extends PeriodicTask<LauncherParameters, LauncherState> {
	
	private static final Integer MIN_INTERVAL_MS = 10000;
	
	public synchronized void check(LauncherParameters parameters) {
		
		Date now = new Date();
		HashSet<String> runningServices = new HashSet<String>();
		ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);	
		for (RunningServiceInfo runningServiceInfo : activityManager.getRunningServices(Integer.MAX_VALUE)) {
			runningServices.add(runningServiceInfo.service.getPackageName());
		}
		
		synchronized(stateLock) {
			state.runningServices.clear();
			for (ScheduledService startingService : parameters.currentRunningServices(now)) {
				if (runningServices.contains(startingService.packageName)) {
					state.runningServices.add(startingService);
					Log.v(TAG, "PhoneLab service " + startingService + " is running.");
				} else {
					Log.v(TAG, "Starting PhoneLab service " + startingService);
					try {
						Intent startingIntent = new Intent(startingService.intentName);
						if (context.startService(startingIntent) == null) {
							throw new Exception("startService() failed.");
						}
					} catch (Exception e) {
						Log.e(TAG, "Unable to start " + startingService + ": " + e);
					}
				}
			}
			
			for (ScheduledService stoppingService : parameters.currentStoppedServices(now)) {
				if (!(runningServices.contains(stoppingService.packageName))) {
					Log.v(TAG, "PhoneLab service " + stoppingService + " is not running.");
				} else {
					state.runningServices.add(stoppingService);
					Log.v(TAG, "Stopping PhoneLab service " + stoppingService);
					try {
						Intent stoppingIntent = new Intent(stoppingService.intentName);
						context.stopService(stoppingIntent);
					} catch (Exception e) {
						Log.e(TAG, "Unable to stop " + stoppingService + ": " + e);
					}
				}
			}
			
			for (ScheduledService checkingService : parameters.currentCheckingServices(now)) {
				if (!(runningServices.contains(checkingService.packageName))) {
					Log.v(TAG, "PhoneLab service " + checkingService + " is not running.");
				} else {
					state.runningServices.add(checkingService);
					Log.v(TAG, "PhoneLab service " + checkingService + " is running.");
				}
			}
		
			Long nextInterval = Math.min(parameters.nextEvent(now).getTime() - now.getTime(), parameters.checkInterval * 1000);
			nextInterval = Math.max(nextInterval, MIN_INTERVAL_MS);
			Log.v(TAG, "Setting timer for " + nextInterval + " ms.");
			state.nextInterval = nextInterval;
			startOneShot(nextInterval);
		}
	}
	
	public BroadcastReceiver packageReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context arg0, Intent arg1) {
			Log.v(TAG, "Package intent fired.");
			scheduleCheckTask();
		}
	};
	
	IntentFilter packageIntentFilter;
	
	public LauncherTask(Context context) {
		super(context, "LauncherService");
		this.startPeriodicTimer = false;
		
		packageIntentFilter = new IntentFilter();
		packageIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
		packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
		packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
		packageIntentFilter.addDataScheme("package");
	}
	
	@Override
	public synchronized void start () {
		this.context.registerReceiver(packageReceiver, packageIntentFilter);
		super.start();
	}
	
	@Override
	public void stop() {
		this.context.unregisterReceiver(packageReceiver);
		
		LauncherParameters shutdownParameters = new LauncherParameters();
		shutdownParameters.stoppedServices.addAll(parameters.runningServices);
		shutdownParameters.stoppedServices.addAll(parameters.stoppedServices);
		check(shutdownParameters);
		stopAlarm();
		super.stop();
	}

	@Override
	public LauncherParameters newParameters() {
		return new LauncherParameters();
	}
	
	@Override
	public LauncherParameters newParameters(LauncherParameters parameters) {
		return new LauncherParameters(parameters);
	}

	@Override
	public Class<LauncherParameters> parameterClass() {
		return LauncherParameters.class;
	}

	@Override
	public LauncherState newState() {
		return new LauncherState();
	}	
}

@Root(name="LauncherService")
class LauncherParameters extends PeriodicParameters {
	
	@ElementList(type=ScheduledService.class)
	public HashSet<ScheduledService> runningServices;
	
	@ElementList(type=ScheduledService.class)
	public HashSet<ScheduledService> stoppedServices;
	
	@ElementList(type=ScheduledService.class)
	public HashSet<ScheduledService> checkingServices;
	
	public LauncherParameters() {
		super();
		runningServices = new HashSet<ScheduledService>();
		stoppedServices = new HashSet<ScheduledService>();
		checkingServices = new HashSet<ScheduledService>();
	}
	
	public LauncherParameters(LauncherParameters parameters) {
		super(parameters);
		runningServices = new HashSet<ScheduledService>(parameters.runningServices);
		stoppedServices = new HashSet<ScheduledService>(parameters.stoppedServices);
		checkingServices = new HashSet<ScheduledService>(parameters.checkingServices);
	}
	
	private HashSet<ScheduledService> filterServices(HashSet<ScheduledService> services, Date now) {
		HashSet<ScheduledService> filteredServices = new HashSet<ScheduledService>();
		for (ScheduledService scheduledService : services) {
			if ((scheduledService.startTime.before(now)) && scheduledService.endTime.after(now)) {
				filteredServices.add(scheduledService);
			}
		}
		return filteredServices;
	}
	
	public HashSet<ScheduledService> currentRunningServices(Date now) {
		return filterServices(runningServices, now);
	}
	
	public HashSet<ScheduledService> currentStoppedServices(Date now) {
		return filterServices(stoppedServices, now);
	}
	
	public HashSet<ScheduledService> currentCheckingServices(Date now) {
		return filterServices(checkingServices, now);
	}
	
	public Date nextEvent(Date now) {
		Date currentNext = new Date(Long.MAX_VALUE);
		ArrayList<ScheduledService> allServices = new ArrayList<ScheduledService>(runningServices);
		allServices.addAll(stoppedServices);
		for (ScheduledService scheduledService : allServices) {
			if (((scheduledService.startTime.after(now)) &&
				 (scheduledService.startTime.before(currentNext))) || 
				((scheduledService.endTime.after(now)) &&
				 (scheduledService.endTime.before(currentNext)))) {
				currentNext = scheduledService.startTime;
			}
		}
		return currentNext;
	}

	@Override
	public String toString() {
		return "LauncherParameters [runningServices=" + runningServices
				+ ", stoppedServices=" + stoppedServices
				+ ", checkingServices=" + checkingServices + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime
				* result
				+ ((checkingServices == null) ? 0 : checkingServices.hashCode());
		result = prime * result
				+ ((runningServices == null) ? 0 : runningServices.hashCode());
		result = prime * result
				+ ((stoppedServices == null) ? 0 : stoppedServices.hashCode());
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
		LauncherParameters other = (LauncherParameters) obj;
		if (checkingServices == null) {
			if (other.checkingServices != null)
				return false;
		} else if (!checkingServices.equals(other.checkingServices))
			return false;
		if (runningServices == null) {
			if (other.runningServices != null)
				return false;
		} else if (!runningServices.equals(other.runningServices))
			return false;
		if (stoppedServices == null) {
			if (other.stoppedServices != null)
				return false;
		} else if (!stoppedServices.equals(other.stoppedServices))
			return false;
		return true;
	}
}

class ScheduledService {
	
	@Attribute(required=false)
	public Date startTime;
	
	@Attribute(required=false)
	public Date endTime;
	
	@Element
	public String intentName;
	
	@Element
	public String packageName;
	
	public ScheduledService() {
		super();
		this.startTime = new Date(0L);
		this.endTime = new Date(Long.MAX_VALUE);
	}
	
	public ScheduledService(String intentName, String packageName) {
		super();
		this.intentName = intentName;
		this.packageName = packageName;
		this.startTime = new Date(0L);
		this.endTime = new Date(Long.MAX_VALUE);
	}
	
	public ScheduledService(Date startTime, Date endTime, String intentName, String packageName) {
		super();
		this.startTime = startTime;
		this.endTime = endTime;
		this.intentName = intentName;
		this.packageName = packageName;
	}

	@Override
	public String toString() {
		return "ScheduledService [startTime=" + startTime + ", endTime="
				+ endTime + ", intentName=" + intentName + ", packageName="
				+ packageName + "]";
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((endTime == null) ? 0 : endTime.hashCode());
		result = prime * result
				+ ((intentName == null) ? 0 : intentName.hashCode());
		result = prime * result
				+ ((packageName == null) ? 0 : packageName.hashCode());
		result = prime * result
				+ ((startTime == null) ? 0 : startTime.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ScheduledService other = (ScheduledService) obj;
		if (endTime == null) {
			if (other.endTime != null)
				return false;
		} else if (!endTime.equals(other.endTime))
			return false;
		if (intentName == null) {
			if (other.intentName != null)
				return false;
		} else if (!intentName.equals(other.intentName))
			return false;
		if (packageName == null) {
			if (other.packageName != null)
				return false;
		} else if (!packageName.equals(other.packageName))
			return false;
		if (startTime == null) {
			if (other.startTime != null)
				return false;
		} else if (!startTime.equals(other.startTime))
			return false;
		return true;
	}
}

@Root(name="LauncherService")
class LauncherState extends PeriodicState {
	@ElementList
	public ArrayList<ScheduledService> runningServices;
	
	@Element
	public Long nextInterval;
	
	public LauncherState() {
		super();
		runningServices = new ArrayList<ScheduledService>();
		nextInterval = 0L;
	}
}
