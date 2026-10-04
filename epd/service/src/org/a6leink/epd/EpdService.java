/*
 * Copyright (C) 2026 The hk-hisense-a6l-eink authors
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

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.content.pm.ResolveInfo;
import android.os.Binder;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
    private final SleepWidgetHost mWidgets;
    private final AppModeStore mStore;
    private final ForegroundTracker mTracker;
    private final CameraWatcher mCamera;
    private final PlaybackWatcher mPlayback;
    private final SlideMonitor mSlideMonitor;

    final Object mLock = new Object();
    // lock
    private int mDisplayType = EpdSettings.TYPE_LCD;
    private int mPreviousType = EpdSettings.TYPE_LCD;
    private int mLastScreenOnType = EpdSettings.TYPE_LCD;
    private final boolean[] mDisplayEnabled = { true, true };
    private int mEpdMode = EpdSettings.MODE_MIRROR;
    /** Locks set by clients through lockEpdMode (spec 05 section 6.3 "client locks"). */
    private final boolean[] mModeLocks = new boolean[2];
    private String mTopPackage = "";
    private final Map<String, Integer> mAppModes = new HashMap<>();
    // Per-app evaluation (spec 05 section 6.2).
    /** The observed front app (keep-mode dialogs excluded); null until the first query. */
    private ForegroundTracker.Front mFront;
    /** Last observed keep-mode dialog, for dumpsys only. */
    private ForegroundTracker.Front mKeepModeFront;
    private int mFrontUid = -1;
    private boolean mFrontIsHome;
    /** True if the front package has no launcher activity (a helper, spec 05 section 2.2). */
    private boolean mFrontIsHelper;
    /** Package whose mode a helper follows (the app that opened it), or null. */
    private String mHostPackage;
    private boolean mKeyguardLocked;
    /** Open cameras that lock: id to opener package ("" = unknown). */
    private Map<String, String> mOpenCameras = new TreeMap<>();
    /** Uids with video-like audio playback. */
    private Set<Integer> mVideoUids = new TreeSet<>();
    private boolean mSlideDragging;
    /** Packages with "slide one screen" on (experimental). */
    private final Set<String> mSlide = new TreeSet<>();
    private final Map<String, Boolean> mOptions = new HashMap<>();
    private String mModeReason = "start";
    private String mModeTrigger = "start";
    private boolean mSlideWanted;
    private boolean mPollWanted;
    /**
     * False until the file has been read: no write may happen before, or a
     * client call that arrives early would replace the file with a partial map.
     */
    private boolean mStoreLoaded;
    private boolean mSaveAfterLoad;
    /** a6l_epd_keyguard_clear (spec 02 section 10 item 4a): clear mode on the lock screen. */
    private boolean mKeyguardClear;
    /** Wake timing log (spec 02 section 10 item 4b). Thread-safe. */
    private final WakeTimer mWake = new WakeTimer();
    /** Spec 02 section 10 item 4c: one user activity after a locked wake into the E-ink. */
    private final Runnable mWakeUserActivity = this::wakeUserActivity;
    /** Spec 02 section 10 item 5: SurfaceFlinger repaints after a reconnect. */
    private final Repainter mRepainter;
    private static final long WAKE_USER_ACTIVITY_MS = 1500;
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
        mEinkOnly.setWakeTimer(mWake);
        mRepainter = new Repainter(mHandler, mWake);
        mWidgets = new SleepWidgetHost(context);
        mStatus = new StatusScreen(context, mHandler, mBridge, mWidgets);
        mStore = new AppModeStore(context);
        mTracker = new ForegroundTracker(mHandler, this::onFrontObserved);
        mCamera = new CameraWatcher(context, mHandler, this::onCamerasChanged);
        mPlayback = new PlaybackWatcher(context, mHandler, this::onPlaybackChanged);
        mSlideMonitor = new SlideMonitor(context, mHandler, new SlideMonitor.Listener() {
            @Override
            public void onDragStarted() {
                setSlideDragging(true);
            }

            @Override
            public void onDragEnded() {
                setSlideDragging(false);
            }
        });
    }

    Context context() {
        return mContext;
    }

    /** Called from Application.onCreate. Registration first, then the rest on the handler. */
    void start() {
        ServiceManager.addService(EpdSettings.SERVICE_NAME, new EpdBinder(this));
        Log.i(TAG, "Registered service '" + EpdSettings.SERVICE_NAME + "'");

        mHandler.post(this::initOnHandler);
        // Sleep screen widgets (spec 03 section 3): listen for the life of the service.
        mWidgets.start();
    }

    private void initOnHandler() {
        // Both outlive a reboot taken while the E-ink was in use (section 1.2).
        EinkOnlyController.clearForcedSize();
        EinkOnlyController.restoreSavedSettings(mContext);

        // Read before anything may write the file (spec 02 section 4.1).
        AppModeStore.Data data = mStore.load();
        synchronized (mLock) {
            // Changes made by clients before the load (binder calls can arrive as
            // soon as the service is registered) win over the file.
            for (Map.Entry<String, Integer> e : data.modes.entrySet()) {
                mAppModes.putIfAbsent(e.getKey(), e.getValue());
            }
            mSlide.addAll(data.slide);
            for (Map.Entry<String, Boolean> e : data.options.entrySet()) {
                mOptions.putIfAbsent(e.getKey(), e.getValue());
            }
            mStoreLoaded = true;
            Log.i(TAG, "Loaded " + data.modes.size() + " per-app modes, "
                    + data.slide.size() + " slide flags, " + data.options.size() + " options");
            if (mSaveAfterLoad) saveLocked();
            evaluateLocked("settings loaded");
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

        registerPerAppReceivers();
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(EpdSettings.KEYGUARD_CLEAR), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        final boolean clear = readKeyguardClear();
                        synchronized (mLock) {
                            mKeyguardClear = clear;
                            evaluateLocked("keyguard clear=" + clear);
                        }
                    }
                }, UserHandle.USER_ALL);
        final boolean keyguardClear = readKeyguardClear();
        synchronized (mLock) {
            mKeyguardClear = keyguardClear;
        }
        // Per-app evaluation inputs (spec 05 section 6). Each reports its current
        // state when started, which also covers a restart of this process. The
        // camera watcher starts at "E-ink ready", when cameraserver is surely up.
        final boolean keyguard = queryKeyguardLocked();
        synchronized (mLock) {
            mKeyguardLocked = keyguard;
        }
        mTracker.start();
        mPlayback.start();
        grantNotificationListenerAccess();

        if ("1".equals(SystemProperties.get(EpdSettings.PROP_BOOT_COMPLETED))) {
            // Process restart after boot: no boot broadcast will come.
            scheduleEinkReady("process start after boot");
        }
    }

    /**
     * Spec 03 section 4: as a system app the service grants its own notification
     * listener access, so the user does not have to.
     */
    private void grantNotificationListenerAccess() {
        try {
            NotificationManager nm = mContext.getSystemService(NotificationManager.class);
            ComponentName listener = SleepNotificationListener.component(mContext);
            if (!nm.isNotificationListenerAccessGranted(listener)) {
                nm.setNotificationListenerAccessGranted(listener, true);
                Log.i(TAG, "Granted notification listener access to " + listener);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot grant notification listener access", e);
        }
    }

    /** From the notification listener (main thread): re-check the sleeping screen. */
    void onNotificationsChanged() {
        mHandler.post(() -> {
            if (mEinkReady) mStatus.onNotificationsChanged();
        });
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
        // Camera availability (spec 05 section 6.3); reports cameras already open.
        mCamera.start();
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

    /**
     * From clients. Spec 02 section 10 item 2: once the device is going to sleep
     * (no longer interactive, the screen-off broadcast still to come) a request
     * for a screen-on type is not applied, so E-ink only goes to -1 directly
     * as with the power button instead of passing through 0 ("Leaving E-ink
     * only") just before the screen-off. The caller is logged either way.
     */
    void setDisplayType(int requested) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        if (requested >= 0 && !isInteractive()) {
            Log.i(TAG, "setDisplayType(" + requested + ") from uid " + uid + " pid " + pid
                    + " ignored: going to sleep");
            return;
        }
        Log.i(TAG, "setDisplayType(" + requested + ") from uid " + uid + " pid " + pid);
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
            // Stock clears the camera/video locks when the screens go off; the
            // automatic camera lock is released too (spec 05, decision b).
            mModeLocks[LOCK_CAMERA] = false;
            mModeLocks[LOCK_VIDEO] = false;
            mSlideDragging = false;
            mHandler.post(mCamera::release);
            evaluateLocked("screen off");
        } else if (EpdSettings.isEinkType(type)) {
            evaluateLocked("display type " + type);
            // Re-read the front app and the keyguard, which may have changed
            // while the E-ink was not in use.
            mTracker.requestQuery();
        } else {
            evaluateLocked("display type " + type);
        }
        return true;
    }

    /** Hardware for a type (section 2.4). Handler thread, E-ink ready. */
    private void applyHardware(int type) {
        switch (type) {
            case EpdSettings.TYPE_EINK:
                mEinkOnly.enter();
                mBridge.reconnectEink();
                mRepainter.schedule("type 4");
                break;
            case EpdSettings.TYPE_MIRROR:
                mEinkOnly.leave();
                mBridge.reconnectEink();
                mRepainter.schedule("type 2");
                break;
            case EpdSettings.TYPE_LCD:
                mRepainter.cancel(); // leaving the E-ink
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
        // The E-ink lock screen (spec 05 section 6.2 step 1) must be known before
        // the type change evaluates the refresh mode.
        final boolean keyguard = queryKeyguardLocked();
        final boolean keyguardClear = readKeyguardClear();
        final int target;
        synchronized (mLock) {
            mKeyguardLocked = keyguard;
            mKeyguardClear = keyguardClear;
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
            if (EpdSettings.isEinkType(target)) {
                mWake.start(target, keyguard);
            } else {
                mWake.stop();
            }
            if (!changeTypeLocked(target, true)) {
                // Same type as before: the lock screen state may still have changed.
                evaluateLocked("screen on");
            }
            mWake.step("display type set to " + target + ", refresh mode " + mEpdMode
                    + " (" + mModeReason + ")");
        }
        mHandler.removeCallbacks(mWakeUserActivity);
        if (keyguard && EpdSettings.isEinkType(target)) {
            mHandler.postDelayed(mWakeUserActivity, WAKE_USER_ACTIVITY_MS);
        }
        // Spec 05 section 6.1: re-read the front app after every screen-on.
        mTracker.requestQuery();
        // Runs after the posted type change.
        mHandler.post(() -> {
            mEinkOnly.cancelScreenOnRepeats();
            if (!mEinkReady) return;
            if (target == EpdSettings.TYPE_EINK) {
                // Unconditionally, whatever woke the device (E-ink key, swapped
                // keys, fingerprint, ...) and whether or not it is marked applied.
                mEinkOnly.enterAfterScreenOn();
                mWake.step("E-ink-only setup applied");
                mBridge.reconnectEink();
                mWake.step("reconnect requested");
                mRepainter.schedule("wake into type 4");
                mEinkOnly.scheduleScreenOnRepeats();
            } else {
                applyHardware(target);
                if (target == EpdSettings.TYPE_MIRROR) mWake.step("reconnect requested (mirror)");
            }
        });
    }

    /** ACTION_SCREEN_OFF or onScreenChanged(false). Handler thread. */
    void handleScreenOff() {
        mWake.stop();
        mHandler.removeCallbacks(mWakeUserActivity);
        mHandler.post(mRepainter::cancel);
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

    private boolean isInteractive() {
        final long ident = Binder.clearCallingIdentity();
        try {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } finally {
            Binder.restoreCallingIdentity(ident);
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
            if (mEinkReady) {
                mBridge.setRefreshMode(mode);
                mWake.step("refresh mode " + mode + " written");
            }
        });
    }

    /** Settings.System a6l_epd_keyguard_clear of the current user (outside the lock). */
    private boolean readKeyguardClear() {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                EpdSettings.KEYGUARD_CLEAR, 0, UserHandle.USER_CURRENT) == 1;
    }

    /**
     * Spec 02 section 10 item 4c: about 1.5 s after a wake into the E-ink with the
     * keyguard showing, restart the keyguard's short screen-off timer once, now
     * that the E-ink has started drawing (handler thread).
     */
    private void wakeUserActivity() {
        final boolean eink;
        synchronized (mLock) {
            eink = EpdSettings.isEinkType(mDisplayType);
        }
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        if (!eink || pm == null || !pm.isInteractive()) return;
        pm.userActivity(SystemClock.uptimeMillis(), PowerManager.USER_ACTIVITY_EVENT_OTHER, 0);
        mWake.step("user activity (keyguard screen-off timer restarted)");
    }

    void setEpdDisplayMode(int mode) {
        if (mode < 0 || mode > EpdSettings.MODE_MAX) {
            Log.w(TAG, "Ignoring refresh mode " + mode);
            return;
        }
        synchronized (mLock) {
            mModeReason = "client setEpdDisplayMode (until the next evaluation)";
            mModeTrigger = "setEpdDisplayMode";
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
            evaluateLocked("updateEpdMode");
        }
    }

    /**
     * The refresh mode for the current state (spec 05 section 6.2, with the
     * product decisions recorded in QUESTIONS.md), from strongest to weakest:
     * camera lock; E-ink lock screen; video lock; slide-one-screen drag; fixed
     * rules (E-ink launcher, built-in video activities); the per-app entry of
     * the front app (activity class, exact package, longest substring); for a
     * helper app without one, the entry of the app that opened it; the default.
     * Keep-mode dialogs never get here (they are not recorded as the front).
     * On the LCD the mode is 0 (mirror); with the screens off it is left.
     */
    private void evaluateLocked(String trigger) {
        mModeTrigger = trigger;
        if (mDisplayType == EpdSettings.TYPE_OFF) {
            mModeReason = "screens off (mode left)";
        } else if (!EpdSettings.isEinkType(mDisplayType)) {
            mModeReason = "LCD in use";
            setEpdModeLocked(EpdSettings.MODE_MIRROR);
        } else {
            final String pkg = mFront != null ? mFront.pkg : mTopPackage;
            final String cls = mFront != null ? mFront.cls : null;
            final int mode;
            final String reason;
            String camera = cameraLockLocked(pkg);
            String video = camera == null ? videoLockLocked() : null;
            if (camera != null) {
                mode = EpdSettings.MODE_SMOOTH;
                reason = camera;
            } else if (mKeyguardLocked) {
                // Spec 02 section 10 item 4a: the default mode unless the user
                // chose the clear mode (a6l_epd_keyguard_clear=1).
                mode = mKeyguardClear ? EpdSettings.MODE_CLEAR : EpdSettings.MODE_DEFAULT_EINK;
                reason = "rule: E-ink lock screen (" + (mKeyguardClear ? "clear" : "default")
                        + " mode)";
            } else if (video != null) {
                mode = EpdSettings.MODE_SMOOTH;
                reason = video;
            } else if (mSlideDragging) {
                mode = EpdSettings.MODE_SMOOTH;
                reason = "slide one screen: finger dragging";
            } else if (EpdSettings.EINK_LAUNCHER.equals(pkg)) {
                mode = EpdSettings.MODE_CLEAR;
                reason = "rule: E-ink launcher";
            } else if (AppRules.isSmoothActivity(cls)) {
                mode = EpdSettings.MODE_SMOOTH;
                reason = "rule: built-in video activity";
            } else {
                AppRules.Match match = AppRules.match(mAppModes, pkg, cls);
                String via = "";
                if (match == null && mHostPackage != null) {
                    match = AppRules.match(mAppModes, mHostPackage, null);
                    via = " (helper app, follows " + mHostPackage + ")";
                }
                if (match != null) {
                    mode = match.mode;
                    reason = match.how + " '" + match.key + "'" + via;
                } else {
                    mode = EpdSettings.MODE_DEFAULT_EINK;
                    reason = "default" + via;
                }
            }
            mModeReason = reason;
            setEpdModeLocked(mode);
        }
        updateWantedLocked();
    }

    /** The camera lock's reason if it applies now, else null. */
    private String cameraLockLocked(String frontPkg) {
        // Home rule (spec 05 section 2.3): never while a home app is in front.
        if (mFrontIsHome) return null;
        if (mModeLocks[LOCK_CAMERA]) return "camera lock (client)";
        if (!getOptionLocked(EpdSettings.OPTION_AUTO_CAMERA)) return null;
        // Decision b: only while the app using the camera is in front. An
        // unknown opener (cameras already open when this process started)
        // counts as the front app.
        for (Map.Entry<String, String> e : mOpenCameras.entrySet()) {
            String opener = e.getValue();
            if (opener.isEmpty() || opener.equals(frontPkg) || opener.equals(mHostPackage)) {
                return "camera lock (camera " + e.getKey() + " open by "
                        + (opener.isEmpty() ? "unknown" : opener) + ")";
            }
        }
        return null;
    }

    /** The video lock's reason if it applies now, else null. */
    private String videoLockLocked() {
        if (mModeLocks[LOCK_VIDEO]) return "video lock (client)";
        if (!getOptionLocked(EpdSettings.OPTION_AUTO_VIDEO)) return null;
        if (mFrontUid >= 0 && mVideoUids.contains(mFrontUid)) {
            return "video lock (front app uid " + mFrontUid + " plays video-like audio)";
        }
        return null;
    }

    /** Starts or stops the slide monitor and the safety-net polling as needed. */
    private void updateWantedLocked() {
        final boolean eink = EpdSettings.isEinkType(mDisplayType);
        String pkg = mFront != null ? mFront.pkg : mTopPackage;
        boolean slide = eink && !mKeyguardLocked && (mSlide.contains(pkg)
                || (mHostPackage != null && mSlide.contains(mHostPackage)));
        if (slide != mSlideWanted) {
            mSlideWanted = slide;
            mHandler.post(() -> mSlideMonitor.setEnabled(slide));
        }
        if (eink != mPollWanted) {
            mPollWanted = eink;
            mHandler.post(() -> mTracker.setPolling(eink));
        }
    }

    /**
     * From the slide monitor (handler thread). Spec 05 section 8 decision c:
     * a fast mode only while the finger drags; no extra refresh afterwards.
     */
    private void setSlideDragging(boolean dragging) {
        synchronized (mLock) {
            if (dragging == mSlideDragging) return;
            mSlideDragging = dragging;
            evaluateLocked(dragging ? "slide drag start" : "slide drag end");
        }
    }

    void lockEpdMode(int type, boolean on) {
        if (type != LOCK_CAMERA && type != LOCK_VIDEO) return;
        synchronized (mLock) {
            mModeLocks[type] = on;
            evaluateLocked("lockEpdMode(" + type + ", " + on + ")");
        }
    }

    void addAppMode(String pkg, int mode) {
        if (pkg == null || pkg.isEmpty() || mode < EpdSettings.MODE_APP_MIN
                || mode > EpdSettings.MODE_MAX || EpdSettings.EINK_LAUNCHER.equals(pkg)) {
            Log.w(TAG, "addAppMode refused: " + pkg + " " + mode);
            return;
        }
        synchronized (mLock) {
            Integer old = mAppModes.put(pkg, mode);
            if (old == null || old != mode) {
                Log.i(TAG, "addAppMode " + pkg + " " + old + " -> " + mode + " (uid "
                        + Binder.getCallingUid() + ")");
                saveLocked();
            }
            evaluateLocked("addAppMode " + pkg);
        }
    }

    void removeAppMode(String pkg) {
        if (pkg == null) return;
        synchronized (mLock) {
            if (mAppModes.remove(pkg) != null) {
                Log.i(TAG, "removeAppMode " + pkg + " (uid " + Binder.getCallingUid() + ")");
                saveLocked();
                evaluateLocked("removeAppMode " + pkg);
            }
        }
    }

    Map<String, Integer> getAllAppMode() {
        synchronized (mLock) {
            return new HashMap<>(mAppModes);
        }
    }

    private void saveLocked() {
        if (!mStoreLoaded) {
            mSaveAfterLoad = true;
            return;
        }
        final AppModeStore.Data data = new AppModeStore.Data();
        data.modes.putAll(mAppModes);
        data.slide.addAll(mSlide);
        data.options.putAll(mOptions);
        mHandler.post(() -> mStore.save(data));
    }

    // ---------------------------------------------------------------------------------------
    // Own per-app options and switches (spec 05 section 6.4), for EinkOptionsProvider

    private boolean getOptionLocked(String name) {
        Boolean value = mOptions.get(name);
        return value == null || value; // both switches default to on
    }

    boolean getOption(String name) {
        synchronized (mLock) {
            return getOptionLocked(name);
        }
    }

    /** Returns false for an unknown option. */
    boolean setOption(String name, boolean on) {
        if (!EpdSettings.OPTION_AUTO_CAMERA.equals(name)
                && !EpdSettings.OPTION_AUTO_VIDEO.equals(name)) {
            return false;
        }
        synchronized (mLock) {
            if (getOptionLocked(name) == on) return true;
            mOptions.put(name, on);
            saveLocked();
            evaluateLocked("option " + name + "=" + on);
        }
        return true;
    }

    String[] getSlidePackages() {
        synchronized (mLock) {
            return mSlide.toArray(new String[0]);
        }
    }

    void setSlide(String pkg, boolean on) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (mLock) {
            boolean changed = on ? mSlide.add(pkg) : mSlide.remove(pkg);
            if (!changed) return;
            saveLocked();
            evaluateLocked("slide " + pkg + "=" + on);
        }
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

    /**
     * begigResumeActivity / notifyResumedPackage (spec 05 section 6.1): no 18.1
     * component calls them; a call is taken as a hint to look at the front now.
     */
    void onResumeHint(String pkg) {
        if (pkg != null) mTracker.requestQuery();
    }

    /** From the tracker after every query (handler thread). */
    private void onFrontObserved(ForegroundTracker.Front front) {
        final boolean keyguard = queryKeyguardLocked();
        if (AppRules.isKeepModeActivity(front.cls)) {
            // A dialog over the app: no change, and not the new front app.
            synchronized (mLock) {
                mKeepModeFront = front;
                if (keyguard != mKeyguardLocked) {
                    mKeyguardLocked = keyguard;
                    evaluateLocked("keyguard " + (keyguard ? "shown" : "gone"));
                }
            }
            return;
        }
        final ForegroundTracker.Front old;
        synchronized (mLock) {
            old = mFront;
        }
        final boolean newActivity = !front.sameActivity(old);
        // Facts about a new front app, read outside the lock.
        int uid = -1;
        boolean home = false;
        boolean helper = false;
        if (newActivity) {
            uid = packageUid(front.pkg, front.userId);
            home = isHomePackage(front.pkg, front.userId);
            helper = !home && !hasLauncherActivity(front.pkg, front.userId);
        }
        synchronized (mLock) {
            mKeepModeFront = null;
            final boolean keyguardChanged = keyguard != mKeyguardLocked;
            mKeyguardLocked = keyguard;
            if (!newActivity) {
                if (keyguardChanged) evaluateLocked("keyguard " + (keyguard ? "shown" : "gone"));
                return;
            }
            final boolean newPackage = !front.samePackage(old);
            if (newPackage) {
                // A helper follows the app that opened it (spec 05 section 2.2),
                // and keeps following that app's host if it came from a helper.
                if (helper && old != null) {
                    mHostPackage = mFrontIsHelper && mHostPackage != null
                            ? mHostPackage : old.pkg;
                } else {
                    mHostPackage = null;
                }
            }
            final String oldCls = old != null ? old.cls : null;
            mFront = front;
            mTopPackage = front.pkg;
            mFrontUid = uid;
            mFrontIsHome = home;
            mFrontIsHelper = helper;
            // The per-app mode is re-applied when the front package changes; on an
            // activity change inside the package only if an activity rule is involved.
            if (newPackage || keyguardChanged
                    || AppRules.hasActivityRule(mAppModes, oldCls)
                    || AppRules.hasActivityRule(mAppModes, front.cls)) {
                evaluateLocked(newPackage ? "front app " + front : "front activity " + front);
            }
        }
    }

    private void onCamerasChanged(Map<String, String> open) {
        synchronized (mLock) {
            if (open.equals(mOpenCameras)) return;
            mOpenCameras = open;
            evaluateLocked("camera " + open);
        }
    }

    private void onPlaybackChanged(Set<Integer> uids) {
        synchronized (mLock) {
            if (uids.equals(mVideoUids)) return;
            mVideoUids = uids;
            evaluateLocked("playback " + uids);
        }
    }

    /** ACTION_USER_PRESENT: the lock screen is gone (handler thread). */
    private void onUserPresent() {
        final boolean keyguard = queryKeyguardLocked();
        synchronized (mLock) {
            mKeyguardLocked = keyguard;
            evaluateLocked("user present");
        }
        mTracker.requestQuery();
    }

    private boolean queryKeyguardLocked() {
        try {
            KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read the keyguard state", e);
            return false;
        }
    }

    private int packageUid(String pkg, int userId) {
        try {
            return mContext.getPackageManager().getPackageUidAsUser(pkg, userId);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return -1;
        }
    }

    /** The user's default home app, or one of the stock home apps. */
    private boolean isHomePackage(String pkg, int userId) {
        if (AppRules.STOCK_HOME_PACKAGES.contains(pkg)) return true;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = mContext.getPackageManager().resolveActivityAsUser(home,
                    PackageManager.MATCH_DEFAULT_ONLY, userId);
            return ri != null && ri.activityInfo != null
                    && pkg.equals(ri.activityInfo.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // handler: launcher-activity cache, cleared on every package change
    private final Map<String, Boolean> mLauncherCache = new HashMap<>();

    private boolean hasLauncherActivity(String pkg, int userId) {
        final String key = userId + ":" + pkg;
        Boolean cached = mLauncherCache.get(key);
        if (cached != null) return cached;
        boolean result = true; // on error, treat as a normal app
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(pkg);
            List<ResolveInfo> list = mContext.getPackageManager().queryIntentActivitiesAsUser(
                    launcher, PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
            result = list != null && !list.isEmpty();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot query launcher activities of " + pkg, e);
        }
        mLauncherCache.put(key, result);
        return result;
    }

    private void registerPerAppReceivers() {
        IntentFilter present = new IntentFilter(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onUserPresent();
            }
        }, UserHandle.ALL, present, null, mHandler);

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packages.addDataScheme("package");
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mLauncherCache.clear();
                if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())
                        && intent.getData() != null) {
                    onPackageFullyRemoved(intent.getData().getSchemeSpecificPart());
                }
            }
        }, UserHandle.ALL, packages, null, mHandler);
    }

    /**
     * Spec 05 section 6.4: drop the entry and the slide flag of a package that
     * is no longer installed for any user (the map is device-wide).
     */
    private void onPackageFullyRemoved(String pkg) {
        if (pkg == null) return;
        try {
            mContext.getPackageManager().getPackageInfo(pkg, PackageManager.MATCH_ANY_USER);
            return; // still installed for another user
        } catch (PackageManager.NameNotFoundException e) {
            // gone everywhere
        }
        synchronized (mLock) {
            boolean changed = mAppModes.remove(pkg) != null;
            changed |= mSlide.remove(pkg);
            if (changed) {
                Log.i(TAG, "Removed the E-ink settings of uninstalled " + pkg);
                saveLocked();
                evaluateLocked("package removed " + pkg);
            }
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

    /**
     * Recorded for dumpsys only. Rear touch gestures are not wanted (spec 02
     * section 9.3): nothing writes /sys/ctp1/ctp_func/gesture.
     */
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
            pw.println("E-ink service (hk-hisense-a6l-eink)");
            pw.println("  display type: " + mDisplayType + ", previous: " + mPreviousType
                    + ", last screen-on: " + mLastScreenOnType);
            pw.println("  e-ink ready: " + mEinkReady + " (scheduled: " + mReadyScheduled + ")");
            pw.println("  display enabled: lcd=" + mDisplayEnabled[0]
                    + " eink=" + mDisplayEnabled[1]);
            pw.println("  refresh mode: " + mEpdMode + " (smooth=" + EpdSettings.MODE_SMOOTH
                    + ", clear=" + EpdSettings.MODE_CLEAR + ")");
            pw.println("  mode reason: " + mModeReason);
            pw.println("  last evaluation trigger: " + mModeTrigger);
            pw.println("  top package: " + mTopPackage);
            pw.println("  front: " + (mFront != null ? mFront : "unknown") + ", uid " + mFrontUid
                    + (mFrontIsHome ? ", home" : "") + (mFrontIsHelper ? ", helper" : "")
                    + (mHostPackage != null ? ", follows " + mHostPackage : ""));
            if (mKeepModeFront != null) {
                pw.println("  keep-mode dialog on top: " + mKeepModeFront);
            }
            pw.println("  keyguard locked: " + mKeyguardLocked + ", clear mode on it: "
                    + mKeyguardClear);
            pw.println("  " + mWake.dumpState());
            pw.println("  client locks: camera=" + mModeLocks[LOCK_CAMERA]
                    + " video=" + mModeLocks[LOCK_VIDEO]);
            pw.println("  auto switches: camera=" + getOptionLocked(EpdSettings.OPTION_AUTO_CAMERA)
                    + " video=" + getOptionLocked(EpdSettings.OPTION_AUTO_VIDEO));
            pw.println("  locking cameras: " + mOpenCameras + ", video uids: " + mVideoUids);
            pw.println("  slide one screen (experimental): " + mSlide + ", dragging: "
                    + mSlideDragging);
            pw.println("  fixed rules: keep-mode " + AppRules.keepModeActivities()
                    + ", smooth " + AppRules.smoothActivities() + ", stock home "
                    + AppRules.STOCK_HOME_PACKAGES);
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
            pw.println("  foreground tracker: " + mTracker.dumpState());
            pw.println("  camera watcher: " + mCamera.dumpState());
            pw.println("  playback watcher: " + mPlayback.dumpState());
            pw.println("  slide monitor: " + mSlideMonitor.dumpState());
            pw.println("  " + mRepainter.dumpState());
            mEinkOnly.dump(pw);
            mStatus.dump(pw);
        }, 1000);
    }
}
