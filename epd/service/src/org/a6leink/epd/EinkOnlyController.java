/*
 * Copyright (C) 2026 The a6l-eink-oss authors
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

package org.a6leink.epd;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import java.io.PrintWriter;

/**
 * The "E-ink only" set-up and its undo (spec 02 sections 2.3 to 2.5). All
 * methods run on the service's handler thread.
 */
final class EinkOnlyController {
    private static final String TAG = "A6lEpdEinkOnly";

    /** Delays of the re-application after a screen-on (spec 02 section 2.4). */
    private static final long[] SCREEN_ON_REPEATS_MS = { 0, 300, 800, 1500, 3000 };
    private static final long BACKLIGHT_NUDGE_MS = 100;
    private static final float BRIGHTNESS_NUDGE = 0.01f;

    private final Object mRepeatToken = new Object();
    private final Context mContext;
    private final Handler mHandler;
    private final PropertyBridge mBridge;
    private final ContentObserver mBrightnessObserver;

    private boolean mApplied;
    private boolean mObserving;

    EinkOnlyController(Context context, Handler handler, PropertyBridge bridge) {
        mContext = context;
        mHandler = handler;
        mBridge = bridge;
        mBrightnessObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (mApplied) {
                    // The power manager rewrote the backlight: dark again.
                    mBridge.lcdBacklightOff();
                }
            }
        };
    }

    boolean isApplied() {
        return mApplied;
    }

    /** Section 2.3. No-op when already applied. */
    void enter() {
        if (mApplied) return;
        mApplied = true;
        Log.i(TAG, "Entering E-ink only");

        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            wm.setForcedDisplaySize(Display.DEFAULT_DISPLAY,
                    EpdSettings.FORCED_WIDTH, EpdSettings.FORCED_HEIGHT);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "setForcedDisplaySize failed", e);
        }

        mBridge.setFrontTouch(false);
        mBridge.setRearTouch(true);

        ContentResolver cr = mContext.getContentResolver();
        int saved = Settings.System.getIntForUser(cr, EpdSettings.SAVED_BRIGHTNESS_MODE, -1,
                UserHandle.USER_CURRENT);
        if (saved == -1) {
            int mode = Settings.System.getIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);
            Settings.System.putIntForUser(cr, EpdSettings.SAVED_BRIGHTNESS_MODE, mode,
                    UserHandle.USER_CURRENT);
        }
        Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);

        if (!mObserving) {
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, mBrightnessObserver, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FLOAT),
                    false, mBrightnessObserver, UserHandle.USER_ALL);
            mObserving = true;
        }

        mBridge.lcdBacklightOff();
    }

    /** Section 2.5. No-op when not applied. */
    void leave() {
        if (!mApplied) return;
        mApplied = false;
        Log.i(TAG, "Leaving E-ink only");
        cancelScreenOnRepeats();
        if (mObserving) {
            mContext.getContentResolver().unregisterContentObserver(mBrightnessObserver);
            mObserving = false;
        }

        clearForcedSize();
        mBridge.setFrontTouch(true);
        mBridge.setRearTouch(false);
        restoreSavedSettings(mContext);
        nudgeBacklight();
    }

    /**
     * After a screen-on that woke to the E-ink: front touch and backlight off
     * now and again at the delays of section 2.4, while still applied.
     */
    void scheduleScreenOnRepeats() {
        cancelScreenOnRepeats();
        if (!mApplied) return;
        final long now = SystemClock.uptimeMillis();
        for (long delay : SCREEN_ON_REPEATS_MS) {
            mHandler.postAtTime(this::reapplyAfterScreenOn, mRepeatToken, now + delay);
        }
    }

    void cancelScreenOnRepeats() {
        mHandler.removeCallbacksAndMessages(mRepeatToken);
    }

    private void reapplyAfterScreenOn() {
        if (!mApplied) return;
        mBridge.setFrontTouch(false);
        mBridge.lcdBacklightOff();
    }

    /** Bring the LCD backlight back: the display power controller needs a nudge. */
    private void nudgeBacklight() {
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        if (dm == null || pm == null) return;
        float min = pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM);
        float current = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FLOAT, Float.NaN, UserHandle.USER_CURRENT);
        if (Float.isNaN(current)) {
            current = pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT);
        }
        float temp = Math.max(min, current - BRIGHTNESS_NUDGE);
        try {
            dm.setTemporaryBrightness(temp);
            mHandler.postDelayed(() -> {
                try {
                    dm.setTemporaryBrightness(PowerManager.BRIGHTNESS_INVALID_FLOAT);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Clearing temporary brightness failed", e);
                }
            }, BACKLIGHT_NUDGE_MS);
        } catch (RuntimeException e) {
            Log.e(TAG, "setTemporaryBrightness failed", e);
        }
    }

    /** Clears the forced size of display 0 (it outlives a reboot). */
    static void clearForcedSize() {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .clearForcedDisplaySize(Display.DEFAULT_DISPLAY);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "clearForcedDisplaySize failed", e);
        }
    }

    /** Puts back the user settings saved while E-ink only was applied (section 2.5 step 4). */
    static void restoreSavedSettings(Context context) {
        ContentResolver cr = context.getContentResolver();
        int mode = Settings.System.getIntForUser(cr, EpdSettings.SAVED_BRIGHTNESS_MODE, -1,
                UserHandle.USER_CURRENT);
        if (mode != -1) {
            Settings.System.putIntForUser(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode,
                    UserHandle.USER_CURRENT);
            Settings.System.putIntForUser(cr, EpdSettings.SAVED_BRIGHTNESS_MODE, -1,
                    UserHandle.USER_CURRENT);
        }
        int gesture = Settings.System.getIntForUser(cr, EpdSettings.SAVED_WAKE_GESTURE, -1,
                UserHandle.USER_CURRENT);
        if (gesture != -1) {
            Settings.Secure.putIntForUser(cr, EpdSettings.WAKE_GESTURE_ENABLED, gesture,
                    UserHandle.USER_CURRENT);
            Settings.System.putIntForUser(cr, EpdSettings.SAVED_WAKE_GESTURE, -1,
                    UserHandle.USER_CURRENT);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  eink-only applied: " + mApplied + ", brightness observer: " + mObserving);
    }
}
