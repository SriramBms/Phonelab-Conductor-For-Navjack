package edu.buffalo.cse.phonelab.harness.lib.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPOutputStream;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.telephony.TelephonyManager;

public class Util {
	
	public static final int UPLOAD_READ_TIMEOUT_SEC = 10;
	
	public static void copyFile(File in, File out) throws FileNotFoundException, IOException {
		copyFile(new FileInputStream(in), new FileOutputStream(out));
	}
	
	public static Integer copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[4096];
		Integer count;
		Integer totalCount = 0;
		while ((count = in.read(buffer)) > 0) {
			out.write(buffer, 0, count);
			totalCount += count;
		}
		in.close();
		out.flush();
		out.close();
		return totalCount;
	}
	
	public static String hashFile(File f) throws IOException, NoSuchAlgorithmException {
		return hashFile(new BufferedInputStream(new FileInputStream(f)));
	}
	
	public static String hashFile(InputStream in) throws IOException, NoSuchAlgorithmException {
		MessageDigest digester = MessageDigest.getInstance("SHA-1");
		byte[] buffer = new byte[8192];
		int count;
		while ((count = in.read(buffer)) > 0) {
			digester.update(buffer, 0, count);
		}
		in.close();
		byte[] digest = digester.digest();
		return new BigInteger(1, digest).toString(16);
	}
	
	private static boolean getDeviceIDDone = false;
	private static String deviceID;
	public static String getDeviceID(Context context) throws NoSuchAlgorithmException {
		
		if (getDeviceIDDone == true) {
			return deviceID;
		}
		
		TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
		
		MessageDigest digester = MessageDigest.getInstance("SHA-1");
		byte[] digest = digester.digest(telephonyManager.getDeviceId().getBytes());
		deviceID = (new BigInteger(1, digest)).toString(16);
		getDeviceIDDone = true;
		return deviceID;
	}
	public static String getVersionName(Context context) throws NameNotFoundException {
		return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
	}
	public static String getVersionCode(Context context) throws NameNotFoundException {
		return Integer.toString(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
	}
    public static HttpURLConnection getConnection(URL to, boolean gzip, boolean chunkedTransferMode, int chunkSizeKB, int connectionTimeoutSec) throws IOException {
        HttpURLConnection connection;	
        connection = (HttpURLConnection) to.openConnection();
        connection.setDoOutput(true);
        if (chunkedTransferMode) {
            connection.setChunkedStreamingMode(chunkSizeKB * 1024);
        }
        connection.setUseCaches(false);
        connection.setConnectTimeout(connectionTimeoutSec * 1000);
        connection.setReadTimeout(UPLOAD_READ_TIMEOUT_SEC * 1000);

        return connection;
    }
    public static HttpURLConnection upload(URL to, InputStream from, boolean gzip, boolean chunkedTransferMode, int chunkSizeKB, int connectionTimeoutSec) throws IOException {
        HttpURLConnection connection = getConnection(to, gzip, chunkedTransferMode, chunkSizeKB, connectionTimeoutSec);
        OutputStream outputStream = new BufferedOutputStream(connection.getOutputStream());
        if (gzip == true) {
            outputStream = new GZIPOutputStream(outputStream);
        }

        Util.copyFile(from, outputStream);
        return connection;
    }
}
