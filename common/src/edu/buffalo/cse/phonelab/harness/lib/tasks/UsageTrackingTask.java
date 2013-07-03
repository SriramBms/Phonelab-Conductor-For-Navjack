package edu.buffalo.cse.phonelab.harness.lib.tasks;

import java.io.StringWriter;
import java.text.DecimalFormat;
import java.util.Date;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.CallLog;
import android.util.Log;

import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicParameters;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicState;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicTask;

public class UsageTrackingTask extends PeriodicTask<UsageTrackingParameters, UsageTrackingState> {
    private final String TAG = "PhoneLabServices-" + this.getClass().getSimpleName();

    private Context context;

    private Date screenTurnedOn;
    private long screenOnTimeMs;

    private long totalRxBytes;
    private long totalTxBytes;

    private int appInstalled;
    private int appRemoved;

    private static final int MIN_DISTANCE = 100; /* 100 meters */
    private Location lastKnowLocation;
    private int travelDistance;

    public UsageTrackingTask(Context context) {
        super(context, "UsageTrackingService");
        this.context = context;

        if (isScreenOn()) {
            screenTurnedOn = new Date();
        }
        else {
            screenTurnedOn = new Date(0L);
        }
        screenOnTimeMs = 0;

        totalRxBytes = totalTxBytes = 0;

        appInstalled = appRemoved = 0;

        MiscReceiver miscReceiver = new MiscReceiver();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        context.registerReceiver(miscReceiver, intentFilter);

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        lastKnowLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, MIN_DISTANCE, miscReceiver);
    }

    @Override
    protected void check(UsageTrackingParameters parameters) {
        if (state.lastChecked.equals(new Date(0L))) {
            state.lastChecked = new Date();
            return;
        }

        UsageData usageData = new UsageData();

        if (parameters.trackCallCount) {
            usageData.callCount = getCallCount(state.lastChecked);
        }

        if (parameters.trackSMSCount) {
            usageData.smsCount = getSMSCount(state.lastChecked);
        }


        if (parameters.trackScreenOnTime) {
            if (!screenTurnedOn.equals(new Date(0L))) {
                screenOnTimeMs += (new Date()).getTime() - screenTurnedOn.getTime();
                screenTurnedOn = new Date();
            }
            usageData.screenOnTimeSec = screenOnTimeMs / 1000;
            screenOnTimeMs = 0;
        }
        
        if (parameters.trackNetworkTraffic) {
            /* omit the history traffic, 'cause it'll be huge... */
            if (totalRxBytes > 0) {
                usageData.rxTrafficKB = (TrafficStats.getTotalRxBytes() - totalRxBytes) / 1024;
                usageData.txTrafficKB = (TrafficStats.getTotalTxBytes() - totalTxBytes) / 1024;
            }
            totalRxBytes = TrafficStats.getTotalRxBytes();
            totalTxBytes = TrafficStats.getTotalTxBytes();
        }

        if (parameters.trackBatteryLevel) {
            usageData.batteryLevel = getBatteryLevel();
        }

        if (parameters.trackAppCount) {
            usageData.installedAppCount = appInstalled;
            usageData.removedAppCount = appRemoved;
            appInstalled = appRemoved = 0;
        }

        if (parameters.trackTravelDistance) {
            usageData.travelDistanceMeter = travelDistance;
            travelDistance = 0;
        }

        state.lastChecked = new Date();

        StringWriter stringWriter = new StringWriter();
        Serializer serializer = new Persister();
        try {
            serializer.write(usageData, stringWriter);
        } catch (Exception e) {
            Log.e(TAG, "Problem serializing usage data " + e);
        }
        String xmlUsageData = stringWriter.toString();
        Log.v(TAG, xmlUsageData);
    }

    private boolean isScreenOn() {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager.isScreenOn();
    }

    private int getCallCount(Date since) {
        int count = 0;
        try {
            String[] projection = {CallLog.Calls.DATE, CallLog.Calls.TYPE};
            String selection = CallLog.Calls.DATE + " >= " + since.getTime();
            Cursor cursor = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, projection, selection, null, CallLog.Calls.DEFAULT_SORT_ORDER);
            count = cursor.getCount();
            cursor.close();
        }
        catch (Exception e) {
            Log.e(TAG, "Fail to query CallLog provider: " + e);
        }
        return count;
    }

    private int getSMSCount(Date since) {
        int count = 0;
        try {
            Uri uri = Uri.parse("content://sms/");
            String[] projection = {"date", "type"};
            String selection = "date >= " + since.getTime();
            Cursor cursor = context.getContentResolver().query(uri, projection, selection, null, null);
            count = cursor.getCount();
            cursor.close();
        }
        catch (Exception e) {
            Log.e(TAG, "Fail to query sms content provider: " + e);
        }
        return count;
    }

    private double getBatteryLevel() {
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) {
            return 0;
        }

        int rawlevel = intent.getIntExtra("level", -1);
        double scale = intent.getIntExtra("scale", -1);
        double level = -1;
        if (rawlevel >= 0 && scale > 0) {
            level = rawlevel / scale;
        }

        return Double.parseDouble((new DecimalFormat("#")).format(level));
    }

    @Override
    public UsageTrackingParameters newParameters() {
        return new UsageTrackingParameters();
    }

    @Override
    public UsageTrackingParameters newParameters(
            UsageTrackingParameters parameters) {
        return new UsageTrackingParameters(parameters);
            }

    @Override
    public UsageTrackingState newState() {
        return new UsageTrackingState();
    }

    @Override
    public Class<UsageTrackingParameters> parameterClass() {
        return UsageTrackingParameters.class;
    }

    class MiscReceiver extends BroadcastReceiver implements LocationListener {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                screenTurnedOn = new Date();
                Log.v(TAG, "Screen turned on.");
            }
            else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                if (!screenTurnedOn.equals(new Date(0L))) {
                    screenOnTimeMs += (new Date()).getTime() - screenTurnedOn.getTime();
                    screenTurnedOn = new Date(0L);
                }
                Log.v(TAG, "Screen turned off.");
            }
            else if (intent.getAction().equals(Intent.ACTION_PACKAGE_ADDED)) {
                appInstalled++;
                Log.v(TAG, "Installed app: " + intent.getDataString().split(":")[1]);
            }
            else if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                appRemoved++;
                Log.v(TAG, "Removed app: " + intent.getDataString().split(":")[1]);
            }

        }

        @Override
        public void onLocationChanged(Location location) {
            Log.v(TAG, "Last known location" + lastKnowLocation + "; new location " + location);
            travelDistance += lastKnowLocation.distanceTo(location);
            lastKnowLocation = location;
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    }
}

@Root(name="UsageTrackingService")
class UsageTrackingParameters extends PeriodicParameters {
    @Element
    public Boolean trackCallCount;
    @Element
    public Boolean trackSMSCount;
    @Element
    public Boolean trackScreenOnTime;
    @Element
    public Boolean trackNetworkTraffic;
    @Element
    public Boolean trackBatteryLevel;
    @Element
    public Boolean trackTravelDistance;
    @Element
    public Boolean trackAppCount;

    /**
     *
     */
    public UsageTrackingParameters() {
        super();
        trackCallCount = true;
        trackSMSCount = true;
        trackScreenOnTime = true;
        trackNetworkTraffic = true;
        trackBatteryLevel = true;
        trackTravelDistance = true;
        trackAppCount = true;
    }

    public UsageTrackingParameters(UsageTrackingParameters parameters) {
        super(parameters);

        this.trackCallCount = parameters.trackCallCount;
        this.trackSMSCount = parameters.trackSMSCount;
        this.trackScreenOnTime = parameters.trackScreenOnTime;
        this.trackNetworkTraffic = parameters.trackNetworkTraffic;
        this.trackBatteryLevel = parameters.trackBatteryLevel;
        this.trackTravelDistance = parameters.trackTravelDistance;
        this.trackAppCount = parameters.trackAppCount;
    }

    public String toString() {
        return this.getClass().getSimpleName() + 
            "[trackCallCount = " + trackCallCount + ", " + 
            "trackSMSCount = " + trackSMSCount + ", " +
            "trackScreenOnTime = " + trackScreenOnTime + ", " +
            "trackNetworkTraffic = " + trackScreenOnTime  + ", " +
            "trackPowerLevel = " + trackBatteryLevel + ", " +
            "trackLocation = " + trackTravelDistance + ", " +
            "trackAppCount =" + trackAppCount + "]";

    }

    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result
            + ((trackCallCount == null) ? 0 : trackCallCount.hashCode());
        result = prime * result
            + ((trackSMSCount == null) ? 0 : trackSMSCount.hashCode());
        result = prime * result
            + ((trackScreenOnTime == null) ? 0 : trackScreenOnTime.hashCode());
        result = prime * result
            + ((trackNetworkTraffic == null) ? 0 : trackNetworkTraffic.hashCode());
        result = prime * result
            + ((trackBatteryLevel == null) ? 0 : trackBatteryLevel.hashCode());
        result = prime * result
            + ((trackTravelDistance == null) ? 0 : trackTravelDistance.hashCode());
        result = prime * result
            + ((trackAppCount == null) ? 0 : trackAppCount.hashCode());
        return result;
    }

    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        UsageTrackingParameters other = (UsageTrackingParameters) obj;

        if (trackCallCount == null) {
            if (other.trackCallCount != null)
                return false;
        } else if (!trackCallCount.equals(other.trackCallCount))
            return false;

        if (trackSMSCount == null) {
            if (other.trackSMSCount != null)
                return false;
        } else if (!trackSMSCount.equals(other.trackSMSCount))
            return false;

        if (trackScreenOnTime == null) {
            if (other.trackScreenOnTime != null)
                return false;
        } else if (!trackScreenOnTime.equals(other.trackScreenOnTime))
            return false;


        if (trackNetworkTraffic == null) {
            if (other.trackNetworkTraffic != null)
                return false;
        } else if (!trackNetworkTraffic.equals(other.trackNetworkTraffic))
            return false;

        if (trackBatteryLevel == null) {
            if (other.trackBatteryLevel != null)
                return false;
        } else if (!trackBatteryLevel.equals(other.trackBatteryLevel))
            return false;

        if (trackTravelDistance == null) {
            if (other.trackTravelDistance != null)
                return false;
        } else if (!trackTravelDistance.equals(other.trackTravelDistance))
            return false;

        if (trackAppCount == null) {
            if (other.trackAppCount != null)
                return false;
        } else if (!trackAppCount.equals(other.trackAppCount))
            return false;

        return true;
    }
}

@Root(name="UsageTrackingService")
class UsageTrackingState extends PeriodicState {
    @Element
    public Date lastChecked;
    /**
     *
     */
    public UsageTrackingState() {
        super();
        lastChecked = new Date(0L);
    }
}

class UsageData {
    @Element
    public Integer callCount;
    @Element
    public Integer smsCount;
    @Element
    public Long screenOnTimeSec;
    @Element
    public Long rxTrafficKB;
    @Element
    public Long txTrafficKB;
    @Element
    public Double batteryLevel;
    @Element
    public Integer travelDistanceMeter;
    @Element
    public Integer installedAppCount;
    @Element
    public Integer removedAppCount;

    /**
     *
     */
    public UsageData() {
        callCount = 0;
        smsCount = 0;
        screenOnTimeSec = 0L;
        rxTrafficKB = txTrafficKB = 0L;
        batteryLevel = 0.0;
        travelDistanceMeter = 0;
        installedAppCount = removedAppCount = 0;
    }
}
