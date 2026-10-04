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

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Observes camera use for the automatic camera lock (spec 05 sections 4.1
 * and 6.3), replacing stock's battery-stats hook. A camera id counts as open
 * while it is unavailable; the opener's package comes from the hidden
 * {@code onCameraOpened} callback (permission
 * {@code CAMERA_OPEN_CLOSE_LISTENER}, which uid system passes). Registering
 * reports the cameras already open (after a restart of the service), without
 * their opener, which is then unknown ({@code ""}).
 *
 * <p>{@link #release()} (screen off) ignores the cameras open at that moment
 * until they are closed; a new open locks again. Handler thread only.
 */
final class CameraWatcher extends CameraManager.AvailabilityCallback {
    private static final String TAG = "A6lEpdCamera";

    private final Context mContext;
    private final Handler mHandler;
    /** Receives the open (not released) cameras: id to opener package, "" if unknown. */
    private final Consumer<Map<String, String>> mSink;

    private final Set<String> mUnavailable = new HashSet<>();
    private final Map<String, String> mOpeners = new HashMap<>();
    private final Set<String> mReleased = new HashSet<>();
    private boolean mRegistered;

    CameraWatcher(Context context, Handler handler, Consumer<Map<String, String>> sink) {
        mContext = context;
        mHandler = handler;
        mSink = sink;
    }

    void start() {
        if (mRegistered) return;
        try {
            mContext.getSystemService(CameraManager.class).registerAvailabilityCallback(this,
                    mHandler);
            mRegistered = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot watch the cameras; automatic camera switch is off", e);
        }
    }

    /** Screen off: the cameras open now no longer lock until closed and opened again. */
    void release() {
        mReleased.addAll(mUnavailable);
        report();
    }

    @Override
    public void onCameraUnavailable(String cameraId) {
        mUnavailable.add(cameraId);
        report();
    }

    @Override
    public void onCameraAvailable(String cameraId) {
        mUnavailable.remove(cameraId);
        mOpeners.remove(cameraId);
        mReleased.remove(cameraId);
        report();
    }

    @Override
    public void onCameraOpened(String cameraId, String packageId) {
        mUnavailable.add(cameraId);
        mOpeners.put(cameraId, packageId != null ? packageId : "");
        mReleased.remove(cameraId);
        report();
    }

    @Override
    public void onCameraClosed(String cameraId) {
        mOpeners.remove(cameraId);
        // Availability follows with onCameraAvailable.
        report();
    }

    private void report() {
        Map<String, String> open = new TreeMap<>();
        for (String id : mUnavailable) {
            if (mReleased.contains(id)) continue;
            String pkg = mOpeners.get(id);
            open.put(id, pkg != null ? pkg : "");
        }
        mSink.accept(open);
    }

    String dumpState() {
        Map<String, String> all = new TreeMap<>();
        for (String id : mUnavailable) {
            String pkg = mOpeners.get(id);
            all.put(id, (pkg == null || pkg.isEmpty() ? "?" : pkg)
                    + (mReleased.contains(id) ? " (released)" : ""));
        }
        return "watching=" + mRegistered + " open=" + all;
    }
}
