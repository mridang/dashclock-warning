package com.mridang.warning;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.acra.ACRA;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Main service class that monitors the logcat chatter and updates the
 * notification
 */
@SuppressLint("SimpleDateFormat")
public class WarningService extends Service {

	/* The background worker thread that processes the logs */
	private Thread thrWorker;

	/**
	 * This methods creates a 32bit SHA1 checksum of the of the input string. It
	 * is used to uniquely identify stack traces
	 * 
	 * @param input The string representation of the stack trace whose checksum
	 *            needs to be calculated
	 * @return The 32bit SHA1 checksum of the string
	 * @throws NoSuchAlgorithmException
	 */
	public static String hashString(String strInput) throws NoSuchAlgorithmException {

		strInput = strInput.replaceFirst("^(.*?):.*?\\n", "$1\n");
		MessageDigest shaDigest = MessageDigest.getInstance("SHA1");
		shaDigest.reset();
		shaDigest.update(strInput.getBytes());
		byte[] bytBytes = shaDigest.digest();

		String strHash = "";
		for (int i = 0; i < bytBytes.length; i++) {
			strHash += Integer.toString((bytBytes[i] & 0xff) + 0x100, 16).substring(1);
		}
		return strHash;

	}

	/**
	 * The background worker runnable that is run in a separate thread. This
	 * runnable starts the logcat process and begins reading the output
	 */
	private class BackgroundThread implements Runnable {

		/* The instance of the manager of the notification services */
		private final NotificationManager mgrNotifications;
		/* The instance of the notification builder to rebuild the notification */
		private Builder notBuilder;
		/* The instance of the shared preferences */
		private final SharedPreferences speSettings;
		/* The instance of the logcat process */
		private final Process proLogcat;
		/* The instance of the task scheduler */
		private final Timer tmrDelay;
		/* The instance of the buffered reader */
		private final BufferedReader bufLogcat;

		/**
		 * Constructor for the runnable that initializes the shared preferences,
		 * the background process, the interval timer, the notification manager
		 * and the buffered reader.
		 * 
		 * @throws IOException When the buffered reader cannot be initialized
		 */
		public BackgroundThread() throws IOException {

			Log.d("WarningService", "Initializing the background worker thread");
			mgrNotifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			speSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

			List<String> lstArguments = new ArrayList<String>();
			lstArguments.add("su");
			lstArguments.add("-c");
			lstArguments.add("logcat -v time *:W");

			Intent ittSettings = new Intent(getApplicationContext(), WarningActivity.class);
			ittSettings.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
			PendingIntent pitSettings = PendingIntent.getActivity(WarningService.this, 0, ittSettings, 0);

			notBuilder = new Builder(WarningService.this);
			notBuilder = notBuilder.setSmallIcon(R.drawable.ic_notification);
			notBuilder = notBuilder.setContentIntent(pitSettings);
			notBuilder = notBuilder.setOngoing(false);
			notBuilder = notBuilder.setContentTitle(getString(R.string.notify));
			notBuilder = notBuilder.setContentText(getString(R.string.text));
			notBuilder = notBuilder.setOnlyAlertOnce(true);

			ProcessBuilder bldLogcat = new ProcessBuilder(lstArguments);
			mgrNotifications.cancel(12442);
			bldLogcat.redirectErrorStream(true);
			proLogcat = bldLogcat.start();
			bufLogcat = new BufferedReader(new InputStreamReader(proLogcat.getInputStream()));
			tmrDelay = new Timer();

		}

		/**
		 * Called when the service is started. This method starts the logcat
		 * process and begins reading the log chatter and processing them. It
		 * only monitors error and warning chatter and looks for stack traces
		 * in the stream of chatter.
		 */
		@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
		@Override
		public void run() {

			Log.i("WarningService", "Starting the background worker thread");

			try {

				Date datBookmark = new Date(speSettings.getLong("bookmark", 0L));
				Log.d("WarningService", "Bookmark was placed at " + datBookmark);

				final Set<String> setWarnings = speSettings.getStringSet("warning", new HashSet<String>());
				final Set<String> setFailures = speSettings.getStringSet("failure", new HashSet<String>());
				final Map<String, StringBuffer> mapBuffer = new HashMap<String, StringBuffer>();
				final Map<String, String> mapRecent = new HashMap<String, String>();
				final List<String> lstOccurrences = new ArrayList<String>();

				// /^.*(pid|killing).(\d{2,5}).*$/igm
				final SimpleDateFormat sdfFormat = new SimpleDateFormat("MM-dd kk:mm:ss.SSS");
				final Pattern patProcess = Pattern.compile("\\(\\s*([0-9]+)\\):");

				Integer intLength = 1000;
				String strLine = "";
				while ((strLine = bufLogcat.readLine()) != null && !Thread.interrupted()) {

					try {

						final Matcher matProcess = patProcess.matcher(strLine);
						if (!matProcess.find()) {
							continue;
						}

						final String strProcess = matProcess.group(1);
						if (strLine.contains("\tat ") || strLine.contains("Caused by: ") || strLine.contains("\t... ")) {

							if (mapRecent.containsKey(strProcess)) {

								final String strRecent = mapRecent.remove(strProcess);
								String strMessage = strLine.substring(strLine.indexOf(":", 18) + 1);

								StringBuffer sbfBuffer = new StringBuffer(intLength);
								sbfBuffer.append(strRecent.substring(strLine.indexOf(":", 18) + 1) + "\n");
								sbfBuffer.append(strMessage.replace("\t", "  ") + "\n");
								mapBuffer.put(strProcess, sbfBuffer);
								tmrDelay.schedule(new TimerTask() {

									@SuppressWarnings("deprecation")
									@Override
									public synchronized void run() {

										try {

											String strDate = strRecent.substring(0, 18);
											Calendar calCurrent = Calendar.getInstance();
											Calendar calParsed = Calendar.getInstance();
											calParsed.setTime(sdfFormat.parse(strDate));
											calParsed.set(Calendar.YEAR, calCurrent.get(Calendar.YEAR));

											if (calParsed.after(calCurrent)) {
												calParsed.set(Calendar.YEAR, calParsed.get(Calendar.YEAR) - 1);

											}

											if (!mapBuffer.containsKey(strProcess)) {
												return;
											}

											String strTrace = mapBuffer.get(strProcess).toString().trim();
											String strHash = hashString(strTrace);
											Long lngTimestamp = calParsed.getTime().getTime();

											if (lngTimestamp < speSettings.getLong("bookmark", 0L)) {

												Date datSkipped = new Date(lngTimestamp);
												Log.d("WarningService", "Skipping trace at " + datSkipped);
												return;

											}

											if (strRecent.substring(19, 20).equalsIgnoreCase("E")) {

												if (!setFailures.contains(strTrace)) {
													setFailures.add(strTrace);
												}

											} else {

												if (!setWarnings.contains(strTrace)) {
													setWarnings.add(strTrace);
												}

											}

											Set<String> setTimestamps = new HashSet<String>();
											setTimestamps = speSettings.getStringSet(strHash, setTimestamps);
											setTimestamps.add(lngTimestamp.toString());
											lstOccurrences.add(lngTimestamp.toString());

											Editor ediEditor = speSettings.edit();
											ediEditor.putStringSet("warning", setWarnings);
											ediEditor.putStringSet("failure", setFailures);
											ediEditor.putStringSet(strHash, setTimestamps);
											ediEditor.putLong("bookmark", lngTimestamp);
											ediEditor.commit();

											if (speSettings.getBoolean("notification", true)) {

												notBuilder.setWhen(new Date().getTime());
												notBuilder.setNumber(lstOccurrences.size());

												if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
													mgrNotifications.notify(12442, notBuilder.getNotification());
												} else {
													mgrNotifications.notify(12442, notBuilder.build());
												}

											}

											Log.d("WarningService", "Found an error at " + new Date(lngTimestamp));

										} catch (NoSuchAlgorithmException e) {
											Log.w("WarningService", "Unable to create a hash of the stacktrace");
										} catch (ParseException e) {
											Log.w("WarningService", "Unable to parse date from " + strRecent);
										} catch (Exception e) {
											Log.e("WarningService", "An unknown error occurred", e);
										} finally {

											StringBuffer sbfBuffer = mapBuffer.get(strProcess);
											if (sbfBuffer != null) {

												synchronized (sbfBuffer) {
													sbfBuffer.notify();
												}

											} else {
												Log.w("WarningService", "Buffer for " + strProcess + " was null");
											}

										}

									}

								}, 250);

								continue;

							}

							if (mapBuffer.containsKey(strProcess)) {

								String strMessage = strLine.substring(strLine.indexOf(":", 18) + 1);
								mapBuffer.get(strProcess).append(strMessage.replace("\t", "  ") + "\n");

							}
							continue;

						} else {

							StringBuffer sbfBuffer = mapBuffer.get(strProcess);
							if (sbfBuffer != null) {

								synchronized (sbfBuffer) {

									try {

										sbfBuffer.wait(1000);
										intLength = mapBuffer.remove(strProcess).length() + intLength / 2;

									} catch (InterruptedException e) {
										Log.w("WarningService", "Wait for " + strProcess + " was interrupted");
									}

								}

							}

							mapRecent.put(strProcess, strLine);

						}

					} catch (Exception e) {
						Log.w("WarningService", "An unknown exception occurred", e);
					}

				}

				Log.w("WarningService", "The process has finished or the service was stopped");

			} catch (IOException e) {
				Log.w("WarningService", "Unable to read the buffered stream properly");
			} finally {

				try {

					if (bufLogcat != null)
						bufLogcat.close();

				} catch (IOException ex) {
					Log.w("WarningService", "Unable to close the buffered stream cleanly");
				} finally {

					if (proLogcat != null)
						proLogcat.destroy();

					if (tmrDelay != null)
						tmrDelay.cancel();

				}

			}

			Log.d("WarningService", "Finishing the background worker thread");

		}

	}

	/**
	 * Initializes the service by getting instances of service managers and
	 * mainly setting up the receiver to receive all the necessary intents that
	 * this service is supposed to handle.
	 */
	@Override
	public void onCreate() {

		Log.i("WarningService", "Creating the logcat monitoring service");
		ACRA.init(new AcraApplication(getApplicationContext()));
		try {

			this.thrWorker = new Thread(new BackgroundThread());

		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	/**
	 * Called when the service is being started. This method simply starts the
	 * background worker thread.
	 * 
	 * @see android.app.Service#onStartCommand(android.content.Intent, int, int)
	 */
	public int onStartCommand(Intent ittIntent, int intFlags, int intId) {

		Log.i("WarningService", "Starting the logcat monitoring service");
		if (!this.thrWorker.isAlive()) {
			this.thrWorker.start();
		}
		return Service.START_STICKY;

	}

	/**
	 * Called when the service is being stopped. This method simply interrupts
	 * the background worker thread.
	 * 
	 * @see android.app.Service#onDestroy()
	 */
	@Override
	public void onDestroy() {

		Log.i("WarningService", "Stopping the logcat monitoring service");
		if (!this.thrWorker.isInterrupted()) {
			this.thrWorker.interrupt();
		}

	}

	/**
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent ittIntent) {

		return null;

	}

}