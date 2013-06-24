package edu.buffalo.cse.phonelab.harness.lib.interfaces;

public interface ManifestClient {
	public boolean parametersUpdated(String manifestString);
	public String getState();
}