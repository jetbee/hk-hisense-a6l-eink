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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;
import com.hmct.epd.IEpdManager;

/**
 * LineageOS device key handler for the rear E-ink key (spec 02 section 5.1).
 * Loaded into system_server from this APK through the lineage-sdk overlay
 * ({@code config_deviceKeyHandlerLibs} / {@code config_deviceKeyHandlerClasses}).
 *
 * Runs in the policy's pre-queue stage, so it never blocks: binder calls to
 * {@code epd}, wake-ups and settings reads go to its own handler thread. The
 * service is looked up on every use and may be absent.
 *
 * Must not use this APK's resources (the system_server context has none of them).
 */
public final class EpdKeyHandler implements DeviceKeyHandler {
    private static final String TAG = "A6lEpdKey";

    /** Linux KEY_LEFT_UP from gpio-keys ("EPD_PWR key"); no Android key code on 18.1. */
    public static final int SCANCODE_EINK_KEY = 616;

    private static final int SOURCE_USER = 1;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final Handler mHandler;

    public EpdKeyHandler(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        HandlerThread thread = new HandlerThread("A6lEpdKey");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getScanCode() == SCANCODE_EINK_KEY) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                final boolean interactive = mPowerManager.isInteractive();
                mHandler.post(() -> onEinkKey(interactive));
            }
            return null; // both down and up are consumed
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_POWER
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && !mPowerManager.isInteractive()) {
            // Recorded before the screen-on that this key causes.
            mHandler.post(this::onPowerKeyWhileOff);
        }
        return event;
    }

    private static IEpdManager service() {
        return IEpdManager.Stub.asInterface(ServiceManager.checkService(EpdSettings.SERVICE_NAME));
    }

    private int userSetting(String key, int def) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), key, def,
                UserHandle.USER_CURRENT);
    }

    private void onEinkKey(boolean interactive) {
        mContext.sendBroadcastAsUser(new Intent(EpdSettings.ACTION_EINK_KEY_PRESSED),
                UserHandle.CURRENT);
        IEpdManager epd = service();
        if (epd == null) {
            Log.w(TAG, "E-ink key: service not available");
            return;
        }
        try {
            if (!interactive) {
                epd.setWakeupSourceType(SOURCE_USER, EpdSettings.TYPE_EINK, false);
                mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_WAKE_KEY, "org.a6leink.epd:EINK_KEY");
            } else if (epd.getDisplayType() == EpdSettings.TYPE_EINK) {
                if (userSetting(EpdSettings.EINK_KEY_ACTION, 0) == 1) {
                    mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
                } else {
                    epd.setDisplayType(EpdSettings.TYPE_LCD, false);
                }
            } else {
                epd.setDisplayType(EpdSettings.TYPE_EINK, false);
            }
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "E-ink key handling failed", e);
        }
    }

    private void onPowerKeyWhileOff() {
        if (userSetting(EpdSettings.POWER_KEY_WAKE, 0) != 0) return;
        IEpdManager epd = service();
        if (epd == null) return;
        try {
            epd.setWakeupSourceType(SOURCE_USER, EpdSettings.TYPE_LCD, false);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Recording the LCD wake-up failed", e);
        }
    }
}
