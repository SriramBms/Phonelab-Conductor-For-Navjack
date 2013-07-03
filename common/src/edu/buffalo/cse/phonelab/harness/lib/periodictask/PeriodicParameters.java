package edu.buffalo.cse.phonelab.harness.lib.periodictask;

import org.simpleframework.xml.Element;

import android.app.AlarmManager;

public abstract class PeriodicParameters {
	
	@Element
	public Long checkInterval;
	
	public PeriodicParameters() {
		this.checkInterval = AlarmManager.INTERVAL_HOUR / 1000;
	}
	
	public PeriodicParameters(PeriodicParameters parameters) {
		this.checkInterval = parameters.checkInterval;
	}
	
	@Override
	public String toString() {
		return "PeriodicParameters [checkInterval=" + checkInterval + "]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((checkInterval == null) ? 0 : checkInterval.hashCode());
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
		PeriodicParameters other = (PeriodicParameters) obj;
		if (checkInterval == null) {
			if (other.checkInterval != null)
				return false;
		} else if (!checkInterval.equals(other.checkInterval))
			return false;
		return true;
	}
}