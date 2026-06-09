/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.Utils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Open-source kiosk (single-app / COSU) implementation.
 *
 * In the original free version these were stubs (the strict kiosk lived in the
 * closed Pro module). Here we implement strict single-app mode using Android's
 * Lock Task Mode, which requires the launcher to be the Device Owner.
 *
 * Behaviour: when kiosk mode is enabled in the server config and a "main app"
 * is set, the launcher whitelists that app for lock task and launches it pinned
 * to the screen. The user cannot leave the app, open Recents, or reach the
 * launcher menu. Home / Recents / Notifications etc. are toggled by the
 * server-side kiosk flags (kioskHome, kioskRecents, ...).
 */
public class ProUtils {

    public static boolean isPro() {
        return false;
    }

    private static DevicePolicyManager dpm(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ServerConfig config(Context context) {
        SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
        return settingsHelper != null ? settingsHelper.getConfig() : null;
    }

    /** Kiosk is required when it's enabled in the config and a main app is set. */
    public static boolean kioskModeRequired(Context context) {
        ServerConfig config = config(context);
        if (config == null || !config.isKioskMode()) {
            return false;
        }
        String mainApp = config.getMainApp();
        return mainApp != null && mainApp.trim().length() > 0;
    }

    public static void initCrashlytics(Context context) {
        // Stub
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Stub
    }

    // Start the service checking if the foreground app is allowed to the user (by usage statistics)
    public static boolean checkAccessibilityService(Context context) {
        // Stub
        return true;
    }

    // Pro-version
    public static boolean checkUsageStatistics(Context context) {
        // Stub
        return true;
    }

    // Add a transparent view on top of the status bar which prevents user interaction with the status bar
    public static View preventStatusBarExpansion(Activity activity) {
        // Not needed in lock-task mode: the status bar pulldown is blocked by the
        // system while the device is pinned (see updateKioskOptions / lock task features).
        return null;
    }

    // Add a transparent view on top of a swipeable area at the right (opens app list on Samsung tablets)
    public static View preventApplicationsList(Activity activity) {
        // Not needed in lock-task mode (Recents/overview is blocked by lock task features).
        return null;
    }

    public static View createKioskUnlockButton(Activity activity) {
        // Strict kiosk has no on-screen exit button. Exit is controlled by the
        // server (disable kiosk in the config) which triggers unlockKiosk().
        return null;
    }

    public static boolean isKioskAppInstalled(Context context) {
        ServerConfig config = config(context);
        if (config == null) {
            return false;
        }
        String kioskApp = config.getMainApp();
        if (kioskApp == null || kioskApp.trim().length() == 0) {
            return false;
        }
        if (kioskApp.equals(context.getPackageName())) {
            return true;
        }
        return context.getPackageManager().getLaunchIntentForPackage(kioskApp) != null;
    }

    public static boolean isKioskModeRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            // minSdk is 26, getLockTaskModeState() is available since API 23.
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        if (kioskApp == null || kioskApp.trim().length() == 0) {
            return null;
        }
        return activity.getPackageManager().getLaunchIntentForPackage(kioskApp);
    }

    // Start COSU kiosk mode: pin the main app to the screen via Lock Task.
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        try {
            if (!Utils.isDeviceOwner(activity)) {
                Log.e(Const.LOG_TAG, "startCosuKioskMode: launcher is not Device Owner, cannot enforce kiosk");
                return false;
            }

            // 1. Whitelist the apps allowed in lock task and apply the feature flags.
            updateKioskAllowedApps(kioskApp, activity, enableSettings);
            updateKioskOptions(activity);

            // 2. Launch the kiosk app pinned to the screen.
            Intent launchIntent = getKioskAppIntent(kioskApp, activity);
            if (launchIntent == null) {
                Log.e(Const.LOG_TAG, "startCosuKioskMode: no launch intent for " + kioskApp);
                return false;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+: a Device Owner can start ANY whitelisted app directly in lock task,
                // even a third-party app that doesn't call startLockTask() itself.
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLockTaskEnabled(true);
                Bundle bundle = options.toBundle();
                activity.startActivity(launchIntent, bundle);
            } else {
                // API 26-27: setLockTaskEnabled is unavailable. We can only launch the app;
                // strict pinning of a third-party app requires it to call startLockTask() itself.
                Log.w(Const.LOG_TAG, "startCosuKioskMode: API < 28, launching without lock-task pinning");
                activity.startActivity(launchIntent);
            }
            return true;
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "startCosuKioskMode failed: " + e.getMessage(), e);
            return false;
        }
    }

    // Set/update kiosk mode options (lock task features) from the server config flags.
    public static void updateKioskOptions(Activity activity) {
        try {
            if (!Utils.isDeviceOwner(activity)) {
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // setLockTaskFeatures is API 28+; on older versions the defaults apply.
                return;
            }
            DevicePolicyManager dpm = dpm(activity);
            ComponentName admin = LegacyUtils.getAdminComponentName(activity);
            if (dpm == null || admin == null) {
                return;
            }
            ServerConfig config = config(activity);

            int flags = 0;
            if (config != null) {
                if (Boolean.TRUE.equals(config.getKioskHome())) {
                    flags |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
                }
                if (Boolean.TRUE.equals(config.getKioskRecents())) {
                    flags |= DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
                }
                if (Boolean.TRUE.equals(config.getKioskNotifications())) {
                    flags |= DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
                }
                if (Boolean.TRUE.equals(config.getKioskSystemInfo())) {
                    flags |= DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
                }
                if (Boolean.TRUE.equals(config.getKioskKeyguard())) {
                    flags |= DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD;
                }
                // Keep the power long-press (global actions) menu available unless the
                // admin explicitly locked the hardware buttons.
                if (!Boolean.TRUE.equals(config.getKioskLockButtons())) {
                    flags |= DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
                }
            } else {
                flags = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
            }

            dpm.setLockTaskFeatures(admin, flags);
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "updateKioskOptions failed: " + e.getMessage(), e);
        }
    }

    // Update the list of apps allowed in the kiosk (lock task) mode.
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        try {
            if (!Utils.isDeviceOwner(activity)) {
                return;
            }
            DevicePolicyManager dpm = dpm(activity);
            ComponentName admin = LegacyUtils.getAdminComponentName(activity);
            if (dpm == null || admin == null) {
                return;
            }

            List<String> packages = new ArrayList<>();
            // The launcher itself must stay whitelisted: it is the HOME app and
            // relaunches the kiosk app if it ever exits.
            packages.add(activity.getPackageName());
            if (kioskApp != null && kioskApp.trim().length() > 0 && !packages.contains(kioskApp)) {
                packages.add(kioskApp);
            }
            if (enableSettings) {
                // Allow the system settings (e.g. to fix a Wi-Fi connection error in kiosk).
                packages.add("com.android.settings");
            }

            dpm.setLockTaskPackages(admin, packages.toArray(new String[0]));
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "updateKioskAllowedApps failed: " + e.getMessage(), e);
        }
    }

    public static void unlockKiosk(Activity activity) {
        try {
            if (isKioskModeRunning(activity)) {
                try {
                    activity.stopLockTask();
                } catch (Exception ignored) {
                    // stopLockTask() works only from within the pinned task; ignore if it throws.
                }
            }
            // As Device Owner, clearing the whitelist releases any pinned app.
            if (Utils.isDeviceOwner(activity)) {
                DevicePolicyManager dpm = dpm(activity);
                ComponentName admin = LegacyUtils.getAdminComponentName(activity);
                if (dpm != null && admin != null) {
                    dpm.setLockTaskPackages(admin, new String[]{});
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(Const.LOG_TAG, "unlockKiosk failed: " + e.getMessage(), e);
        }
    }

    public static void processConfig(Context context, ServerConfig config) {
        // Stub
    }

    public static void processLocation(Context context, Location location, String provider) {
        // Stub
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + context.getString(R.string.vendor);
    }
}
