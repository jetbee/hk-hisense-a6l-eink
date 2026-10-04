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

import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Full repaint requests to SurfaceFlinger after a reconnect of the E-ink
 * (spec 02 section 10 item 5). The first panel update after a reconnect is
 * blank and the content only comes with SurfaceFlinger's next frame, which on
 * a still screen can take 10 s or more; a forced repaint brings it at once.
 * The request is the binder transaction 1004 with the interface token
 * {@code android.ui.ISurfaceComposer} on the service {@code SurfaceFlinger}
 * (allowed for uid system), sent at {@link #DELAYS_MS} after the reconnect.
 * Handler thread only.
 */
final class Repainter {
    private static final String TAG = "A6lEpdRepaint";
    private static final String SERVICE = "SurfaceFlinger";
    private static final String DESCRIPTOR = "android.ui.ISurfaceComposer";
    /** SurfaceFlinger debug transaction "repaint everything". */
    private static final int CODE_REPAINT = 1004;
    static final long[] DELAYS_MS = { 1200, 2500, 4000 };

    private final Handler mHandler;
    private final WakeTimer mWake;
    private final Object mToken = new Object();
    private boolean mFailureLogged;
    private int mRequests;
    private int mFailures;

    Repainter(Handler handler, WakeTimer wake) {
        mHandler = handler;
        mWake = wake;
    }

    /** After a reconnect for a wake or a switch into the E-ink. */
    void schedule(String why) {
        cancel();
        final long now = SystemClock.uptimeMillis();
        for (int i = 0; i < DELAYS_MS.length; i++) {
            final String step = "repaint " + (i + 1) + "/" + DELAYS_MS.length + " requested ("
                    + why + ", +" + DELAYS_MS[i] + " ms after the reconnect)";
            mHandler.postAtTime(() -> request(step), mToken, now + DELAYS_MS[i]);
        }
    }

    /** Screen-off or leaving the E-ink. */
    void cancel() {
        mHandler.removeCallbacksAndMessages(mToken);
    }

    private void request(String step) {
        mRequests++;
        IBinder sf = ServiceManager.getService(SERVICE);
        if (sf == null) {
            fail("service " + SERVICE + " not found", null);
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            sf.transact(CODE_REPAINT, data, null, 0);
            mWake.stepOrLog(step);
        } catch (RemoteException | RuntimeException e) {
            fail("transaction " + CODE_REPAINT + " failed", e);
        } finally {
            data.recycle();
        }
    }

    private void fail(String what, Exception e) {
        mFailures++;
        if (mFailureLogged) return;
        mFailureLogged = true;
        Log.w(TAG, "SurfaceFlinger repaint request: " + what + " (logged once)", e);
    }

    String dumpState() {
        return "repaint requests: " + mRequests + ", failures: " + mFailures;
    }
}
