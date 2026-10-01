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

import android.app.AlarmManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;

/**
 * The E-ink status screen (spec 02 section 6): a picture painted by the
 * service while the E-ink is not in use, so the panel never keeps the last
 * page of the phone's content. All methods run on the service's handler
 * thread.
 */
final class StatusScreen {
    private static final String TAG = "A6lEpdStatus";

    static final int STATE_NONE = 0;
    static final int STATE_SLEEP = StatusRenderer.SCREEN_SLEEP;
    static final int STATE_LCD = StatusRenderer.SCREEN_LCD;

    private static final long DRAW_DELAY_MS = 500;
    private static final long WAKE_LOCK_MS = 1500;

    private final Object mDrawToken = new Object();
    private final Context mContext;
    private final Handler mHandler;
    private final PropertyBridge mBridge;
    private final StatusRenderer mRenderer;
    private final AlarmManager mAlarmManager;
    private final PowerManager.WakeLock mWakeLock;
    private final AlarmManager.OnAlarmListener mMinuteListener = this::onMinuteAlarm;

    private int mState = STATE_NONE;
    private long mLcdSince;
    private boolean mAlarmSet;
    private boolean mForced;
    private int mDrawCount;
    private long mLastDraw;
    private String mLastError;

    StatusScreen(Context context, Handler handler, PropertyBridge bridge) {
        mContext = context;
        mHandler = handler;
        mBridge = bridge;
        mRenderer = new StatusRenderer(context);
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "A6lEpd:status");
        mWakeLock.setReferenceCounted(false);
    }

    static int stateForType(int type) {
        if (type == EpdSettings.TYPE_OFF) return STATE_SLEEP;
        if (type == EpdSettings.TYPE_LCD || type == 1) return STATE_LCD;
        return STATE_NONE;
    }

    /** Follows the display type (section 6.1). Only call once the E-ink is ready. */
    void onDisplayType(int type) {
        mForced = false;
        enter(stateForType(type));
    }

    /** Test hook for {@code dumpsys epd status lcd|sleep|stop}. */
    void force(int state) {
        mForced = true;
        enter(state);
    }

    private void enter(int state) {
        if (state == mState) return; // re-entering keeps the shown screen and its time
        cancel();
        mState = state;
        if (state == STATE_NONE) return;
        if (state == STATE_LCD) mLcdSince = System.currentTimeMillis();
        // Keep the CPU up across the wait too (a screen-off would let it suspend).
        mWakeLock.acquire(DRAW_DELAY_MS + WAKE_LOCK_MS);
        mHandler.postAtTime(this::draw, mDrawToken,
                SystemClock.uptimeMillis() + DRAW_DELAY_MS);
    }

    private void cancel() {
        mHandler.removeCallbacksAndMessages(mDrawToken);
        if (mAlarmSet) {
            mAlarmManager.cancel(mMinuteListener);
            mAlarmSet = false;
        }
    }

    private void onMinuteAlarm() {
        mAlarmSet = false;
        if (mState == STATE_SLEEP) draw();
    }

    private void draw() {
        final int state = mState;
        if (state == STATE_NONE) return;
        mWakeLock.acquire(WAKE_LOCK_MS);

        if (state == STATE_LCD) {
            // The HWC shows a committed bitmap only while the E-ink is blanked.
            blankEink();
        }

        final boolean clock = state == STATE_SLEEP && Settings.System.getIntForUser(
                mContext.getContentResolver(), EpdSettings.SLEEP_CLOCK, 1,
                UserHandle.USER_CURRENT) != 0;
        final int style = Settings.System.getIntForUser(mContext.getContentResolver(),
                EpdSettings.SLEEP_CLOCK_STYLE, EpdSettings.CLOCK_STYLE_ANALOG,
                UserHandle.USER_CURRENT);
        final long now = System.currentTimeMillis();

        try {
            Bitmap bmp = mRenderer.render(state, now, mLcdSince, clock, style);
            byte[] raw = StatusRenderer.toRgb565(bmp);
            bmp.recycle();
            try (FileOutputStream out = new FileOutputStream(EpdSettings.BITMAP_PATH, false)) {
                out.write(raw);
            }
            mBridge.commitBitmap();
            mDrawCount++;
            mLastDraw = now;
            mLastError = null;
        } catch (IOException | RuntimeException e) {
            mLastError = e.toString();
            Log.e(TAG, "Cannot write " + EpdSettings.BITMAP_PATH + ", not committing", e);
        }

        if (clock) scheduleNextMinute(now);
    }

    private void scheduleNextMinute(long now) {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(now);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        next.add(Calendar.MINUTE, 1);
        mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), "A6lEpd:clock",
                mMinuteListener, mHandler);
        mAlarmSet = true;
    }

    /** Powers display 1 off through SurfaceFlinger, which makes the HWC blank the E-ink. */
    private static void blankEink() {
        try {
            IBinder internal = SurfaceControl.getInternalDisplayToken();
            for (long id : SurfaceControl.getPhysicalDisplayIds()) {
                IBinder token = SurfaceControl.getPhysicalDisplayToken(id);
                if (token != null && !token.equals(internal)) {
                    SurfaceControl.setDisplayPowerMode(token, SurfaceControl.POWER_MODE_OFF);
                    return;
                }
            }
            Log.w(TAG, "No external display found to blank");
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot blank the E-ink", e);
        }
    }

    void dump(PrintWriter pw) {
        String name = mState == STATE_SLEEP ? "sleeping" : mState == STATE_LCD ? "lcd" : "none";
        pw.println("  status screen: " + name + (mForced ? " (forced)" : "")
                + ", minute alarm: " + mAlarmSet + ", draws: " + mDrawCount
                + ", last draw: " + mLastDraw
                + (mState == STATE_LCD ? ", lcd since: " + mLcdSince : ""));
        if (mLastError != null) pw.println("  status screen last error: " + mLastError);
    }
}
