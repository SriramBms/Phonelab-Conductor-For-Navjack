package edu.buffalo.cse.phonelab.harness.lib.tasks;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementMap;
import org.simpleframework.xml.Root;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Log;
import edu.buffalo.cse.phonelab.harness.lib.interfaces.ManifestClient;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicParameters;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicState;
import edu.buffalo.cse.phonelab.harness.lib.periodictask.PeriodicTask;
import edu.buffalo.cse.phonelab.harness.lib.util.Util;

public class ManifestTask extends PeriodicTask<ManifestParameters, ManifestState> {
	
	private final static int CONNECTION_TIMEOUT_SEC = 10;
	private final static String BACKUP_MANIFEST_URL = "http://backend.phone-lab.org/manifest/";
	private final static int BACKUP_MANIFEST_THRESHOLD = 6;
	
	private int fallbackManifestCount;
	
	@Override
	protected synchronized void check(ManifestParameters parameters) {
		try {
			Log.v(TAG, "Collecting manifest.");
			collectState(parameters);
			Log.v(TAG, "Downloading manifest.");
			boolean newManifest = exchangeManifest(parameters.manifestURL, parameters);
			if (fallbackManifestCount > BACKUP_MANIFEST_THRESHOLD) {
				Log.w(TAG, "Using backup manifest URL due to failure count");
				newManifest = exchangeManifest(BACKUP_MANIFEST_URL, parameters);
			}
			if (newManifest == true || parameters.compareFiles == false) {
				Log.v(TAG, "Distributing manifest.");
				distributeManifest(newManifest, parameters);
			}
		} catch (Exception e) {
			Log.e(TAG, "Downloading and distributing the manifest failed: " + e);
		}
	}
	
	private File serverManifestFile;
	private String serverManifestHash;
	private File clientManifestFile;
	private File newManifestFile;
	private DocumentBuilderFactory manifestBuilderFactory;
	private DocumentBuilder manifestBuilder;
	private Document manifestDocument;
	PendingIntent pendingAlarmIntent;
		
	public ManifestTask(Context context) throws Exception {
		super(context, "ManifestService");
		
		File manifestDir = context.getDir(this.getClass().getSimpleName(), Context.MODE_PRIVATE);
		serverManifestFile = new File(manifestDir, "server.xml");
		clientManifestFile = new File(manifestDir, "client.xml");
		newManifestFile = new File(manifestDir, "new.xml");
		fallbackManifestCount = 0;
		
		if (serverManifestFile.exists()) {
			try {
				serverManifestHash = Util.hashFile(serverManifestFile);
			} catch (Exception e) {
				Log.e(TAG, "Failed to start." + e);
				throw(e);
			}
		} else {
			serverManifestHash = "";
		}
	
		manifestBuilderFactory = DocumentBuilderFactory.newInstance();
		try {
			manifestBuilder = manifestBuilderFactory.newDocumentBuilder();	
		} catch (Exception e) {
			Log.e(TAG, "Failed to start." + e);
			throw(e);
		}
	}
	
	private synchronized boolean collectState(ManifestParameters parameters) {
        if (state.receivers.isEmpty()) {
            Log.w(TAG, "Trying to collect states, while has no receivers yet");
            if (clientManifestFile.length() > 0) {
                Log.v(TAG, "Found staled state file, deleting it...");
                try {
                    clientManifestFile.delete();
                } catch (Exception e) {
                    Log.w(TAG, "Error deleting state file " + e);
                }
            }
            return true;
        }
		try {
			BufferedWriter clientManifestWriter = new BufferedWriter(new FileWriter(clientManifestFile));
			clientManifestWriter.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>");
			
			for (HashMap.Entry<String, ManifestReceiver> entry : state.receivers.entrySet()) {
				
				String manifestReceiverName = entry.getKey();
				ManifestReceiver manifestReceiver = entry.getValue();
				
				try {
					Log.v(TAG, "Collecting state from " + manifestReceiverName);
					String localReceiverUpdate = manifestReceiver.receiver.getState();
					if ((localReceiverUpdate != null) &&
						(localReceiverUpdate.equals("") == false)) {
						clientManifestWriter.write(localReceiverUpdate + "\n");
					} else {
						Log.w(TAG, manifestReceiverName + " returned no state.");
					}
				} catch (Exception e) {
					Log.e(TAG, "Problem collecting state from " + manifestReceiverName + ": " + e);
					continue;
				}
			}
			clientManifestWriter.write("</root>");
			clientManifestWriter.close();
			state.stateCollected = new Date();
			return true;
		} catch (IOException e) {
			return false;
		}
	}
	
	private synchronized boolean exchangeManifest(String manifestURL, ManifestParameters parameters) throws NoSuchAlgorithmException {

		String hash = "";
		URL url;
        HttpURLConnection connection;
		
        /* check network availability */
		try {
			if (((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo().isConnected() == false) {	
				Log.v(TAG, "Network not connected.");
				return false;
			}
		} catch (NullPointerException e) {
			Log.v(TAG, "No network connection.");
			return false;
		}

        /* generate server url */
        try {
            url = new URL(manifestURL + Util.getVersionName(context) + "/" + Util.getDeviceID(context));
        } catch (Exception e) {
            Log.e(TAG, "Unable to generate manifest URL: " + e);
            return false;
        }

        /* set up the connection */
        try {
            connection = Util.getConnection(url, true, false, 0, CONNECTION_TIMEOUT_SEC);
        } catch (Exception e) {
            Log.e(TAG, "Unable to connect to " + url + ": " + e);
            return false;
        }

        /* try to upload local state file if it's not empty */
        if (clientManifestFile.length() > 0) {
            try {
                Util.copyFile(new BufferedInputStream(new FileInputStream(clientManifestFile)), new GZIPOutputStream(new BufferedOutputStream(connection.getOutputStream())));
            } catch (Exception e) {
                Log.e(TAG, "Unable to upload state to " + url + ": " + e);
                if (manifestURL.equals(BACKUP_MANIFEST_URL) == false) {
                    fallbackManifestCount++;
                }
                return false;
            }

            Log.v(TAG, "Uploaded state with length " + clientManifestFile.length() + " to " + url);
        }

        try {
            Util.copyFile(new BufferedInputStream(connection.getInputStream()), new BufferedOutputStream(new FileOutputStream(newManifestFile)));
            connection.disconnect();			
            if (newManifestFile.exists() && (newManifestFile.length() != 0)) {
                Util.copyFile(newManifestFile, serverManifestFile);	
                hash = Util.hashFile(serverManifestFile);
                Log.i(TAG, "Retrieved manifest with length " + serverManifestFile.length() + " and hash " + hash + " from " + url);
            } else {
                Log.e(TAG, "Manifest cannot be saved or has zero length.");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to download manifest from " + url + ": " + e);
            if (manifestURL.equals(BACKUP_MANIFEST_URL) == false) {
                fallbackManifestCount++;
            }
            return false;
        }

        if (manifestURL.equals(BACKUP_MANIFEST_URL) == false) {
            fallbackManifestCount = 0;
        }

        state.downloadedManifest = new Date();
        boolean sameFile = (hash.equals(serverManifestHash));
        if (sameFile) {
            Log.v(TAG, "Downloaded manifest is identical.");
        } else {
            state.newManifest = new Date();
            Log.v(TAG, "Downloaded manifest has changed: " + serverManifestHash + " v. " + hash);
        }

        serverManifestHash = hash;
        return (!(sameFile));
    }

    private synchronized boolean distributeManifest(boolean reparseManifest, ManifestParameters parameters) throws TransformerConfigurationException, TransformerFactoryConfigurationError {

        if ((manifestDocument == null) ||
                (reparseManifest == true)) {
            BufferedInputStream manifestInputStream;
            try {
                manifestInputStream = new BufferedInputStream(new FileInputStream(serverManifestFile));
                manifestDocument = manifestBuilder.parse(manifestInputStream);
            } catch (Exception e) {
                Log.e(TAG, "Unable to parse and distribute manifest: " + e);
                return false;
            }
                }

        XPath manifestXPath = XPathFactory.newInstance().newXPath();

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        for (HashMap.Entry<String, ManifestReceiver> entry : state.receivers.entrySet()) {

            String receiverName = entry.getKey();
            ManifestReceiver manifestReceiver = entry.getValue();

            Log.i(TAG, "Looking for " + receiverName + " in receive hash.");
            String classNamePattern = "/manifest/" + receiverName;
            try {
                Node newReceiverNode = (Node) manifestXPath.evaluate(classNamePattern, manifestDocument, XPathConstants.NODE);
                if (newReceiverNode == null) {
                    Log.w(TAG, "No manifest entry for " + receiverName + ".");
                    continue;
                }
                newReceiverNode.normalize();

                boolean nodeChanged = false;

                if (manifestReceiver.node == null) {
                    Log.v(TAG, "No previous record for " + receiverName + ".");
                    nodeChanged = true;
                } else if (parameters.compareNodes == false) {
                    Log.v(TAG, "Updates forced for " + receiverName + " regardless of node similarity.");
                    nodeChanged = true;
                } else if (newReceiverNode.isEqualNode(manifestReceiver.node) == false) {
                    Log.v(TAG, "Updates due to changes in manifest for " + receiverName + ".");
                    nodeChanged = true;
                }

                if (nodeChanged == true) {

                    Log.v(TAG, "Manifest for " + receiverName + " has changed. Updating.");
                    manifestReceiver.node = newReceiverNode;
                    entry.setValue(manifestReceiver);

                    DOMSource domSource = new DOMSource(manifestReceiver.node);
                    StringWriter writer = new StringWriter();
                    StreamResult result = new StreamResult(writer);
                    transformer.transform(domSource, result);
                    try {
                        manifestReceiver.updateTime = new Date();
                        manifestReceiver.receiver.parametersUpdated(writer.toString());
                    } catch (Exception e) {
                        Log.e(TAG, "Remote update for " + receiverName + " generated exception: " + e);
                    }
                } else {
                    Log.v(TAG, "Manifest for " + receiverName + " is unchanged.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception occured during manifest distribution: " + e);
                continue;
            }
        }
        return true;
    }

    public synchronized void receiveManifestUpdates(ManifestClient receiver, String key) {

        if (state.receivers.containsKey(key) == true) {
            Log.e(TAG, "Duplicate manifest receiver. Ignoring.");
            return;
        }

        ManifestReceiver manifestReceiver = new ManifestReceiver(receiver, null);
        state.receivers.put(key, manifestReceiver);

        synchronized (parameterLock) {
            try {
                distributeManifest(false, parameters);
            } catch (Exception e) {
                Log.e(TAG, "Distributing manifest in receiveManifestUpdates generated an exception: " + e);
            }
        }

        Log.v(TAG, "Registered " + key + " for manifest updates.");
    }

    public synchronized void discardManifestUpdates(String key) {
        if (state.receivers.containsKey(key) == false) {
            Log.e(TAG, "Manifest receiver not registered. Ignoring.");
            return;
        }

        state.receivers.remove(key);
        Log.v(TAG, "Removed " + key + " for manifest updates.");
    }

    @Override
    public synchronized String getState() {
        state.now = new Date();
        if (state.versionName.equals("")) {
            try {
                state.versionName = Util.getVersionName(context);
                state.versionCode = Util.getVersionCode(context);
            } catch (Exception e) {
                Log.e(TAG, "Exception trying to get version: " + e);
            }
        }
        return super.getState();
    }

    @Override
    public ManifestParameters newParameters() {
        return new ManifestParameters();
    }

    @Override
    public ManifestParameters newParameters(ManifestParameters parameters) {
        return new ManifestParameters(parameters);
    }

    @Override
    public Class<ManifestParameters> parameterClass() {
        return ManifestParameters.class;
    }

    @Override
    public ManifestState newState() {
        return new ManifestState();
    }
}

@Root(name="ManifestReceiver")
class ManifestReceiver {
    @Element
    String receiverName;

    @Element
    Date updateTime;

    public ManifestClient receiver;
    public Node node;
    public ManifestReceiver(ManifestClient receiver, Node node) {
        this.updateTime = new Date(0L);
        this.receiver = receiver;
        this.receiverName = receiver.getClass().getName();
        this.node = node;
    }
}

@Root(name="ManifestService")
class ManifestParameters extends PeriodicParameters {

    @Element
    public String manifestURL;

    @Element
    public Boolean compareFiles;

    @Element
    public Boolean compareNodes;

    // 08 Nov 2012 : No longer used as of 1.1.12 due to reliability issues.

    @Element(required=false)
    public Boolean chunkedTransferMode;

    // 08 Nov 2012 : No longer used as of 1.1.12 due to reliability issues.

    @Element(required=false)
    public Integer chunkSizeKB;

    public ManifestParameters() {
        super();
        checkInterval = AlarmManager.INTERVAL_HALF_HOUR / 1000L;
        manifestURL = "http://backend.phone-lab.org/manifest/";
        compareFiles = true;
        compareNodes = true;
        chunkedTransferMode = false;
        chunkSizeKB = 32;
    }

    public ManifestParameters(ManifestParameters parameters) {
        super(parameters);
        manifestURL = parameters.manifestURL;
        compareFiles = parameters.compareFiles;
        compareNodes = parameters.compareNodes;
        chunkedTransferMode = parameters.chunkedTransferMode;
        chunkSizeKB = parameters.chunkSizeKB;
    }

    @Override
    public String toString() {
        return "ManifestParameters [manifestURL=" + manifestURL
            + ", compareFiles=" + compareFiles + ", compareNodes="
            + compareNodes + "]";
    }
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result
            + ((compareFiles == null) ? 0 : compareFiles.hashCode());
        result = prime * result
            + ((compareNodes == null) ? 0 : compareNodes.hashCode());
        result = prime * result
            + ((manifestURL == null) ? 0 : manifestURL.hashCode());
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
        ManifestParameters other = (ManifestParameters) obj;
        if (compareFiles == null) {
            if (other.compareFiles != null)
                return false;
        } else if (!compareFiles.equals(other.compareFiles))
            return false;
        if (compareNodes == null) {
            if (other.compareNodes != null)
                return false;
        } else if (!compareNodes.equals(other.compareNodes))
            return false;
        if (manifestURL == null) {
            if (other.manifestURL != null)
                return false;
        } else if (!manifestURL.equals(other.manifestURL))
            return false;
        return true;
    }
}

@Root(name="ManifestService")
class ManifestState extends PeriodicState {

    @Element
    public Date now;

    @Element
    public Date downloadedManifest;

    @Element
    public Date newManifest;

    @Element
    public Date stateCollected;

    @Element
    public String versionName;

    @Element
    public String versionCode;

    @ElementMap
    public HashMap<String, ManifestReceiver> receivers;

    public ManifestState() {
        super();
        now = new Date(0L);
        downloadedManifest = new Date(0L);
        newManifest = new Date(0L);
        stateCollected = new Date(0L);
        receivers = new HashMap<String, ManifestReceiver>();
        versionName = versionCode = "";
    }
}
