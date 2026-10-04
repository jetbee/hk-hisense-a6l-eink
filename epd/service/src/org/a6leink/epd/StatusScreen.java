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

import android.app.AlarmManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * page of the phone's content. While sleeping, stage 2 (spec 03) adds widgets
 * and notifications below the header. All methods run on the service's
 * handler thread; widgets are rendered on the main thread and handed back.
 */
final class StatusScreen {
    private static final String TAG = "A6lEpdStatus";

    static final int STATE_NONE = 0;
    static final int STATE_SLEEP = StatusRenderer.SCREEN_SLEEP;
    static final int STATE_LCD = StatusRenderer.SCREEN_LCD;

    private static final long DRAW_DELAY_MS = 500;
    private static final long WAKE_LOCK_MS = 1500;
    /** Spec 03 section 4: notification redraws at most once per 10 s. */
    private static final long NOTIFICATION_INTERVAL_MS = 10_000;
    /** Draw without widgets if the main thread has not answered by then. */
    private static final long WIDGET_TIMEOUT_MS = SleepWidgetHost.COLLECTION_WAIT_MS + 1000;

    private final Object mDrawToken = new Object();
    private final Context mContext;
    private final Handler mHandler;
    private final PropertyBridge mBridge;
    private final StatusRenderer mRenderer;
    private final AlarmManager mAlarmManager;
    private final PowerManager.WakeLock mWakeLock;
    private final AlarmManager.OnAlarmListener mMinuteListener = this::onMinuteAlarm;
    private final AlarmManager.OnAlarmListener mNotificationListener = this::onNotificationAlarm;
    private final SleepSections mSections;
    private final SleepWidgetHost mWidgets;

    private int mState = STATE_NONE;
    private long mLcdSince;
    private boolean mAlarmSet;
    private boolean mNotificationAlarmSet;
    private boolean mDrawPending;
    /** Bumped by every draw and state change; a widget result of an older draw is dropped. */
    private int mGeneration;
    private long mLastDrawElapsed;
    private String mNotificationSignature = "";
    private boolean mForced;
    private int mDrawCount;
    private int mNotificationDraws;
    private long mLastDraw;
    private String mLastError;
    private boolean mWriteErrorLogged;

    StatusScreen(Context context, Handler handler, PropertyBridge bridge,
            SleepWidgetHost widgets) {
        mContext = context;
        mHandler = handler;
        mBridge = bridge;
        mWidgets = widgets;
        mRenderer = new StatusRenderer(context);
        mSections = new SleepSections(context);
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
        mDrawPending = true;
        mHandler.postAtTime(this::draw, mDrawToken,
                SystemClock.uptimeMillis() + DRAW_DELAY_MS);
    }

    private void cancel() {
        mHandler.removeCallbacksAndMessages(mDrawToken);
        mDrawPending = false;
        mGeneration++;
        if (mAlarmSet) {
            mAlarmManager.cancel(mMinuteListener);
            mAlarmSet = false;
        }
        if (mNotificationAlarmSet) {
            mAlarmManager.cancel(mNotificationListener);
            mNotificationAlarmSet = false;
        }
    }

    private void onMinuteAlarm() {
        mAlarmSet = false;
        if (mState == STATE_SLEEP) draw();
    }

    /**
     * A notification was posted, updated or removed (spec 03 section 4). While
     * sleeping, redraws when the shown list changed, at most once per 10 s;
     * changes within that window are coalesced into one redraw at its end.
     */
    void onNotificationsChanged() {
        if (mState != STATE_SLEEP || mDrawPending || mNotificationAlarmSet) return;
        if (mSections.notificationSignature().equals(mNotificationSignature)) return;
        long due = mLastDrawElapsed + NOTIFICATION_INTERVAL_MS;
        if (SystemClock.elapsedRealtime() >= due) {
            mNotificationDraws++;
            draw();
        } else {
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, due,
                    "A6lEpd:notifications", mNotificationListener, mHandler);
            mNotificationAlarmSet = true;
        }
    }

    private void onNotificationAlarm() {
        mNotificationAlarmSet = false;
        if (mState != STATE_SLEEP || mDrawPending) return;
        if (mSections.notificationSignature().equals(mNotificationSignature)) return;
        mNotificationDraws++;
        draw();
    }

    private void draw() {
        final int state = mState;
        mDrawPending = false;
        if (state == STATE_NONE) return;
        // A newer draw supersedes one still waiting for its widgets.
        mHandler.removeCallbacksAndMessages(mDrawToken);
        final int generation = ++mGeneration;
        mWakeLock.acquire(WAKE_LOCK_MS);
        mLastDrawElapsed = SystemClock.elapsedRealtime();

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
        if (clock) scheduleNextMinute(now);

        final SleepSections.Content content = state == STATE_SLEEP ? mSections.prepare(now) : null;
        if (content != null) mNotificationSignature = content.signature;
        final SleepWidgetHost.Request[] requests = content != null && mWidgets != null
                ? SleepSections.requests(content) : new SleepWidgetHost.Request[0];
        if (requests.length == 0) {
            if (content != null) mSections.setRendered(content, null);
            finish(state, now, clock, style, content, false);
            return;
        }

        // Widgets: rendered on the main thread, with the bounded wait for collections.
        mWakeLock.acquire(WAKE_LOCK_MS + SleepWidgetHost.COLLECTION_WAIT_MS);
        final boolean[] done = new boolean[1];
        mHandler.postAtTime(() -> {
            if (done[0]) return;
            done[0] = true;
            Log.w(TAG, "Widgets not rendered in time, drawing without them");
            mSections.setRendered(content, null);
            finish(state, now, clock, style, content, true);
        }, mDrawToken, SystemClock.uptimeMillis() + WIDGET_TIMEOUT_MS);
        mWidgets.render(requests, content.configuredIds, bitmaps -> mHandler.post(() -> {
            if (done[0] || generation != mGeneration || mState != state) {
                recycle(bitmaps);
                return;
            }
            done[0] = true;
            mHandler.removeCallbacksAndMessages(mDrawToken);
            mSections.setRendered(content, bitmaps);
            finish(state, now, clock, style, content, true);
            recycle(bitmaps);
        }));
    }

    /** Draws, writes and commits. {@code afterWait}: the draw waited for widgets. */
    private void finish(int state, long now, boolean clock, int style,
            SleepSections.Content content, boolean afterWait) {
        try {
            Bitmap bmp = mRenderer.render(state, now, mLcdSince, clock, style);
            if (content != null) {
                try {
                    mSections.draw(new Canvas(bmp), content);
                } catch (RuntimeException e) {
                    // The header is drawn whatever happens below it (spec 03 section 6).
                    Log.e(TAG, "Cannot draw the widget and notification sections", e);
                }
            }
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
            // Logged once per boot (spec 03 section 6); dumpsys shows the last error.
            if (!mWriteErrorLogged) {
                mWriteErrorLogged = true;
                Log.e(TAG, "Cannot write " + EpdSettings.BITMAP_PATH + ", not committing", e);
            }
        }
        // After a wait for widgets, cover the HWC reading the file after the commit again.
        if (afterWait) mWakeLock.acquire(WAKE_LOCK_MS);
    }

    private static void recycle(Bitmap[] bitmaps) {
        if (bitmaps == null) return;
        for (Bitmap b : bitmaps) if (b != null) b.recycle();
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
        pw.println("  sleep screen: notification redraws: " + mNotificationDraws
                + ", notification redraw pending: " + mNotificationAlarmSet
                + ", notifications counted: "
                + (mNotificationSignature.isEmpty() ? 0
                        : mNotificationSignature.split("\n").length)
                + ", listener connected: " + (SleepNotificationListener.connected() != null));
        if (mSections.lastNotificationError() != null) {
            pw.println("  sleep screen notifications last error: "
                    + mSections.lastNotificationError());
        }
        if (mWidgets != null) mWidgets.dump(pw);
        if (mLastError != null) pw.println("  status screen last error: " + mLastError);
    }
}
