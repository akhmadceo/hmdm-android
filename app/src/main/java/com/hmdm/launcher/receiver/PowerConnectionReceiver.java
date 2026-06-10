/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Kiosk power management: when the device is on a permanent power cable,
 * keep the screen on while power is supplied and turn it off the moment
 * the cable stops delivering power.
 *
 * - ACTION_POWER_CONNECTED   -> wake the screen (STAY_ON_WHILE_PLUGGED_IN keeps it on)
 * - ACTION_POWER_DISCONNECTED -> DevicePolicyManager.lockNow() turns the screen off immediately
 *
 * Gated by the server-side "kioskScreenOn" flag and only active while the
 * single-app kiosk is running.
 */

package com.hmdm.launcher.receiver;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.service.ScreenLockAccessibilityService;

public class PowerConnectionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }

        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        ServerConfig config = settingsHelper.getConfig();

        // Only manage the screen by power state when kioskScreenOn is enabled
        // and the kiosk (single-app) mode is actually running.
        boolean screenManaged = config != null
                && config.getKioskScreenOn() != null
                && config.getKioskScreenOn()
                && ProUtils.isKioskModeRunning(context);
        if (!screenManaged) {
            return;
        }

        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            Log.d(Const.LOG_TAG, "Power disconnected: turning the screen off");
            // Preferred: accessibility GLOBAL_ACTION_LOCK_SCREEN (works in lock-task mode,
            // equivalent to pressing the power button). Falls back to lockNow() when the
            // accessibility service is not enabled.
            if (!ScreenLockAccessibilityService.lockScreen()) {
                try {
                    DevicePolicyManager dpm =
                            (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                    if (dpm != null) {
                        dpm.lockNow();
                    }
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "lockNow() failed: " + e.getMessage());
                }
            }
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            Log.d(Const.LOG_TAG, "Power connected: turning the screen back on");
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    PowerManager.WakeLock wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                            "com.hmdm.launcher:powerWakeLock"
                    );
                    wl.acquire(1000); // Wake for ~1 second; STAY_ON_WHILE_PLUGGED_IN keeps it on afterwards
                    wl.release();
                }
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "Screen wake failed: " + e.getMessage());
            }
        }
    }
}
