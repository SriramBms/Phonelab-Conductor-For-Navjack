package edu.buffalo.cse.phonelab.harness.lib.periodictask;

import java.util.Date;

import org.simpleframework.xml.Element;

public class PeriodicState {
	
	@Element
	Date started;
	
	@Element
	Date restarted;
	
	@Element
	Date lastCheck;
	
	@Element
	Date parameterUpdate;
	
	@Element(required=false)
	PeriodicParameters parameters;
	
	public PeriodicState() {
		this.started = new Date(0L);
		this.restarted = new Date(0L);
		this.lastCheck = new Date(0L);
		this.parameterUpdate = new Date(0L);
		this.parameters = null;
	}
	
	@Override
	public String toString() {
		return "PeriodicState [started=" + started + ", restarted=" + restarted
				+ ", lastCheck=" + lastCheck + ", parameterUpdate="
				+ parameterUpdate + "]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((lastCheck == null) ? 0 : lastCheck.hashCode());
		result = prime * result
				+ ((parameterUpdate == null) ? 0 : parameterUpdate.hashCode());
		result = prime * result
				+ ((restarted == null) ? 0 : restarted.hashCode());
		result = prime * result + ((started == null) ? 0 : started.hashCode());
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
		PeriodicState other = (PeriodicState) obj;
		if (lastCheck == null) {
			if (other.lastCheck != null)
				return false;
		} else if (!lastCheck.equals(other.lastCheck))
			return false;
		if (parameterUpdate == null) {
			if (other.parameterUpdate != null)
				return false;
		} else if (!parameterUpdate.equals(other.parameterUpdate))
			return false;
		if (restarted == null) {
			if (other.restarted != null)
				return false;
		} else if (!restarted.equals(other.restarted))
			return false;
		if (started == null) {
			if (other.started != null)
				return false;
		} else if (!started.equals(other.started))
			return false;
		return true;
	}
}
