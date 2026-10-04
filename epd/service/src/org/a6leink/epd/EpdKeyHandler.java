/*
 * Copyright (C) 2026 jetbee
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;
import com.hmct.epd.IEpdManager;

/**
 * LineageOS device key handler for the rear E-ink key (spec 02 sections 5.1
 * and 9).
 *
 * Button roles (spec 02 section 9.1), the same on both screens:
 * screen on: power button sleeps (left to the framework), E-ink key switches
 * between LCD only and E-ink only; screen off: power button wakes to the LCD,
 * E-ink key wakes to the E-ink. With {@code a6l_epd_swap_keys_on_eink} = 1
 * (section 9.2), while the E-ink is in use and the screen is on, the power
 * button switches back to the LCD and the E-ink key sleeps; the power button's
 * long press is then not available on the E-ink.
 *
 * "E-ink in use" is read from the published display type property, without a
 * binder call; the swap setting is cached by a content observer.
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
    private static final long OBSERVER_RETRY_MS = 5000;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final Handler mHandler;

    /** Cached a6l_epd_swap_keys_on_eink of the current user. */
    private volatile boolean mSwapOnEink;
    /** A power button press whose down event was taken for the swap. */
    private boolean mPowerSwapped;

    public EpdKeyHandler(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);
        HandlerThread thread = new HandlerThread("A6lEpdKey");
        thread.start();
        mHandler = new Handler(thread.getLooper());
        // The handler is created early in system_server; settings may not be up yet.
        mHandler.post(this::startObserving);
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        final int action = event.getAction();
        if (event.getScanCode() == SCANCODE_EINK_KEY) {
            if (action == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                final boolean interactive = mPowerManager.isInteractive();
                if (interactive && mSwapOnEink && isEinkInUse()) {
                    // Swapped role: the E-ink key puts the phone to sleep.
                    mHandler.post(() -> {
                        sendKeyPressed();
                        mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
                    });
                } else {
                    mHandler.post(() -> onEinkKey(interactive));
                }
            }
            return null; // both down and up are consumed
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_POWER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() != 0) {
                    return mPowerSwapped ? null : event;
                }
                final boolean interactive = mPowerManager.isInteractive();
                mPowerSwapped = interactive && mSwapOnEink && isEinkInUse();
                if (mPowerSwapped) {
                    return null; // no sleep, no long press: back to the LCD on release
                }
                if (!interactive) {
                    // Recorded before the screen-on that this key causes.
                    mHandler.post(this::onPowerKeyWhileOff);
                }
            } else if (action == KeyEvent.ACTION_UP && mPowerSwapped) {
                mPowerSwapped = false;
                if (!event.isCanceled()) {
                    mHandler.post(() -> setDisplayType(EpdSettings.TYPE_LCD));
                }
                return null;
            }
        }
        return event;
    }

    /** E-ink only is active, from the published property (no binder call). */
    private static boolean isEinkInUse() {
        return SystemProperties.getInt(EpdSettings.PROP_DISPLAY_TYPE, EpdSettings.TYPE_LCD)
                == EpdSettings.TYPE_EINK;
    }

    private void startObserving() {
        try {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(EpdSettings.SWAP_KEYS_ON_EINK), false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            readSwapSetting();
                        }
                    }, UserHandle.USER_ALL);
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    readSwapSetting();
                }
            }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);
            readSwapSetting();
        } catch (RuntimeException e) {
            Log.i(TAG, "Settings not available yet, retrying: " + e);
            mHandler.postDelayed(this::startObserving, OBSERVER_RETRY_MS);
        }
    }

    private void readSwapSetting() {
        mSwapOnEink = userSetting(EpdSettings.SWAP_KEYS_ON_EINK, 0) != 0;
    }

    private static IEpdManager service() {
        return IEpdManager.Stub.asInterface(ServiceManager.checkService(EpdSettings.SERVICE_NAME));
    }

    private int userSetting(String key, int def) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), key, def,
                UserHandle.USER_CURRENT);
    }

    private void sendKeyPressed() {
        mContext.sendBroadcastAsUser(new Intent(EpdSettings.ACTION_EINK_KEY_PRESSED),
                UserHandle.CURRENT);
    }

    private void setDisplayType(int type) {
        IEpdManager epd = service();
        if (epd == null) {
            Log.w(TAG, "Service not available");
            return;
        }
        try {
            epd.setDisplayType(type, false);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "setDisplayType failed", e);
        }
    }

    private void onEinkKey(boolean interactive) {
        sendKeyPressed();
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
                // The swap was decided on the input thread from a cached setting;
                // read it again so a stale cache never turns the swapped "sleep"
                // into a switch to the LCD (spec 02 section 10 item 2).
                readSwapSetting();
                if (mSwapOnEink || userSetting(EpdSettings.EINK_KEY_ACTION, 0) == 1) {
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
