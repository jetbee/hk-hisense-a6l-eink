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

import android.graphics.Bitmap;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.RemoteCallbackList;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.util.DumpUtils;
import com.hmct.epd.IDirectionDetect;
import com.hmct.epd.IEpdManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;

/**
 * The {@code epd} binder object: every {@link IEpdManager} method of spec 01
 * section 3.1 with the behaviour of spec 02 section 3. No method checks a
 * permission (stock apps depend on that); only dumpsys needs DUMP.
 * Methods do not block: they take the state lock or post to the handler.
 */
final class EpdBinder extends IEpdManager.Stub {
    private static final String TAG = EpdService.TAG;

    private final EpdService mService;
    private final RemoteCallbackList<IDirectionDetect> mDirectionCallbacks =
            new RemoteCallbackList<>();

    EpdBinder(EpdService service) {
        mService = service;
    }

    // --- Presentation content (section 3.6): accepted and ignored. ---

    @Override
    public void addViewToPresentationDisplay(RemoteViews views, int priority, int epdMode,
            int minShowTime, boolean withBar) {
        logIgnored("addViewToPresentationDisplay");
    }

    @Override
    public void addBitmapToPresentationDisplayWithString(Bitmap bitmap, int priority, int epdMode,
            int minShowTime, boolean withBar, String extra) {
        logIgnored("addBitmapToPresentationDisplayWithString");
    }

    @Override
    public void addBitmapToPresentationDisplay(Bitmap bitmap, int priority, int epdMode,
            int minShowTime, boolean withBar) {
        logIgnored("addBitmapToPresentationDisplay");
    }

    @Override
    public void updateShutdownWallPaper(Bitmap bitmap) {
        logIgnored("updateShutdownWallPaper");
    }

    @Override
    public void removeViewOnPresentationDisplay(int priority) {
        logIgnored("removeViewOnPresentationDisplay");
    }

    @Override
    public void updateExteralPaper() {
        logIgnored("updateExteralPaper");
    }

    @Override
    public void addPreViewPage(int type, RemoteViews views) {
        logIgnored("addPreViewPage");
    }

    private static void logIgnored(String method) {
        Log.d(TAG, method + " from uid " + Binder.getCallingUid() + " ignored (no slots)");
    }

    // --- Dual screen (section 3.5). ---

    @Override
    public int getDualScreenTaskState() {
        return -1;
    }

    @Override
    public void setDualScreenEnabled(boolean enabled, boolean flag) {
    }

    @Override
    public void finishBoot() {
        mService.finishBoot();
    }

    // --- Refresh mode (section 3.2). ---

    @Override
    public void lockEpdMode(int type, boolean on) {
        mService.lockEpdMode(type, on);
    }

    @Override
    public void setEpdDisplayMode(int mode) {
        mService.setEpdDisplayMode(mode);
    }

    @Override
    public void updateEpdMode() {
        mService.updateEpdMode();
    }

    @Override
    public int getEpdMode() {
        return mService.getEpdMode();
    }

    @Override
    public void forceClear() {
        mService.forceClear();
    }

    @Override
    public void removeAppMode(String pkg) {
        mService.removeAppMode(pkg);
    }

    @Override
    public void addAppMode(String pkg, int mode) {
        mService.addAppMode(pkg, mode);
    }

    @Override
    @SuppressWarnings("rawtypes") // the AIDL declares a raw Map
    public Map getAllAppMode() {
        return mService.getAllAppMode();
    }

    // --- Foreground app (section 3.4). ---

    @Override
    public void begigResumeActivity(String packageName, String className) {
        mService.onResumeHint(packageName);
    }

    @Override
    public void notifyResumedPackage(String name) {
        mService.onResumeHint(name);
    }

    @Override
    public String getTopPackage() {
        return mService.getTopPackage();
    }

    // --- Display type (section 3.1). ---

    @Override
    public void setDisplayType(int type, boolean isAllowExitFromReadOnly) {
        mService.setDisplayType(type);
    }

    @Override
    public void setDisplayPowerMode(int displayId, boolean enabled) {
        // No-op as on stock (spec 02 open question 7).
    }

    @Override
    public void setDisplayEnabled(int id, boolean enabled) {
        mService.setDisplayEnabled(id, enabled);
    }

    @Override
    public boolean getDisplayEnabled(int id) {
        return mService.getDisplayEnabled(id);
    }

    @Override
    public int getDisplayType() {
        return mService.getDisplayType();
    }

    @Override
    public int getLastScreenOnType() {
        return mService.getLastScreenOnType();
    }

    @Override
    public boolean isExternalType() {
        int type = mService.getDisplayType();
        return type >= 0 && EpdSettings.isEinkType(type & ~1);
    }

    @Override
    public void requestGoToSleep() {
        final long ident = Binder.clearCallingIdentity();
        try {
            mService.requestGoToSleep();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // --- Screen and wake-up (sections 2.7, 3.1). ---

    @Override
    public void lockWakeupSource() {
        mService.setWakeupLocked(true);
    }

    @Override
    public void unLockWakeupSource() {
        mService.setWakeupLocked(false);
    }

    @Override
    public void setWakeupSourceType(int sourceType, int display, boolean goHomeEnabled) {
        mService.setWakeupSourceType(sourceType, display);
    }

    @Override
    public int getWakeupSourceType() {
        return mService.getWakeupSourceType();
    }

    @Override
    public void startWakingUp() {
        // The wake target is decided at the screen-on broadcast (section 3.1).
    }

    @Override
    public void onScreenChanged(boolean isScreenOn) {
        mService.onScreenChanged(isScreenOn);
    }

    // --- Settings-like calls (section 3.5). ---

    @Override
    public void setEinkThemeMode(int mode) {
        mService.setEinkThemeMode(mode);
    }

    @Override
    public void setEinkKeyguardMode(int mode) {
        mService.setEinkKeyguardMode(mode);
    }

    @Override
    public void setGestureUnlock(int enabled) {
        mService.setGestureUnlock(enabled);
    }

    @Override
    public void setExternalTpEnabled(boolean enabled) {
        mService.setExternalTpEnabled(enabled);
    }

    // --- Direction listener (section 3.6): kept, never called. ---

    @Override
    public void registerDirectionChangeListener(IDirectionDetect callback) {
        if (callback != null) mDirectionCallbacks.register(callback);
    }

    @Override
    public void unregisterDirectionChangeListener(IDirectionDetect callback) {
        if (callback != null) mDirectionCallbacks.unregister(callback);
    }

    // --- Tuning (section 3.3). ---

    @Override
    public void adjustEinkEffect(int type, int value) {
        mService.adjustEinkEffect(type, value);
    }

    @Override
    public int getEinkEffect(int type) {
        return mService.getEinkEffect(type);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        // "System properties changed" pokes arrive one-way (for example
        // "service call epd 1599295570", or tools that poke every service). The
        // native layer already handles them; a Java binder that does not claim
        // the code answers UNKNOWN_TRANSACTION, which IPCThreadState logs as
        // "oneway function results ... UNKNOWN_TRANSACTION". Claim it.
        if (code == IBinder.SYSPROPS_TRANSACTION) return true;
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mService.context(), TAG, pw)) return;
        final long ident = Binder.clearCallingIdentity();
        try {
            mService.dump(pw, args);
            pw.println("  direction callbacks: "
                    + mDirectionCallbacks.getRegisteredCallbackCount());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
