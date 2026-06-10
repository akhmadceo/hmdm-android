/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Minimal accessibility service used only to turn the screen off on demand
 * (GLOBAL_ACTION_LOCK_SCREEN). In lock-task / kiosk mode the keyguard is
 * disabled, so DevicePolicyManager.lockNow() does not turn the display off;
 * the accessibility "lock screen" global action is the app-accessible
 * equivalent of pressing the power button and does work in lock task.
 */

package com.hmdm.launcher.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.hmdm.launcher.Const;

public class ScreenLockAccessibilityService extends AccessibilityService {

    private static ScreenLockAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(Const.LOG_TAG, "ScreenLockAccessibilityService connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used; this service only exists to expose performGlobalAction().
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /**
     * Turn the screen off (lock). Returns true if the action was dispatched.
     */
    public static boolean lockScreen() {
        if (instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            } catch (Exception e) {
                Log.w(Const.LOG_TAG, "GLOBAL_ACTION_LOCK_SCREEN failed: " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean isAvailable() {
        return instance != null;
    }
}
