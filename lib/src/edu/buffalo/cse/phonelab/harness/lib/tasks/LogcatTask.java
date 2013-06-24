package edu.buffalo.cse.phonelab.harness.lib.tasks;
 	
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Formatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;

import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderClient;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.UploaderFileDescription;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicParameters;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicState;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicTask;
import edu.buffalo.cse.phonelab.harness.lib.services.UploaderService;
import edu.buffalo.cse.phonelab.harness.lib.services.UploaderService.LoggerBinder;
import edu.buffalo.cse.phonelab.harness.lib.util.Util;

public class LogcatTask extends PeriodicTask<LogcatParameters, LogcatState> implements UploaderClient {
	
	private LogcatProcess logcatProcess = null;
	private ArrayList<LogcatProcess> stoppedLogcatProcesses;
	
	public synchronized void check(LogcatParameters parameters) {
		if (logcatProcess == null) {
			Log.v(TAG, "No logcat process running. Starting in " + LogcatTask.this.logRoot);
			logcatProcess = new LogcatProcess(LogcatTask.this.logRoot, parameters);
		} else if (logcatProcess.parameters.equals(parameters) == false) {
			Log.v(TAG, "Logcat process running but with different parameters.");
			logcatProcess.stop();
			stoppedLogcatProcesses.add(logcatProcess);
			logcatProcess = new LogcatProcess(LogcatTask.this.logRoot, parameters);
		}
		if (logcatProcess.isRunning() == false) {
			Log.v(TAG, "Correct parameters loaded but not yet running. Trying to start.");
			try {
				logcatProcess.start();
			} catch (Exception e) {
				Log.e(TAG, "Error while starting logcat: " + e);
			}
		}
		updateUploaderFiles();
	}
	
	private File logRoot;
	
	public LogcatTask(Context context) {
		super(context, "LogcatService");
		logRoot = context.getDir(this.getClass().getSimpleName(), Context.MODE_PRIVATE);
		stoppedLogcatProcesses = new ArrayList<LogcatProcess>();
		
		if (logRoot.exists() == false) {
			logRoot.mkdir();
		} else {
			for (File f1 : logRoot.listFiles()) {
				if (f1.isDirectory() == true) {
					try {
						LogcatProcess oldLogcatProcess = new LogcatProcess(f1.getAbsolutePath());
						if (oldLogcatProcess.isRunning() == true) {
							if (oldLogcatProcess.parameters.equals(initialParameters)) {
								Log.v(TAG, "Found logcat process running on startup with correct parameters. Setting it as current.");
								if (logcatProcess != null) {
									Log.e(TAG, "Found more than one logcat process running on startup with correct parameters. Stopping one.");
									oldLogcatProcess.stop();
									Log.v(TAG, "Adding logs for " + oldLogcatProcess + " to queue.");
									stoppedLogcatProcesses.add(oldLogcatProcess);
								}
								logcatProcess = oldLogcatProcess;
							} else {
								Log.v(TAG, "Stopping logcat process with incorrect parameters.");
								oldLogcatProcess.stop();
								Log.v(TAG, "Adding logs for " + oldLogcatProcess + " to queue.");
								stoppedLogcatProcesses.add(oldLogcatProcess);
							}
						} else {
							Log.v(TAG, "Found old dead logcat process.");
							Log.v(TAG, "Adding logs for " + oldLogcatProcess + " to queue.");
							stoppedLogcatProcesses.add(oldLogcatProcess);
						}
					} catch (Exception e) {
						Log.i(TAG, e.toString());
					}
				}
			}
		}
		
		Intent uploaderServiceIntent = new Intent(context, UploaderService.class);
		context.startService(uploaderServiceIntent);
		context.bindService(uploaderServiceIntent, uploaderServiceConnection, Context.BIND_AUTO_CREATE);
	}
	
	@Override
	public void stop() {
		try {
			updateUploaderFiles();
			uploaderService.unregisterLogger(LogcatTask.this);
			context.unbindService(uploaderServiceConnection);
			super.stop();
		} catch (Exception e) { };
	}
	
	UploaderService uploaderService;
	private ServiceConnection uploaderServiceConnection = new ServiceConnection() {
		
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			Log.v(TAG, "Connecting to uploader service.");
			LoggerBinder binder = (LoggerBinder) service;
			uploaderService = binder.getService();
			uploaderService.registerLogger(LogcatTask.this, UploaderService.PRIORITY_LOGCAT);
		}
		
		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			Log.w(TAG, "Uploader service disconnected.");
			return;
		}
	};
	
	private ArrayList<File> uploaderFiles = new ArrayList<File>();
	
	private synchronized void updateUploaderFiles() {
		
		ArrayList<LogcatProcess> destroyingLogcatProcesses = new ArrayList<LogcatProcess>();
		Collections.sort(stoppedLogcatProcesses);
		uploaderFiles = new ArrayList<File>();
		
		for (LogcatProcess stoppedLogcatProcess : stoppedLogcatProcesses) {
			ArrayList<File> oldFiles;
			try {
				oldFiles = stoppedLogcatProcess.getLogFiles();
			} catch (Exception e) {
				Log.e(TAG, "Unable to get list of log files to upload for stopped logcat process: " + e);
				continue;
			}
			if (oldFiles.size() == 0) {
				Log.v(TAG, "No log files for " + stoppedLogcatProcess);
				destroyingLogcatProcesses.add(stoppedLogcatProcess);
			} else if (uploaderFiles.size() > parameters.uploadFileCount) {
				Log.v(TAG, "Upload list too long. Destroying logs for " + stoppedLogcatProcess);
				destroyingLogcatProcesses.add(stoppedLogcatProcess);		
			} else {
				Log.v(TAG, "Adding old log files for " + stoppedLogcatProcess);
				uploaderFiles.addAll(oldFiles);
			}
		}
		
		for (LogcatProcess destroyingLogcatProcess : destroyingLogcatProcesses) {
			Log.v(TAG, "Destroying stopped logcat process " + destroyingLogcatProcess);
			try {
				destroyingLogcatProcess.destroy();
			} catch (Exception e) {
				Log.e(TAG, "Exception while destroying old logcat process " + e);
			}
			stoppedLogcatProcesses.remove(destroyingLogcatProcess);
		}
		
		try {
			uploaderFiles.addAll(logcatProcess.getLogFiles());
		} catch (Exception e) {
			Log.e(TAG, "Unable to get list of log files to upload for current process: " + e);
		}
		
		synchronized (stateLock) {
			state.bytesAvailable = 0L;
			for (File file : uploaderFiles) {
				state.bytesAvailable += file.length();
			}
		}
	}
	
	
	@Override
	public boolean hasNext() {
		synchronized (LogcatTask.this) {
			if (uploaderFiles.size() == 0) {
				return false;
			} else {
				return true;
			}
		}
	}
	
	private File currentFile = null;
	private String currentFileHash = null;
	private void setCurrentFile(File file) throws NoSuchAlgorithmException, IOException {
		synchronized (LogcatTask.this) {
			currentFile = file;
			currentFileHash = Util.hashFile(file);
		}
	}
	private boolean checkCurrentFile() {
		synchronized (LogcatTask.this) {
			try {
				if (Util.hashFile(currentFile).equals(currentFileHash)) {
					return true;
				} else {
					return false;
				}
			} catch (Exception e) {
				return false;
			}
		}
	}
	private void clearCurrentFile() {
		synchronized (LogcatTask.this) {
			currentFile = null;
			currentFileHash = null;
		}
	}
	
	@Override
	public void prepare() {
		Log.v(TAG, "Preparing files for upload.");
		updateUploaderFiles();
	}

	@Override
	public long bytesAvailable() {
		if (hasNext() == false) {
			return 0L;
		} else {
			return state.bytesAvailable;
		}
	}
	
	@Override
	public UploaderFileDescription next() {
		synchronized (LogcatTask.this) {
			try {
				setCurrentFile(uploaderFiles.get(0));
			} catch (Exception e) {
				clearCurrentFile();
				return null;	
			}
			
			Log.v(TAG, "Uploading " + currentFile.getName());
			
			if (currentFile.exists() == true) {
				return new UploaderFileDescription(currentFile.getAbsolutePath(), LogcatProcess.LOG_FILENAME, currentFile.length());
			} else {
				Log.e(TAG, "File in upload list disappeared before we could upload it.");
				updateUploaderFiles();
				clearCurrentFile();
				return null;
			}
		}
	}

	@Override
	public void complete(UploaderFileDescription uploaderFileDescription, boolean success) {
		synchronized (LogcatTask.this) {
			if (success == true) {
				Log.v(TAG, "Uploading " + currentFile.getName() + " succeeded.");
				
				if (checkCurrentFile() == false) {
					Log.w(TAG, "Did not pass hash " + currentFile.getName());
					clearCurrentFile();
					updateUploaderFiles();
					return;
				}
				if (currentFile.delete() != true) {
					Log.e(TAG, "Unable to delete " + currentFile.getName());
				} else {
					Log.v(TAG, "Deleted " + currentFile.getName());
				}
				if (uploaderFiles.remove(currentFile) != true) {
					Log.e(TAG, "Unable to remove " + currentFile.getName());
				}
			} else {
				Log.v(TAG, "Uploading " + currentFile.getName() + " failed.");
			}
			clearCurrentFile();
		}
	}
	
	@Override
	public String getState() {
		updateUploaderFiles();
		return super.getState();
	}
	
	@Override
	public LogcatParameters newParameters() {
		return new LogcatParameters();
	}
	
	@Override
	public LogcatParameters newParameters(LogcatParameters parameters) {
		return new LogcatParameters(parameters);
	}

	@Override
	public Class<LogcatParameters> parameterClass() {
		return LogcatParameters.class;
	}

	@Override
	public LogcatState newState() {
		return new LogcatState();
	}

	
}

@Root(name="LogcatService")
class LogcatParameters extends PeriodicParameters {
	
	@Element(required=false)
	public Integer PID;
	
	@Element(required=false)
	public Date started;
	
	@Element
	public String format;
	
	@ElementList(type=Tag.class)
	public HashSet<Tag> tags;
	
	@Element
	public Integer fileSize;
	
	@Element
	public Integer fileCount;
	
	@Element(required=false)
	public Integer uploadFileCount;
	
	public LogcatParameters() {
		super();
		PID = null;
		started = null;
		format = "threadtime";
		fileSize = 100;
		fileCount = 1024;
		uploadFileCount = 2048;
		tags = new HashSet<Tag>();
		tags.add(new Tag("*", "V"));
	}
	
	public LogcatParameters(LogcatParameters parameters) {
		this.PID = parameters.PID;
		this.started = parameters.started;
		this.format = parameters.format;
		this.tags = new HashSet<Tag>(parameters.tags);
		this.fileSize = parameters.fileSize;
		this.fileCount = parameters.fileCount;
		this.uploadFileCount = parameters.uploadFileCount;
	}
	
	public String toLogcatCommand(File logcatFile) {
		StringBuilder stringBuilder = new StringBuilder();
		Formatter formatter = new Formatter(stringBuilder, Locale.US);
		
		formatter.format("logcat ");
		formatter.format("-v %s ", format);
		formatter.format("-r %d ", fileSize);
		formatter.format("-n %d ", fileCount);
		formatter.format("-f %s ", logcatFile.getAbsolutePath());
		
		formatter.format("*:S ");
		for (Tag tag : tags) {
			formatter.format("%s ", tag.toLogcatTag());
		}
		
		formatter.close();
		return stringBuilder.toString();
	}

	@Override
	public String toString() {
		return "LogcatParameters [PID=" + PID + ", started=" + started
				+ ", format=" + format + ", tags=" + tags + ", fileSize="
				+ fileSize + ", fileCount=" + fileCount + ", uploadFileCount="
				+ uploadFileCount + "]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((PID == null) ? 0 : PID.hashCode());
		result = prime * result
				+ ((fileCount == null) ? 0 : fileCount.hashCode());
		result = prime * result
				+ ((fileSize == null) ? 0 : fileSize.hashCode());
		result = prime * result + ((format == null) ? 0 : format.hashCode());
		result = prime * result + ((started == null) ? 0 : started.hashCode());
		result = prime * result + ((tags == null) ? 0 : tags.hashCode());
		result = prime * result
				+ ((uploadFileCount == null) ? 0 : uploadFileCount.hashCode());
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
		LogcatParameters other = (LogcatParameters) obj;
		if (PID == null) {
			if (other.PID != null)
				return false;
		} else if (!PID.equals(other.PID))
			return false;
		if (fileCount == null) {
			if (other.fileCount != null)
				return false;
		} else if (!fileCount.equals(other.fileCount))
			return false;
		if (fileSize == null) {
			if (other.fileSize != null)
				return false;
		} else if (!fileSize.equals(other.fileSize))
			return false;
		if (format == null) {
			if (other.format != null)
				return false;
		} else if (!format.equals(other.format))
			return false;
		if (started == null) {
			if (other.started != null)
				return false;
		} else if (!started.equals(other.started))
			return false;
		if (tags == null) {
			if (other.tags != null)
				return false;
		} else if (!tags.equals(other.tags))
			return false;
		if (uploadFileCount == null) {
			if (other.uploadFileCount != null)
				return false;
		} else if (!uploadFileCount.equals(other.uploadFileCount))
			return false;
		return true;
	}
}

class Tag {
	
	@Attribute
	public String name;
	
	@Attribute
	public String level;
	
	public Tag() { 
		super();
	}
	
	public Tag(String name, String level) {
		super();
		this.name = name;
		this.level = level;
	}

	public String toLogcatTag() {
		StringBuilder stringBuilder = new StringBuilder();
		Formatter formatter = new Formatter(stringBuilder, Locale.US);
		formatter.format("%s:%s", name, level);
		formatter.close();
		return stringBuilder.toString();
	}
	
	@Override
	public String toString() {
		return "TagInfo [name=" + name + ", level=" + level + "]";
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((level == null) ? 0 : level.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
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
		Tag other = (Tag) obj;
		if (level == null) {
			if (other.level != null)
				return false;
		} else if (!level.equals(other.level))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}
}

@Root(name="LogcatService")
class LogcatState extends PeriodicState {
	@Element
	public Long bytesAvailable;
	
	public LogcatState() {
		super();
		bytesAvailable = 0L;
	}
}

class LogcatProcess implements Comparable<LogcatProcess> {
	
	public static final String LOG_FILENAME = "log.out";
	private static final String PARAMETER_FILENAME = "parameters.xml";
	
	public String logcatCommand;
		
	public LogcatParameters parameters;
	
	public File logcatDir;
	public File logFile;	
	public File pidFile;
	
	public LogcatProcess(String logcatDir) throws Exception {
		super();
		this.logcatDir = new File(logcatDir);
		pidFile = new File(logcatDir + "/" + PARAMETER_FILENAME);
		logFile = new File(logcatDir + "/" + LOG_FILENAME);
		Serializer serializer = new Persister();
		this.parameters = serializer.read(LogcatParameters.class, pidFile);
		this.logcatCommand = parameters.toLogcatCommand(logFile);
	}
	
	public LogcatProcess(File logRoot, LogcatParameters parameters) {
		super();
		logcatDir = new File(logRoot.getAbsolutePath() + "/" + new BigInteger(130, new Random()).toString(32));
		logcatDir.mkdir();
		pidFile = new File(logcatDir + "/" + PARAMETER_FILENAME);
		logFile = new File(this.logcatDir + "/" + LOG_FILENAME);
		this.parameters = parameters;
		this.logcatCommand = parameters.toLogcatCommand(logFile);
	}
	
	public void start() throws Exception {
		Process logcatProcess;
		try {
			logcatProcess = new ProcessBuilder().command(this.logcatCommand.split(" ")).redirectErrorStream(true).start();
			Log.v("PhoneLabServices-LogcatTask", logcatProcess.toString());
		} catch (Exception e) {
			throw(e);
		}
		
		try {
			Field field = logcatProcess.getClass().getDeclaredField("pid");
			field.setAccessible(true);
			this.parameters.PID = ((Integer) field.get(logcatProcess));
			this.parameters.started = new Date();
		} catch (Exception e) {
			this.parameters.PID = null;
			try {
				logcatProcess.destroy();
			} catch (Exception e1) { }
			throw(e);
		}
		
		try {
			Serializer serializer = new Persister();
			serializer.write(this.parameters, this.pidFile);
		} catch (Exception e) {
			this.pidFile.delete();
			this.parameters.PID = null;
			try {
				logcatProcess.destroy();
			} catch (Exception e1) { }
			throw(e);
		}
		Log.v("PhoneLabServices-LogcatTask", "Started logcat process " + this.parameters.PID + " successfully: " + this.logcatCommand);
	}
	
	public void stop() {
		if (this.parameters.PID == null) {
			return;
		}
		android.os.Process.killProcess(this.parameters.PID);
		Log.v("PhoneLabServices-LogcatTask", "Stopped logcat process " + this.parameters.PID + " successfully.");
	}
	
	public void destroy() throws Exception {
		if (this.pidFile.delete() == false) {
			throw new Exception("Unable to delete PID file");
		}
		if (this.logcatDir.delete() == false) {
			Log.v("PhoneLabServices-LogcatTask", "Logcat directory not empty. Removing files.");
			for (File f1 : this.logcatDir.listFiles()) {
				f1.delete();
			}
		}
		if (this.logcatDir.delete() == false) {
			throw new Exception("Unable to delete PID directory.");
		}
	}
	
	public boolean isRunning() {
		if (this.parameters.PID == null) {
			return false;
		}
		
		String processCommandLine = null;
		try {
			processCommandLine = new BufferedReader(new FileReader(new File("/proc/" + this.parameters.PID + "/cmdline"))).readLine();
			processCommandLine = TextUtils.join(" ", Arrays.asList(this.logcatCommand.split("\0")));
			if (processCommandLine.equals(this.logcatCommand)) {
				return true;
			} else {
				return false;
			}
		} catch (Exception e) {
			return false;
		}
	}
	
	private FileFilter logcatFileFilter = new FileFilter() {
		public boolean accept(File pathname) {
			String filename = pathname.getName();
			if (filename.startsWith(LogcatProcess.LOG_FILENAME) == true) {
				if (LogcatProcess.this.isRunning() == false) {
					return true;
				} else {
					return (filename.equals(LogcatProcess.LOG_FILENAME) == false);
				}
			} else {
				return false;
			}
		}
	};
	
	public ArrayList<File> getLogFiles() throws Exception {
		
		if (this.logcatDir == null) {
			return new ArrayList<File>();
		}
		
		ArrayList<File> logcatFiles;
		try {
			logcatFiles = new ArrayList<File>(Arrays.asList(this.logcatDir.listFiles(logcatFileFilter)));
		} catch (Exception e) {
			throw(e);
		}
		
		if (logcatFiles.isEmpty()) {
			return logcatFiles;
		}
		
		Collections.sort(logcatFiles, new Comparator<File>() {
			@Override
			public int compare(File f1, File f2) {
				Integer ext1 = 0;
				try {
					ext1 = Integer.valueOf(f1.getName().substring(f1.getName().lastIndexOf('.') + 1));
				} catch (Exception e) {}
				
				Integer ext2 = 0;
				try {
					ext2 = Integer.valueOf(f2.getName().substring(f2.getName().lastIndexOf('.') + 1));
				} catch (Exception e) {}
				
				return ext1.compareTo(ext2);
			}
		});
		Collections.reverse(logcatFiles);
		return new ArrayList<File>(logcatFiles);
	}

	@Override
	public String toString() {
		return "LogcatProcess [logcatDir=" + logcatDir + ", logcatCommand="
				+ logcatCommand + ", parameters=" + parameters + ", logFile="
				+ logFile + ", pidFile=" + pidFile + "]";
	}

	@Override
	public int compareTo(LogcatProcess o) {
		if (this.parameters.started == null && o.parameters.started == null) {
			return 0;
		}
		if (this.parameters.started == null && o.parameters.started != null) {
			return 1;
		}
		if (this.parameters.started != null && o.parameters.started == null) {
			return -1;
		}
		return o.parameters.started.compareTo(this.parameters.started);
	}
}
