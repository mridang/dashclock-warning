package com.mridang.warning;

import java.util.HashSet;
import java.util.Random;

import org.acra.ACRA;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;
import com.stericson.RootTools.RootTools;

/*
 * This class is the main class that provides the widget
 */
public class WarningWidget extends DashClockExtension {

	/* This is the launch intent using for starting the application */
	Intent ittApplication;
	/* This is the instance of the settings storage of the application */
	SharedPreferences speSettings;

	/*
	 * @see
	 * com.google.android.apps.dashclock.api.DashClockExtension#onInitialize
	 * (boolean)
	 */
	@Override
	protected void onInitialize(boolean booReconnect) {

		Log.d("WarningWidget", "Initializing");
		if (RootTools.isRootAvailable() && RootTools.isAccessGiven()) {

			speSettings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

			try {

				Log.d("WarningWidget", "Getting the launch intent for the application");
				ittApplication = new Intent(this, WarningActivity.class);
				ittApplication.addCategory(Intent.CATEGORY_LAUNCHER);

			} catch (Exception e) {
				Log.e("WarningWidget", "Error getting the launch intent for application", e);
				return;
			}

			Log.d("WarningWidget", "Checking if the service is running");
			ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
			for (RunningServiceInfo rsiService : manager.getRunningServices(Integer.MAX_VALUE)) {

				if (WarningService.class.getName().equals(rsiService.service.getClassName())) {
					super.onInitialize(booReconnect);
					return;
				}

			}

			Log.d("WarningWidget", "Starting the service since it isn't running");
			getApplicationContext().startService(new Intent(getApplicationContext(), WarningService.class));
			super.onInitialize(booReconnect);

		} else {

			Log.w("WarningWidget", "The device is not rooted or root access was denied");
			Toast.makeText(getApplicationContext(), R.string.unrooted_error, Toast.LENGTH_LONG).show();
			return;

		}

	}

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onCreate()
	 */
	public void onCreate() {

		super.onCreate();
		Log.d("WarningWidget", "Created");
		ACRA.init(new AcraApplication(getApplicationContext()));

	}

	/*
	 * @see
	 * com.google.android.apps.dashclock.api.DashClockExtension#onUpdateData
	 * (int)
	 */
	@Override
	protected void onUpdateData(int intReason) {

		Log.d("WarningWidget", "Calculating the number of warnings and failures");
		final ExtensionData edtInformation = new ExtensionData();
		setUpdateWhenScreenOn(true);

		try {

			Integer intFailures = speSettings.getStringSet("failure", new HashSet<String>()).size();
			Integer intWarnings = speSettings.getStringSet("warning", new HashSet<String>()).size();

			if (intFailures + intWarnings > 0) {

				Log.d("WarningWidget", String.format("Found %d warnings and %d failures", intWarnings, intFailures));
				String strFailures = getResources().getQuantityString(R.plurals.failure, intFailures, intFailures);
				String strWarnings = getResources().getQuantityString(R.plurals.warning, intWarnings, intWarnings);
				String strBookmark = DateUtils
						.formatDateTime(this, speSettings.getLong("bookmark", Long.MIN_VALUE), 17);

				edtInformation.clickIntent(ittApplication);
				edtInformation.expandedBody(getString(R.string.body, strBookmark));
				edtInformation.expandedTitle(getString(R.string.title, strFailures, strWarnings));
				edtInformation.status(String.valueOf(intFailures + intWarnings));
				edtInformation.visible(true);

			} else {

				Log.d("WarningWidget", "Found no warnings or failures");
				edtInformation.visible(false);

			}

			if (new Random().nextInt(5) == 0 && !(0 != (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE))) {

				PackageManager mgrPackages = getApplicationContext().getPackageManager();

				try {

					mgrPackages.getPackageInfo("com.mridang.donate", PackageManager.GET_META_DATA);

				} catch (NameNotFoundException e) {

					Integer intExtensions = 0;
					Intent ittFilter = new Intent("com.google.android.apps.dashclock.Extension");
					String strPackage;

					for (ResolveInfo info : mgrPackages.queryIntentServices(ittFilter, 0)) {

						strPackage = info.serviceInfo.applicationInfo.packageName;
						intExtensions = intExtensions + (strPackage.startsWith("com.mridang.") ? 1 : 0);

					}

					if (intExtensions > 1) {

						edtInformation.visible(true);
						edtInformation.clickIntent(new Intent(Intent.ACTION_VIEW).setData(Uri
								.parse("market://details?id=com.mridang.donate")));
						edtInformation.expandedTitle("Please consider a one time purchase to unlock.");
						edtInformation
								.expandedBody("Thank you for using "
										+ intExtensions
										+ " extensions of mine. Click this to make a one-time purchase or use just one extension to make this disappear.");
						setUpdateWhenScreenOn(true);

					}

				}

			} else {
				setUpdateWhenScreenOn(true);
			}

		} catch (Exception e) {
			edtInformation.visible(true);
			Log.e("WarningWidget", "Encountered an error", e);
			ACRA.getErrorReporter().handleSilentException(e);
		}

		edtInformation.icon(R.drawable.ic_dashclock);
		publishUpdate(edtInformation);
		Log.d("WarningWidget", "Done");

	}

	/*
	 * @see com.google.android.apps.dashclock.api.DashClockExtension#onDestroy()
	 */
	public void onDestroy() {

		super.onDestroy();
		Log.d("WarningWidget", "Destroyed");

	}

}