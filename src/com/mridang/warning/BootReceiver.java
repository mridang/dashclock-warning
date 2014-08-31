package com.mridang.warning;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Broadcast receiver class to help start the logcat monitoring service when the
 * phone boots up
 */
public class BootReceiver extends BroadcastReceiver {

	/**
	 * Receiver method for the phone bootup that starts the logcat monitoring
	 * service
	 */
	@Override
	public void onReceive(Context ctxContext, Intent ittIntent) {
		ctxContext.startService(new Intent(ctxContext, WarningService.class));
	}

}