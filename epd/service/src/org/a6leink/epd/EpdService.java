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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The state of the E-ink service and everything it drives (spec 02 sections
 * 1.2, 2, 3 and 4). The binder face is {@link EpdBinder}.
 *
 * Threading: state fields marked "lock" are guarded by {@link #mLock} and may
 * be read and changed from binder threads; anything that touches hardware,
 * settings or other services runs on {@link #mHandler}, a dedicated thread,
 * in the order it was posted. Fields marked "handler" are confined to it.
 */
final class EpdService {
    static final String TAG = "A6lEpd";

    /** Delay between LOCKED_BOOT_COMPLETED (or a process restart) and "E-ink ready". */
    private static final long EINK_READY_DELAY_MS = 5000;
    private static final int NO_DISPLAY = Integer.MIN_VALUE;
    private static final int SOURCE_USER = 1;
    private static final int SOURCE_UNKNOWN = -1;
    private static final int LOCK_CAMERA = 0;
    private static final int LOCK_VIDEO = 1;

    private final Context mContext;
    private final Handler mHandler;
    private final PropertyBridge mBridge = new PropertyBridge();
    private final EinkOnlyController mEinkOnly;
    private final StatusScreen mStatus;
    private final AppModeStore mStore;

    final Object mLock = new Object();
    // lock
    private int mDisplayType = EpdSettings.TYPE_LCD;
    private int mPreviousType = EpdSettings.TYPE_LCD;
    private int mLastScreenOnType = EpdSettings.TYPE_LCD;
    private final boolean[] mDisplayEnabled = { true, true };
    private int mEpdMode = EpdSettings.MODE_MIRROR;
    private final boolean[] mModeLocks = new boolean[2];
    private String mTopPackage = "";
    private final Map<String, Integer> mAppModes = new HashMap<>();
    private int mWakeupSource = SOURCE_USER;
    private boolean mWakeupLocked;
    private int mWakeupDisplay = NO_DISPLAY;
    private int mThemeMode;
    private int mKeyguardMode;
    private int mGestureUnlock;
    private boolean mBootFinished;
    private boolean mReadyScheduled;

    // handler
    private volatile boolean mEinkReady;

    EpdService(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("A6lEpd", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mEinkOnly = new EinkOnlyController(context, mHandler, mBridge);
        mStatus = new StatusScreen(context, mHandler, mBridge);
        mStore = new AppModeStore(context);
    }

    Context context() {
        return mContext;
    }

    /** Called from Application.onCreate. Registration first, then the rest on the handler. */
    void start() {
        ServiceManager.addService(EpdSettings.SERVICE_NAME, new EpdBinder(this));
        Log.i(TAG, "Registered service '" + EpdSettings.SERVICE_NAME + "'");

        mHandler.post(this::initOnHandler);
    }

    private void initOnHandler() {
        // Both outlive a reboot taken while the E-ink was in use (section 1.2).
        EinkOnlyController.clearForcedSize();
        EinkOnlyController.restoreSavedSettings(mContext);

        Map<String, Integer> modes = mStore.load();
        synchronized (mLock) {
            mAppModes.putAll(modes);
        }
        publish(EpdSettings.TYPE_LCD);

        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    handleScreenOn();
                } else {
                    handleScreenOff();
                }
            }
        }, screen, null, mHandler);

        IntentFilter boot = new IntentFilter();
        boot.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        boot.addAction(Intent.ACTION_BOOT_COMPLETED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                scheduleEinkReady(intent.getAction());
            }
        }, boot, null, mHandler);

        new ForegroundTracker(mHandler, this::setTopPackage).start();

        if ("1".equals(SystemProperties.get(EpdSettings.PROP_BOOT_COMPLETED))) {
            // Process restart after boot: no boot broadcast will come.
            scheduleEinkReady("process start after boot");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Boot timing (section 1.2)

    /** Starts the 5 s delay to "E-ink ready" once; later calls are ignored. */
    void scheduleEinkReady(String why) {
        synchronized (mLock) {
            if (mReadyScheduled) return;
            mReadyScheduled = true;
        }
        Log.i(TAG, "E-ink ready in " + EINK_READY_DELAY_MS + " ms (" + why + ")");
        mHandler.postDelayed(this::onEinkReady, EINK_READY_DELAY_MS);
    }

    private void onEinkReady() {
        if (mEinkReady) return;
        if (!"1".equals(SystemProperties.get(EpdSettings.PROP_BOOT_COMPLETED))) {
            // init connects the E-ink at sys.boot_completed=1; touching fb1 earlier
            // crashes the HWC. Wait for it and give the HWC the same delay.
            Log.i(TAG, "sys.boot_completed not set yet, delaying E-ink ready");
            mHandler.postDelayed(this::onEinkReady, EINK_READY_DELAY_MS);
            return;
        }
        mEinkReady = true;
        final int type;
        final int mode;
        synchronized (mLock) {
            type = mDisplayType;
            mode = mEpdMode;
        }
        Log.i(TAG, "E-ink ready, type " + type + ", mode " + mode);
        if (type == EpdSettings.TYPE_EINK) {
            applyHardware(type);
        } else {
            // init switched the rear touch on at boot completion.
            mBridge.setRearTouch(false);
        }
        reapplyTuning();
        mBridge.setRefreshMode(mode);
        mStatus.onDisplayType(type);
    }

    private void reapplyTuning() {
        int[] defaults = { EpdSettings.DEFAULT_CONTRAST, EpdSettings.DEFAULT_BLACK,
                EpdSettings.DEFAULT_WHITE };
        for (int t = 0; t < defaults.length; t++) {
            int value = getEinkEffect(t);
            if (value != defaults[t]) mBridge.setTuning(t, value);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Display type (sections 2.2 to 2.7, 3.1)

    /** Restriction of section 3.1; returns {@link #NO_DISPLAY} for an unknown type. */
    private int restrictLocked(int type) {
        if (type < 0) return EpdSettings.TYPE_OFF;
        type &= ~1; // no dual-screen tasks on 18.1
        if (type != EpdSettings.TYPE_LCD && type != EpdSettings.TYPE_MIRROR
                && type != EpdSettings.TYPE_EINK) {
            return NO_DISPLAY;
        }
        if (!mDisplayEnabled[1] && type >= EpdSettings.TYPE_MIRROR) type = EpdSettings.TYPE_LCD;
        if (!mDisplayEnabled[0] && type == EpdSettings.TYPE_LCD) type = EpdSettings.TYPE_EINK;
        return type;
    }

    void setDisplayType(int requested) {
        synchronized (mLock) {
            int type = restrictLocked(requested);
            if (type == NO_DISPLAY) {
                Log.w(TAG, "Ignoring unknown display type " + requested);
                return;
            }
            changeTypeLocked(type, false);
        }
    }

    /** Section 2.2 steps 1 to 5. Returns false for a no-op. */
    private boolean changeTypeLocked(int type, boolean fromScreenOn) {
        if (type == mDisplayType) return false;
        final int old = mDisplayType;
        mPreviousType = old;
        mDisplayType = type;
        if (type != EpdSettings.TYPE_OFF) mLastScreenOnType = type;
        Log.i(TAG, "Display type " + old + " -> " + type + (fromScreenOn ? " (screen on)" : ""));

        mHandler.post(() -> {
            if (mEinkReady) {
                if (!fromScreenOn) applyHardware(type);
                mStatus.onDisplayType(type);
            }
            publish(type);
        });

        if (type == EpdSettings.TYPE_OFF) {
            // Stock clears the camera/video locks when the screens go off.
            mModeLocks[LOCK_CAMERA] = false;
            mModeLocks[LOCK_VIDEO] = false;
        } else if (EpdSettings.isEinkType(type)) {
            updateEpdModeLocked();
        } else {
            setEpdModeLocked(EpdSettings.MODE_MIRROR);
        }
        return true;
    }

    /** Hardware for a type (section 2.4). Handler thread, E-ink ready. */
    private void applyHardware(int type) {
        switch (type) {
            case EpdSettings.TYPE_EINK:
                mEinkOnly.enter();
                mBridge.reconnectEink();
                break;
            case EpdSettings.TYPE_MIRROR:
                mEinkOnly.leave();
                mBridge.reconnectEink();
                break;
            case EpdSettings.TYPE_LCD:
                mEinkOnly.leave();
                break;
            default:
                break;
        }
    }

    private void publish(int type) {
        try {
            SystemProperties.set(EpdSettings.PROP_DISPLAY_TYPE, Integer.toString(type));
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot set " + EpdSettings.PROP_DISPLAY_TYPE, e);
        }
        Settings.System.putIntForUser(mContext.getContentResolver(),
                EpdSettings.SETTING_DISPLAY_TYPE, type, UserHandle.USER_CURRENT);
        Intent intent = new Intent(EpdSettings.ACTION_DISPLAY_CHANGED);
        intent.putExtra(EpdSettings.EXTRA_DISPLAY_TYPE, type);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /** ACTION_SCREEN_ON or onScreenChanged(true). Handler thread. */
    void handleScreenOn() {
        // Read outside the lock (a call into the settings provider).
        final boolean wakeToLcd = Settings.System.getIntForUser(mContext.getContentResolver(),
                EpdSettings.DEFAULT_WAKE, 0, UserHandle.USER_CURRENT) == 1;
        final int target;
        synchronized (mLock) {
            mWakeupLocked = false;
            int wanted;
            if (mWakeupDisplay != NO_DISPLAY) {
                wanted = mWakeupDisplay;
            } else if (mDisplayType != EpdSettings.TYPE_OFF) {
                wanted = mDisplayType; // redundant screen-on
            } else if (wakeToLcd) {
                wanted = EpdSettings.TYPE_LCD;
            } else {
                wanted = mLastScreenOnType;
            }
            mWakeupDisplay = NO_DISPLAY;
            mWakeupSource = SOURCE_UNKNOWN;
            int restricted = restrictLocked(wanted);
            target = restricted == NO_DISPLAY || restricted == EpdSettings.TYPE_OFF
                    ? EpdSettings.TYPE_LCD : restricted;
            changeTypeLocked(target, true);
        }
        // Runs after the posted type change.
        mHandler.post(() -> {
            mEinkOnly.cancelScreenOnRepeats();
            if (!mEinkReady) return;
            applyHardware(target);
            if (target == EpdSettings.TYPE_EINK) mEinkOnly.scheduleScreenOnRepeats();
        });
    }

    /** ACTION_SCREEN_OFF or onScreenChanged(false). Handler thread. */
    void handleScreenOff() {
        synchronized (mLock) {
            mWakeupLocked = false;
            changeTypeLocked(EpdSettings.TYPE_OFF, false);
        }
        mHandler.post(mEinkOnly::cancelScreenOnRepeats);
    }

    void onScreenChanged(boolean on) {
        mHandler.post(on ? this::handleScreenOn : this::handleScreenOff);
    }

    int getDisplayType() {
        synchronized (mLock) {
            return mDisplayType;
        }
    }

    int getLastScreenOnType() {
        synchronized (mLock) {
            return mLastScreenOnType;
        }
    }

    void setDisplayEnabled(int id, boolean enabled) {
        if (id != 0 && id != 1) return;
        synchronized (mLock) {
            mDisplayEnabled[id] = enabled;
        }
    }

    boolean getDisplayEnabled(int id) {
        if (id != 0 && id != 1) return true;
        synchronized (mLock) {
            return mDisplayEnabled[id];
        }
    }

    void setWakeupSourceType(int source, int display) {
        synchronized (mLock) {
            if (mWakeupLocked) return;
            mWakeupSource = source;
            if (source == SOURCE_USER
                    && (display == EpdSettings.TYPE_LCD || display == EpdSettings.TYPE_EINK)) {
                mWakeupDisplay = display;
            }
        }
    }

    int getWakeupSourceType() {
        synchronized (mLock) {
            return mWakeupSource;
        }
    }

    void setWakeupLocked(boolean locked) {
        synchronized (mLock) {
            mWakeupLocked = locked;
        }
    }

    void requestGoToSleep() {
        mHandler.post(() -> mContext.getSystemService(PowerManager.class)
                .goToSleep(SystemClock.uptimeMillis()));
    }

    // ---------------------------------------------------------------------------------------
    // Refresh mode (section 3.2)

    private void setEpdModeLocked(int mode) {
        if (mode == mEpdMode) return;
        mEpdMode = mode;
        mHandler.post(() -> {
            if (mEinkReady) mBridge.setRefreshMode(mode);
        });
    }

    void setEpdDisplayMode(int mode) {
        if (mode < 0 || mode > EpdSettings.MODE_DEFAULT_EINK) {
            Log.w(TAG, "Ignoring refresh mode " + mode);
            return;
        }
        synchronized (mLock) {
            setEpdModeLocked(mode);
        }
    }

    int getEpdMode() {
        synchronized (mLock) {
            return mEpdMode;
        }
    }

    void updateEpdMode() {
        synchronized (mLock) {
            updateEpdModeLocked();
        }
    }

    private void updateEpdModeLocked() {
        if (!EpdSettings.isEinkType(mDisplayType)) return;
        int mode;
        if (mModeLocks[LOCK_CAMERA] || mModeLocks[LOCK_VIDEO]) {
            mode = EpdSettings.MODE_FAST;
        } else {
            Integer app = matchAppModeLocked(mTopPackage);
            mode = app != null ? app : EpdSettings.MODE_DEFAULT_EINK;
        }
        setEpdModeLocked(mode);
    }

    /** An entry matches when either string contains the other. */
    private Integer matchAppModeLocked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        Integer exact = mAppModes.get(pkg);
        if (exact != null) return exact;
        for (Map.Entry<String, Integer> e : mAppModes.entrySet()) {
            String key = e.getKey();
            if (key.isEmpty()) continue;
            if (key.contains(pkg) || pkg.contains(key)) return e.getValue();
        }
        return null;
    }

    void lockEpdMode(int type, boolean on) {
        if (type != LOCK_CAMERA && type != LOCK_VIDEO) return;
        synchronized (mLock) {
            mModeLocks[type] = on;
            updateEpdModeLocked();
        }
    }

    void addAppMode(String pkg, int mode) {
        if (pkg == null || mode <= 0 || mode >= EpdSettings.MODE_DEFAULT_EINK
                || EpdSettings.EINK_LAUNCHER.equals(pkg)) {
            Log.w(TAG, "addAppMode refused: " + pkg + " " + mode);
            return;
        }
        synchronized (mLock) {
            mAppModes.put(pkg, mode);
            saveLocked();
            if (!mTopPackage.isEmpty()
                    && (mTopPackage.contains(pkg) || pkg.contains(mTopPackage))) {
                updateEpdModeLocked();
            }
        }
    }

    void removeAppMode(String pkg) {
        if (pkg == null) return;
        synchronized (mLock) {
            if (mAppModes.remove(pkg) != null) {
                saveLocked();
                updateEpdModeLocked();
            }
        }
    }

    Map<String, Integer> getAllAppMode() {
        synchronized (mLock) {
            return new HashMap<>(mAppModes);
        }
    }

    private void saveLocked() {
        final Map<String, Integer> copy = new HashMap<>(mAppModes);
        mHandler.post(() -> mStore.save(copy));
    }

    void forceClear() {
        mHandler.post(() -> {
            if (mEinkReady) mBridge.forceClear();
        });
    }

    // ---------------------------------------------------------------------------------------
    // Tuning (section 3.3)

    private static String tuningProperty(int type) {
        switch (type) {
            case 0: return EpdSettings.PROP_CONTRAST;
            case 1: return EpdSettings.PROP_BLACK;
            case 2: return EpdSettings.PROP_WHITE;
            default: return null;
        }
    }

    int getEinkEffect(int type) {
        String prop = tuningProperty(type);
        if (prop == null) return 0;
        int def = type == 0 ? EpdSettings.DEFAULT_CONTRAST
                : type == 1 ? EpdSettings.DEFAULT_BLACK : EpdSettings.DEFAULT_WHITE;
        return SystemProperties.getInt(prop, def);
    }

    void adjustEinkEffect(int type, int value) {
        final String prop = tuningProperty(type);
        if (prop == null || value < 0 || value > 100) return;
        mHandler.post(() -> {
            try {
                SystemProperties.set(prop, Integer.toString(value));
            } catch (RuntimeException e) {
                Log.e(TAG, "Cannot set " + prop, e);
            }
            if (mEinkReady) mBridge.setTuning(type, value);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Foreground app (section 3.4)

    void setTopPackage(String pkg) {
        if (pkg == null) return;
        synchronized (mLock) {
            if (pkg.equals(mTopPackage)) return;
            mTopPackage = pkg;
            updateEpdModeLocked();
        }
    }

    String getTopPackage() {
        synchronized (mLock) {
            return mTopPackage;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings-like calls (section 3.5)

    void setEinkThemeMode(int mode) {
        synchronized (mLock) {
            mThemeMode = mode;
        }
    }

    void setEinkKeyguardMode(int mode) {
        synchronized (mLock) {
            mKeyguardMode = mode;
        }
    }

    void setGestureUnlock(int value) {
        synchronized (mLock) {
            mGestureUnlock = value;
        }
    }

    void setExternalTpEnabled(boolean enabled) {
        mHandler.post(() -> mBridge.setRearTouch(enabled));
    }

    void finishBoot() {
        synchronized (mLock) {
            mBootFinished = true;
        }
        mHandler.post(() -> {
            try {
                SystemProperties.set(EpdSettings.PROP_LAUNCHER_READY, "1");
            } catch (RuntimeException e) {
                Log.e(TAG, "Cannot set " + EpdSettings.PROP_LAUNCHER_READY, e);
            }
            ContentResolver cr = mContext.getContentResolver();
            if (Settings.System.getIntForUser(cr, EpdSettings.ACCELEROMETER_ROTATION_RESTORE, 0,
                    UserHandle.USER_CURRENT) != 0) {
                Settings.System.putIntForUser(cr, Settings.System.ACCELEROMETER_ROTATION, 1,
                        UserHandle.USER_CURRENT);
                Settings.System.putIntForUser(cr, EpdSettings.ACCELEROMETER_ROTATION_RESTORE, 0,
                        UserHandle.USER_CURRENT);
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // dumpsys (section 3.8)

    void dump(PrintWriter pw, String[] args) {
        if (args != null && args.length >= 2 && "status".equals(args[0])) {
            final int state;
            switch (args[1]) {
                case "lcd": state = StatusScreen.STATE_LCD; break;
                case "sleep": state = StatusScreen.STATE_SLEEP; break;
                case "stop": state = StatusScreen.STATE_NONE; break;
                default:
                    pw.println("usage: dumpsys epd status lcd|sleep|stop");
                    return;
            }
            mHandler.post(() -> mStatus.force(state));
            pw.println("status screen forced to " + args[1]);
            return;
        }

        synchronized (mLock) {
            pw.println("E-ink service (a6l-eink-oss)");
            pw.println("  display type: " + mDisplayType + ", previous: " + mPreviousType
                    + ", last screen-on: " + mLastScreenOnType);
            pw.println("  e-ink ready: " + mEinkReady + " (scheduled: " + mReadyScheduled + ")");
            pw.println("  display enabled: lcd=" + mDisplayEnabled[0]
                    + " eink=" + mDisplayEnabled[1]);
            pw.println("  refresh mode: " + mEpdMode + ", locks: camera=" + mModeLocks[LOCK_CAMERA]
                    + " video=" + mModeLocks[LOCK_VIDEO]);
            pw.println("  top package: " + mTopPackage);
            pw.println("  wake-up source: " + mWakeupSource + ", locked: " + mWakeupLocked
                    + ", wake-up display: "
                    + (mWakeupDisplay == NO_DISPLAY ? "none" : mWakeupDisplay));
            pw.println("  theme mode: " + mThemeMode + ", keyguard mode: " + mKeyguardMode
                    + ", gesture unlock: " + mGestureUnlock + ", finishBoot: " + mBootFinished);
            pw.println("  tuning: contrast=" + getEinkEffect(0) + " black=" + getEinkEffect(1)
                    + " white=" + getEinkEffect(2));
            pw.println("  per-app modes: " + new TreeMap<>(mAppModes));
        }
        // Handler-confined parts: read them on the handler and wait briefly.
        mHandler.runWithScissors(() -> {
            mEinkOnly.dump(pw);
            mStatus.dump(pw);
        }, 1000);
    }
}
