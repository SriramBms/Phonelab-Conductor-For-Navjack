package edu.buffalo.cse.phonelab.harness.lib.interfaces;

public interface UploaderClient {
	public void prepare();
	public boolean hasNext();
	public long bytesAvailable();
	public UploaderFileDescription next();
	public void complete(UploaderFileDescription uploaderFileDescription, boolean success);
}
