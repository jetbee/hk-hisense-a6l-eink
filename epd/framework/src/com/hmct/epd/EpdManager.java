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

package com.hmct.epd;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * Client-side handle to the E-ink ("epd") system service.
 *
 * <p>Obtained with {@code context.getSystemService("epd")} on devices where
 * {@code ro.hmct.epd.support} is {@code "1"}. Every instance method performs a
 * single synchronous call on {@link IEpdManager}; a {@link RemoteException} is
 * swallowed and the method returns the documented failure value instead.
 *
 * <p>Public names, values and overloads (including misspellings) follow the
 * compatibility facts of clean-room spec 01 and must not be changed.
 */
public class EpdManager {

    // ---------------------------------------------------------------------
    // Public constants (compatibility facts, spec 01 section 2)
    // ---------------------------------------------------------------------

    public static final String ACTION_DISPLAY_CHANGED = "com.hmct.action.displaychanged";
    public static final String ACTION_READING_TOMEOUT = "com.hmct.action.reading.timeout";

    public static final int DEFAULT_GESTURE_UNLOCK = 0;

    public static final int DISPLAY_MODE_SCREEN_OFF = -1;
    public static final int DISPLAY_MODE_PRIMARY_ONLY = 0;
    public static final int DISPLAY_MODE_PRIMARY_WITH_PRESENTATION = 1;
    public static final int DISPLAY_MODE_WITH_PRESENTATION = 1;
    public static final int DISPLAY_MODE_EXTERNAL_MIRROR = 2;
    public static final int DISPLAY_MODE_EXTERNAL_EPAPER = 4;
    public static final int DISPLAY_MODE_SWITCHPOLICY_ENABLE = 256;

    public static final String DISPLAY_TYPE_PROPERTY = "sys.sysctl.display_type";
    public static final String DISPLAY_TYPE_SETTINGS = "current_display_type";

    public static final String EINK_HOME = "com.hmct.einklauncher";
    public static final String EINK_HOME_CLASSNAME = "com.hmct.einklauncher.Launcher";

    public static final boolean EPD_DEBUG = true;

    public static final int EPD_MODE_MIRROR = 0;
    public static final int EPD_MODE_TYPING = 1;
    public static final int EPD_MODE_PICTURE = 2;
    public static final int EPD_MODE_READING = 3;
    public static final int EPD_MODE_FAST = 6;
    public static final int EPD_MODE_VIDEO = 8;

    public static final int EPD_READING_TIMEOUT_DURATION = 5000;
    public static final String EPD_TAG = "EpdManager";

    public static final int EXTERNAL_DISPLAY_WIDTH = 720;
    public static final int EXTERNAL_DISPLAY_HEIGHT = 1440;
    public static final int EXTERNAL_DISPLAY_UPDATE_NORMAL = 0;
    public static final int EXTERNAL_DISPLAY_UPDATE_BITMAP = 1;

    public static final int EXTERNAL_THEME_MODE_LIGHT = 0;
    public static final int EXTERNAL_THEME_MODE_DARK = 1;

    public static final String EXTRA_INT = "display_type";

    public static final int LISTENER_TYPE_LISTENER = 0;
    public static final int LISTENER_TYPE_REQUEST = 1;

    public static final int MAX_PRIMARY_ANGLE = 110;

    public static final int PRIORITY_EXTERNAL_VIEWS_KEYGUARD = 0;
    public static final int PRIORITY_EXTERNAL_VIEWS_COVER = 1;
    public static final int PRIORITY_EXTERNAL_VIEWS_MUSIC = 2;
    public static final int PRIORITY_EXTERNAL_VIEWS_CALL = 3;
    public static final int PRIORITY_EXTERNAL_VIEWS_SIZE = 4;

    /** Known reading apps. Deliberately a mutable list, as on stock. */
    public static final ArrayList<String> READING_APP_LIST = new ArrayList<>(Arrays.asList(
            "com.chaozh.iReader",
            "com.chaozh.iReader.siswoo",
            "com.duokan.reader",
            "com.zhangyue.iReader",
            "com.tencent.weread",
            "com.jingdong.app.reader"));

    /** Deliberately a mutable list holding one empty string, as on stock. */
    public static final ArrayList<String> SHOW_WHEN_UNLOCKED_APP =
            new ArrayList<>(Arrays.asList(""));

    public static final int TIMEOUT_WITHOUT_KEYGUARD = 120001;

    public static final int TYPE_EINK_CONTRAST = 0;
    public static final int TYPE_EINK_BLACK = 1;
    public static final int TYPE_EINK_WHITE = 2;

    public static final int TYPE_EPDLOCK_CAMERA = 0;
    public static final int TYPE_EPDLOCK_VIDEO = 1;
    public static final int TYPE_EPDLOCK_SIZE = 2;

    public static final int WAKEUP_SOURCE_FROM_SENSOR = 0;
    public static final int WAKEUP_SORUCE_FROM_USER = 1;
    public static final int WAKEUP_SOURCE_FROM_SENSOR_WITH_LIGHT = 3;

    // ---------------------------------------------------------------------
    // Direction listener types
    // ---------------------------------------------------------------------

    /** Receives direction (display type) detection results from the service. */
    public interface DetectCompleteLisenter {
        void onDetectComplete(int mode, long requestTime);
    }

    /**
     * Binder callback handed to the service by
     * {@link #registerDirectionChangeListener}. Forwards incoming calls to the
     * listener(s) stored in the enclosing manager.
     */
    public class DirectionDetect extends IDirectionDetect.Stub {
        private final int mListenerType;
        private final long mRequestTime;

        public DirectionDetect(int type, long time) {
            mListenerType = type;
            mRequestTime = time;
        }

        @Override
        public void onDirectionChanged(int mode, long requestTime) {
            dispatchDirectionChanged(mode, requestTime);
        }

        @Override
        public int getListenerType() {
            return mListenerType;
        }

        @Override
        public long getRequestTime() {
            return mRequestTime;
        }
    }

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private static final int PANEL_MASK = ~DISPLAY_MODE_WITH_PRESENTATION;

    private final IEpdManager mService;

    private final Object mListenerLock = new Object();
    private DirectionDetect mCallback;              // guarded by mListenerLock
    private DetectCompleteLisenter mListener;       // guarded by mListenerLock
    private DetectCompleteLisenter mOneShotListener; // guarded by mListenerLock; no public setter

    /**
     * Called by the framework's system-service registry. {@code context} is
     * accepted for compatibility and not used. No binder calls are made here.
     */
    public EpdManager(IEpdManager service, Context context) {
        mService = service;
    }

    // ---------------------------------------------------------------------
    // Pure helpers
    // ---------------------------------------------------------------------

    /** True iff {@code type} selects the E-ink panel (2, 3, 4 or 5). */
    public static boolean isExternalDisplayOn(int type) {
        if (type < 0) {
            return false;
        }
        final int panel = type & PANEL_MASK;
        return panel == DISPLAY_MODE_EXTERNAL_MIRROR || panel == DISPLAY_MODE_EXTERNAL_EPAPER;
    }

    /** True iff {@code type} selects the LCD panel (0 or 1). */
    public static boolean isPrimaryDisplayOn(int type) {
        return type >= 0 && (type & PANEL_MASK) == DISPLAY_MODE_PRIMARY_ONLY;
    }

    // ---------------------------------------------------------------------
    // Content on the E-ink panel
    // ---------------------------------------------------------------------

    public void addViewOnExternal(RemoteViews views) {
        addViewOnExternal(views, PRIORITY_EXTERNAL_VIEWS_KEYGUARD);
    }

    public void addViewOnExternal(RemoteViews views, int priority) {
        addViewOnExternal(views, priority, EPD_MODE_PICTURE);
    }

    public void addViewOnExternal(RemoteViews views, int priority, int epdMode) {
        addViewOnExternal(views, priority, epdMode, 0);
    }

    public void addViewOnExternal(RemoteViews views, int priority, int epdMode, int minShowTime) {
        if (mService == null) return;
        try {
            mService.addViewToPresentationDisplay(views, priority, epdMode, minShowTime, false);
        } catch (RemoteException ignored) {
        }
    }

    public void addBitmapOnExternal(Bitmap bitmap) {
        addBitmapOnExternal(bitmap, EPD_MODE_PICTURE);
    }

    public void addBitmapOnExternal(Bitmap bitmap, int epdMode) {
        addBitmapOnExternal(bitmap, PRIORITY_EXTERNAL_VIEWS_KEYGUARD, epdMode, 0);
    }

    public void addBitmapOnExternal(Bitmap bitmap, int priority, int epdMode, int minShowTime) {
        addBitmapOnExternal(bitmap, priority, epdMode, minShowTime, false);
    }

    public void addBitmapOnExternal(Bitmap bitmap, int priority, int epdMode, int minShowTime,
            boolean withBar) {
        addBitmapOnExternal(bitmap, priority, epdMode, minShowTime, withBar, null);
    }

    public void addBitmapOnExternal(Bitmap bitmap, int priority, int epdMode, int minShowTime,
            boolean withBar, String extra) {
        if (mService == null) return;
        try {
            mService.addBitmapToPresentationDisplayWithString(bitmap, priority, epdMode,
                    minShowTime, withBar, extra);
        } catch (RemoteException ignored) {
        }
    }

    public void removeViewOnExternal() {
        removeViewOnExternal(PRIORITY_EXTERNAL_VIEWS_KEYGUARD);
    }

    public void removeViewOnExternal(int priority) {
        if (mService == null) return;
        try {
            mService.removeViewOnPresentationDisplay(priority);
        } catch (RemoteException ignored) {
        }
    }

    public void updateExteralPaper() {
        if (mService == null) return;
        try {
            mService.updateExteralPaper();
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Pushes a final image to the E-ink panel. On the stock service this
     * freezes the E-ink panel for the rest of the boot.
     */
    public void updateShutdownWallPaper(Bitmap bitmap) {
        if (mService == null) return;
        try {
            mService.updateShutdownWallPaper(bitmap);
        } catch (RemoteException ignored) {
        }
    }

    /**
     * Kept for compatibility: despite the name this behaves exactly like
     * {@link #updateShutdownWallPaper(Bitmap)}; {@code page} is ignored.
     */
    public void addPreViewPage(int page, Bitmap bitmap) {
        Log.d(EPD_TAG, "addPreViewPage(bitmap) -> updateShutdownWallPaper, page=" + page);
        updateShutdownWallPaper(bitmap);
    }

    public void addPreViewPage(int type, RemoteViews view) {
        if (mService == null) return;
        try {
            mService.addPreViewPage(type, view);
        } catch (RemoteException ignored) {
        }
    }

    public void forceClear() {
        if (mService == null) return;
        try {
            mService.forceClear();
        } catch (RemoteException ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Display type
    // ---------------------------------------------------------------------

    /** Signature must stay exactly {@code setDisplayType(int)}; called by reflection. */
    public void setDisplayType(int type) {
        setDisplayType(type, false);
    }

    public void setDisplayType(int type, boolean isAllowExitFromReadOnly) {
        if (mService == null) return;
        try {
            mService.setDisplayType(type, isAllowExitFromReadOnly);
        } catch (RemoteException ignored) {
        }
    }

    /** Throws {@link NullPointerException} if {@code type} is null. */
    public void goToExternalForce(Integer type) {
        setDisplayType(type.intValue());
    }

    public int getDisplayType() {
        if (mService == null) return DISPLAY_MODE_PRIMARY_ONLY;
        try {
            return mService.getDisplayType();
        } catch (RemoteException e) {
            return DISPLAY_MODE_PRIMARY_ONLY;
        }
    }

    public boolean isExternalDisplayOn() {
        if (mService == null) return false;
        try {
            return mService.isExternalType();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * True only if the current display type is exactly 2 or 4. Never actually
     * throws; the clause is kept for source compatibility.
     */
    public boolean isExternalDisplayAlready() throws RemoteException {
        final int type = getDisplayType();
        return type == DISPLAY_MODE_EXTERNAL_MIRROR || type == DISPLAY_MODE_EXTERNAL_EPAPER;
    }

    public int getLastScreenOnType() {
        if (mService == null) return DISPLAY_MODE_PRIMARY_ONLY;
        try {
            return mService.getLastScreenOnType();
        } catch (RemoteException e) {
            return DISPLAY_MODE_PRIMARY_ONLY;
        }
    }

    public void setDisplayEnabled(int id, boolean enabled) {
        if (mService == null) return;
        try {
            mService.setDisplayEnabled(id, enabled);
        } catch (RemoteException ignored) {
        }
    }

    public boolean getDisplayEnabled(int id) {
        if (mService == null) return true;
        try {
            return mService.getDisplayEnabled(id);
        } catch (RemoteException e) {
            return true;
        }
    }

    public void setDisplayPowerMode(int displayId, boolean enabled) {
        if (mService == null) return;
        try {
            mService.setDisplayPowerMode(displayId, enabled);
        } catch (RemoteException ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Screen on/off and wake-up source
    // ---------------------------------------------------------------------

    public void onScreenChanged(boolean isScreenOn) {
        if (mService == null) return;
        try {
            mService.onScreenChanged(isScreenOn);
        } catch (RemoteException ignored) {
        }
    }

    public void requestGoToSleep() {
        if (mService == null) return;
        try {
            mService.requestGoToSleep();
        } catch (RemoteException ignored) {
        }
    }

    public void startWakingUp() {
        if (mService == null) return;
        try {
            mService.startWakingUp();
        } catch (RemoteException ignored) {
        }
    }

    /** {@code goHomeEnabled} is derived: true only for a sensor wake-up. */
    public void setWakeupSourceType(int sourceType, int display) {
        setWakeupSourceType(sourceType, display, sourceType == WAKEUP_SOURCE_FROM_SENSOR);
    }

    public void setWakeupSourceType(int sourceType, int display, boolean goHomeEnabled) {
        if (mService == null) return;
        try {
            mService.setWakeupSourceType(sourceType, display, goHomeEnabled);
        } catch (RemoteException ignored) {
        }
    }

    public int getWakeupSourceType() {
        if (mService == null) return -1;
        try {
            return mService.getWakeupSourceType();
        } catch (RemoteException e) {
            return -1;
        }
    }

    public void lockWakeupSource() {
        if (mService == null) return;
        try {
            mService.lockWakeupSource();
        } catch (RemoteException ignored) {
        }
    }

    public void unLockWakeupSource() {
        if (mService == null) return;
        try {
            mService.unLockWakeupSource();
        } catch (RemoteException ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Panel refresh mode
    // ---------------------------------------------------------------------

    public int getEpdMode() {
        if (mService == null) return EPD_MODE_FAST;
        try {
            return mService.getEpdMode();
        } catch (RemoteException e) {
            return EPD_MODE_FAST;
        }
    }

    public void setEpdDisplayMode(int mode) {
        if (mService == null) return;
        try {
            mService.setEpdDisplayMode(mode);
        } catch (RemoteException ignored) {
        }
    }

    public void updateEpdMode() {
        if (mService == null) return;
        try {
            mService.updateEpdMode();
        } catch (RemoteException ignored) {
        }
    }

    public void lockEpdMode(int type, boolean on) {
        if (mService == null) return;
        try {
            mService.lockEpdMode(type, on);
        } catch (RemoteException e) {
            Log.e(EPD_TAG, "notifyCameraOn: lockEpdMode(" + type + ", " + on + ") failed", e);
        }
    }

    public void notifyCameraOn(boolean on) {
        lockEpdMode(TYPE_EPDLOCK_CAMERA, on);
    }

    public void addAppMode(String pkg, int mode) {
        if (mService == null) return;
        try {
            mService.addAppMode(pkg, mode);
        } catch (RemoteException ignored) {
        }
    }

    public void removeAppMode(String pkg) {
        if (mService == null) return;
        try {
            mService.removeAppMode(pkg);
        } catch (RemoteException ignored) {
        }
    }

    /** Package name to boxed Integer refresh mode, or null on failure. */
    @SuppressWarnings("rawtypes")
    public Map getAllAppMode() {
        if (mService == null) return null;
        try {
            return mService.getAllAppMode();
        } catch (RemoteException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Foreground app tracking
    // ---------------------------------------------------------------------

    public void begigResumeActivity(String packageName, String claseName) {
        if (mService == null) return;
        try {
            mService.begigResumeActivity(packageName, claseName);
        } catch (RemoteException ignored) {
        }
    }

    public void notifyResumedPackage(String name) {
        if (mService == null) return;
        try {
            mService.notifyResumedPackage(name);
        } catch (RemoteException ignored) {
        }
    }

    public String getTopPackage() {
        if (mService == null) return null;
        try {
            return mService.getTopPackage();
        } catch (RemoteException e) {
            return null;
        }
    }

    public boolean isTopActivity(String packageName) {
        if (packageName == null) {
            Log.e(EPD_TAG, "isTopActivity: null package name");
            return false;
        }
        return packageName.equals(getTopPackage());
    }

    // ---------------------------------------------------------------------
    // Keyguard, theme, gestures, touch
    // ---------------------------------------------------------------------

    public void setEinkKeyguardMode(int mode) {
        if (mService == null) return;
        try {
            mService.setEinkKeyguardMode(mode);
        } catch (RemoteException ignored) {
        }
    }

    public void setEinkThemeMode(int mode) {
        if (mService == null) return;
        try {
            mService.setEinkThemeMode(mode);
        } catch (RemoteException ignored) {
        }
    }

    /** Kept for compatibility: despite the name this sets E-ink theme mode 2. */
    public void doPageDown() {
        Log.d(EPD_TAG, "doPageDown -> setEinkThemeMode(2)");
        setEinkThemeMode(2);
    }

    public void setGestureUnlock(int enabled) {
        if (mService == null) return;
        try {
            mService.setGestureUnlock(enabled);
        } catch (RemoteException ignored) {
        }
    }

    public void setExternalTpEnabled(boolean enabled) {
        if (mService == null) return;
        try {
            mService.setExternalTpEnabled(enabled);
        } catch (RemoteException ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // E-ink tuning
    // ---------------------------------------------------------------------

    public int getEinkEffect(int type) {
        if (mService == null) return -1;
        try {
            return mService.getEinkEffect(type);
        } catch (RemoteException e) {
            return -1;
        }
    }

    public void adjustEinkEffect(int type, int value) {
        if (mService == null) return;
        try {
            mService.adjustEinkEffect(type, value);
        } catch (RemoteException ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Boot and dual screen
    // ---------------------------------------------------------------------

    public void finishBoot() {
        if (mService == null) return;
        try {
            mService.finishBoot();
        } catch (RemoteException ignored) {
        }
    }

    public int getDualScreenTaskState() {
        if (mService == null) return -1;
        try {
            return mService.getDualScreenTaskState();
        } catch (RemoteException e) {
            return -1;
        }
    }

    public void setDualScreenEnabled(boolean enabled, boolean flag) {
        if (mService == null) return;
        try {
            mService.setDualScreenEnabled(enabled, flag);
        } catch (RemoteException ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Direction listener
    // ---------------------------------------------------------------------

    /**
     * Stores {@code listener} as this manager's only direction listener and,
     * the first time, registers a binder callback with the service. If the
     * registration call fails the listener is not stored.
     */
    public void registerDirectionChangeListener(DetectCompleteLisenter listener) {
        synchronized (mListenerLock) {
            if (mCallback == null) {
                final DirectionDetect callback = new DirectionDetect(LISTENER_TYPE_LISTENER,
                        SystemClock.currentThreadTimeMillis());
                if (mService == null) return;
                try {
                    mService.registerDirectionChangeListener(callback);
                } catch (RemoteException e) {
                    return;
                }
                mCallback = callback;
            } else {
                Log.e(EPD_TAG, "direction callback already registered; replacing listener only");
            }
            mListener = listener;
        }
    }

    /**
     * Unregisters the binder callback, if any, and forgets the stored
     * listener. {@code listener} is ignored. If the call fails nothing is
     * forgotten.
     */
    public void unregisterDirectionChangeListener(DetectCompleteLisenter listener) {
        synchronized (mListenerLock) {
            if (mCallback == null) {
                Log.d(EPD_TAG, "no direction callback registered");
                return;
            }
            if (mService == null) return;
            try {
                mService.unregisterDirectionChangeListener(mCallback);
            } catch (RemoteException e) {
                return;
            }
            mCallback = null;
            mListener = null;
        }
    }

    /** Runs on a binder thread when the service reports a direction. */
    private void dispatchDirectionChanged(int mode, long requestTime) {
        final DetectCompleteLisenter persistent;
        final DetectCompleteLisenter oneShot;
        synchronized (mListenerLock) {
            persistent = mListener;
            oneShot = mOneShotListener;
            if (oneShot != null && requestTime > 0) {
                mOneShotListener = null;
            }
        }
        if (EPD_DEBUG) {
            Log.d(EPD_TAG, "onDirectionChanged mode=" + mode + " requestTime=" + requestTime);
        }
        if (persistent != null) {
            persistent.onDetectComplete(mode, requestTime);
        }
        if (oneShot != null) {
            oneShot.onDetectComplete(mode, requestTime);
        }
        if (EPD_DEBUG) {
            Log.d(EPD_TAG, "onDirectionChanged dispatched");
        }
    }
}
