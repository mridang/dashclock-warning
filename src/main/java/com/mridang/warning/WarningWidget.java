package com.mridang.warning;

import android.annotation.TargetApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.dashclock.api.ExtensionData;
import com.stericson.RootTools.RootTools;

import org.acra.ACRA;

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

/*
 * This class is the main class that provides the widget
 */
public class WarningWidget extends ImprovedExtension {

	/**
	 * This methods creates a 32bit SHA1 checksum of the of the input string. It
	 * is used to uniquely identify stack traces
	 *
	 * @param strInput The string representation of the stack trace whose checksum needs to be calculated
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
		for (byte bytByte : bytBytes) {
			strHash += Integer.toString((bytByte & 0xff) + 0x100, 16).substring(1);
		}
		return strHash;

	}

	/**
	 * The background worker runnable that is run in a separate thread. This
	 * runnable starts the logcat process and begins reading the output
	 */
	private class BackgroundThread extends Thread {

		/**
		 * The instance of the manager of the notification services
		 */
		private final NotificationManager mgrNotifications;
		/**
		 * The instance of the notification builder to rebuild the notification
		 */
		private NotificationCompat.Builder notBuilder;
		/**
		 * The instance of the shared preferences
		 */
		private final SharedPreferences speSettings;
		/**
		 * The instance of the logcat process
		 */
		private final Process proLogcat;
		/**
		 * The instance of the task scheduler
		 */
		private final Timer tmrDelay;
		/**
		 * The instance of the buffered reader
		 */
		private final BufferedReader bufLogcat;
		/**
		 * The instance of the buffered reader
		 */
		private final Set<String> setWarnings;
		/**
		 * The instance of the buffered reader
		 */
		private final Set<String> setFailures;
		/**
		 * The instance of the buffered reader
		 */
		private final Map<String, StringBuffer> mapBuffer;
		/**
		 * The instance of the buffered reader
		 */
		private final Map<String, String> mapRecent;
		/**
		 * The instance of the buffered reader
		 */
		private final List<String> lstOccurrences;

		/**
		 * Constructor for the runnable that initializes the shared preferences,
		 * the background process, the interval timer, the notification manager
		 * and the buffered reader.
		 *
		 * @throws IOException When the buffered reader cannot be initialized
		 */
		public BackgroundThread() throws IOException {

			Log.d(getTag(), "Initializing the background worker thread");
			mgrNotifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			speSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
			setWarnings = speSettings.getStringSet("warning", new HashSet<String>());
			setFailures = speSettings.getStringSet("failure", new HashSet<String>());
			mapBuffer = new HashMap<>();
			mapRecent = new HashMap<>();
			lstOccurrences = new ArrayList<>();

			List<String> lstArguments = new ArrayList<>();
			lstArguments.add("su");
			lstArguments.add("-c");
			lstArguments.add("logcat -v time *:W");

			Intent ittSettings = new Intent(getApplicationContext(), WarningActivity.class);
			ittSettings.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
			PendingIntent pitSettings = PendingIntent.getActivity(getApplicationContext(), 0, ittSettings, 0);

			notBuilder = new NotificationCompat.Builder(getApplicationContext());
			notBuilder = notBuilder.setSmallIcon(R.drawable.ic_notification);
			notBuilder = notBuilder.setContentIntent(pitSettings);
			notBuilder = notBuilder.setOngoing(false);
			notBuilder = notBuilder.setContentTitle(getString(R.string.notify));
			notBuilder = notBuilder.setContentText(getString(R.string.text));
			notBuilder = notBuilder.setOnlyAlertOnce(true);
			notBuilder = notBuilder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
			notBuilder = notBuilder.setCategory(NotificationCompat.CATEGORY_SERVICE);

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

			Log.i(getTag(), "Starting the background worker thread");

			try {

				Date datBookmark = new Date(speSettings.getLong("bookmark", 0L));
				Log.d(getTag(), "Bookmark was placed at " + datBookmark);

				// /^.*(pid|killing).(\d{2,5}).*$/igm
				final SimpleDateFormat sdfFormat = new SimpleDateFormat("MM-dd kk:mm:ss.SSS");
				final Pattern patProcess = Pattern.compile("\\(\\s*([0-9]+)\\):");

				Integer intLength = 1000;
				String strLine;
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
								sbfBuffer.append(strRecent.substring(strLine.indexOf(":", 18) + 1)).append("\n");
								sbfBuffer.append(strMessage.replace("\t", "  ")).append("\n");
								mapBuffer.put(strProcess, sbfBuffer);
								tmrDelay.schedule(new TimerTask() {

									@SuppressWarnings({"deprecation", "SynchronizationOnLocalVariableOrMethodParameter"})
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
												Log.d(getTag(), "Skipping trace at " + datSkipped);
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

											Set<String> setTimestamps = new HashSet<>();
											setTimestamps = speSettings.getStringSet(strHash, setTimestamps);
											setTimestamps.add(lngTimestamp.toString());
											lstOccurrences.add(lngTimestamp.toString());

											SharedPreferences.Editor ediEditor = speSettings.edit();
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

											Log.d(getTag(), "Found an error at " + new Date(lngTimestamp));

										} catch (NoSuchAlgorithmException e) {
											Log.w(getTag(), "Unable to create a hash of the stacktrace");
										} catch (ParseException e) {
											Log.w(getTag(), "Unable to parse date from " + strRecent);
										} catch (Exception e) {
											Log.e(getTag(), "An unknown error occurred", e);
										} finally {

											@SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
											StringBuffer sbfBuffer = mapBuffer.get(strProcess);
											if (sbfBuffer != null) {

												synchronized (sbfBuffer) {
													sbfBuffer.notify();
												}

											} else {
												Log.w(getTag(), "Buffer for " + strProcess + " was null");
											}

										}

									}

								}, 250);

								continue;

							}

							if (mapBuffer.containsKey(strProcess)) {

								String strMessage = strLine.substring(strLine.indexOf(":", 18) + 1);
								mapBuffer.get(strProcess).append(strMessage.replace("\t", "  ")).append("\n");

							}

						} else {

							@SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
							StringBuffer sbfBuffer = mapBuffer.get(strProcess);
							if (sbfBuffer != null) {

								//noinspection SynchronizationOnLocalVariableOrMethodParameter
								synchronized (sbfBuffer) {

									try {

										sbfBuffer.wait(1000);
										intLength = mapBuffer.remove(strProcess).length() + intLength / 2;

									} catch (InterruptedException e) {
										Log.w(getTag(), "Wait for " + strProcess + " was interrupted");
									}

								}

							}

							mapRecent.put(strProcess, strLine);

						}

					} catch (Exception e) {
						Log.w(getTag(), "An unknown exception occurred", e);
					}

				}

				Log.w(getTag(), "The process has finished or the service was stopped");

			} catch (IOException e) {
				Log.w(getTag(), "Unable to read the buffered stream properly");
			} finally {

				try {

					bufLogcat.close();

				} catch (IOException ex) {
					Log.w(getTag(), "Unable to close the buffered stream cleanly");
				} finally {

					proLogcat.destroy();
					tmrDelay.cancel();

				}

			}

			Log.d(getTag(), "Finishing the background worker thread");

		}

		public void resetNotification() {

			setWarnings.clear();
			setFailures.clear();
			mapBuffer.clear();
			mapRecent.clear();
			lstOccurrences.clear();
			mgrNotifications.cancel(12442);

		}

	}

	/* The background worker thread that processes the logs */
	private BackgroundThread thrWorker;

	/*
	 * (non-Javadoc)
	 * @see com.mridang.warning.ImprovedExtension#getIntents()
	 */
	@Override
	protected IntentFilter getIntents() {

		return new IntentFilter("com.mridang.warning.ACTION_REFRESH");

	}

	/*
	 * (non-Javadoc)
	 * @see com.mridang.warning.ImprovedExtension#getTag()
	 */
	@Override
	protected String getTag() {
		return getClass().getSimpleName();
	}

	/*
	 * (non-Javadoc)
	 * @see com.mridang.warning.ImprovedExtension#getUris()
	 */
	@Override
	protected String[] getUris() {
		return null;
	}

	/*
	 * @see
	 * com.google.android.apps.dashclock.api.DashClockExtension#onInitialize
	 * (boolean)
	 */
	@Override
	protected void onInitialize(boolean booReconnect) {

		Log.d(getTag(), "Initializing");
		if (RootTools.isRootAvailable() && RootTools.isAccessGiven()) {

			Log.i(getTag(), "Starting the logcat monitoring service");
			if (!this.thrWorker.isAlive()) {
				this.thrWorker.start();
			}
			super.onInitialize(booReconnect);

		} else {

			Log.w(getTag(), "The device is not rooted or root access was denied");
			Toast.makeText(getApplicationContext(), R.string.unrooted_error, Toast.LENGTH_LONG).show();

		}

	}

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onCreate()
	 */
	public void onCreate() {

		super.onCreate();
		Log.d(getTag(), "Created");
		ACRA.init(new AcraApplication(getApplicationContext()));

		try {
			this.thrWorker = new BackgroundThread();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	/*
	 * @see
	 * com.google.android.apps.dashclock.api.DashClockExtension#onUpdateData
	 * (int)
	 */
	@Override
	protected void onUpdateData(int intReason) {

		Log.d(getTag(), "Calculating the number of warnings and failures");
		final ExtensionData edtInformation = new ExtensionData();
		setUpdateWhenScreenOn(true);

		try {

			Integer intFailures = getSet("failure", new HashSet<String>()).size();
			Integer intWarnings = getSet("warning", new HashSet<String>()).size();

			if (intFailures + intWarnings > 0) {

				Log.d(getTag(), String.format("Found %d warnings and %d failures", intWarnings, intFailures));
				String strFailures = getQuantityString(R.plurals.failure, intFailures, intFailures);
				String strWarnings = getQuantityString(R.plurals.warning, intWarnings, intWarnings);
				String strBookmark = DateUtils.formatDateTime(this, speSettings.getLong("bookmark", Long.MIN_VALUE), 17);

				edtInformation.clickIntent(new Intent(this, WarningActivity.class).addCategory(Intent.CATEGORY_LAUNCHER));
				edtInformation.expandedBody(getString(R.string.body, strBookmark));
				edtInformation.expandedTitle(getString(R.string.title, strFailures, strWarnings));
				edtInformation.status(String.valueOf(intFailures + intWarnings));
				edtInformation.visible(true);

			} else {

				Log.d(getTag(), "Found no warnings or failures");
				edtInformation.visible(false);

			}

		} catch (Exception e) {
			edtInformation.visible(true);
			Log.e(getTag(), "Encountered an error", e);
			ACRA.getErrorReporter().handleSilentException(e);
		}

		edtInformation.icon(R.drawable.ic_dashclock);
		doUpdate(edtInformation);

	}

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onDestroy()
	*/
	public void onDestroy() {

		Log.i(getTag(), "Stopping the logcat monitoring service");
		if (!this.thrWorker.isInterrupted()) {
			this.thrWorker.interrupt();
		}

		super.onDestroy();
		Log.d(getTag(), "Destroyed");

	}

	/*
	 * (non-Javadoc)
	 * @see com.mridang.warning.ImprovedExtension#onReceiveIntent(android.content.Context, android.content.Intent)
	 */
	@Override
	protected void onReceiveIntent(Context ctxContext, Intent ittIntent) {


		if (ittIntent.getAction().equalsIgnoreCase("com.mridang.warning.ACTION_REFRESH")) {

			Log.d(getTag(), "Refresh requested; hiding the notification");
			this.thrWorker.resetNotification();

		}

		onUpdateData(UPDATE_REASON_MANUAL);

	}

}