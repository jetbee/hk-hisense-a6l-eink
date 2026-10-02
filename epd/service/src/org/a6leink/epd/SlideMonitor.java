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
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * EXPERIMENTAL "slide one screen" approximation (spec 05 sections 4.4 and
 * 6.3, without framework changes). Stock suppressed the app's drawing during
 * a drag; 18.1 cannot. Instead, while enabled (the front app has the flag and
 * the E-ink is in use), a gesture monitor on display 0 watches touches
 * without taking them from the app: once a finger has moved more than the
 * touch slop, the listener is told a drag started (the service switches to
 * the smooth mode); after the last finger is lifted and {@link #SETTLE_MS}
 * passed without a new touch, it is told the drag ended (the service restores
 * the app's mode). Spec 05 section 8 decision c: a fast mode only while the
 * finger drags. Handler thread only.
 */
final class SlideMonitor {
    private static final String TAG = "A6lEpdSlide";
    private static final String MONITOR_NAME = "a6l-epd-slide";
    /** Time after finger-up for a fling to slow down before the final update. */
    static final long SETTLE_MS = 600;

    interface Listener {
        void onDragStarted();

        void onDragEnded();
    }

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final int mSlop;
    private final Runnable mSettle = this::settle;

    private InputMonitor mMonitor;
    private Receiver mReceiver;
    private boolean mTracking;
    private boolean mDragging;
    private float mDownX;
    private float mDownY;
    private int mDrags;
    private long mLastDragUptime;

    SlideMonitor(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    boolean isEnabled() {
        return mMonitor != null;
    }

    void setEnabled(boolean on) {
        if (on == isEnabled()) return;
        if (on) {
            try {
                mMonitor = InputManager.getInstance().monitorGestureInput(MONITOR_NAME,
                        Display.DEFAULT_DISPLAY);
                mReceiver = new Receiver(mMonitor);
                Log.i(TAG, "Slide monitor on");
            } catch (RuntimeException e) {
                Log.e(TAG, "Cannot monitor touches; slide one screen is off", e);
                dispose();
            }
        } else {
            dispose();
            Log.i(TAG, "Slide monitor off");
            mHandler.removeCallbacks(mSettle);
            mTracking = false;
            if (mDragging) {
                mDragging = false;
                mListener.onDragEnded();
            }
        }
    }

    private void dispose() {
        if (mReceiver != null) {
            mReceiver.dispose();
            mReceiver = null;
        }
        if (mMonitor != null) {
            mMonitor.dispose();
            mMonitor = null;
        }
    }

    private void onMotion(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracking = true;
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                // A new touch during the settle time continues the same drag.
                mHandler.removeCallbacks(mSettle);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mTracking || mDragging) break;
                float dx = ev.getRawX() - mDownX;
                float dy = ev.getRawY() - mDownY;
                if (dx * dx + dy * dy > (float) mSlop * mSlop) {
                    mDragging = true;
                    mDrags++;
                    mLastDragUptime = SystemClock.uptimeMillis();
                    mListener.onDragStarted();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                if (mDragging) {
                    mHandler.removeCallbacks(mSettle);
                    mHandler.postDelayed(mSettle, SETTLE_MS);
                }
                break;
            default:
                break;
        }
    }

    private void settle() {
        if (!mDragging || mTracking) return;
        mDragging = false;
        mListener.onDragEnded();
    }

    String dumpState() {
        return "enabled=" + isEnabled() + " dragging=" + mDragging + " drags=" + mDrags
                + (mDrags > 0 ? " last=" + (SystemClock.uptimeMillis() - mLastDragUptime)
                        + " ms ago" : "") + " slop=" + mSlop + " px";
    }

    private final class Receiver extends InputEventReceiver {
        Receiver(InputMonitor monitor) {
            super(monitor.getInputChannel(), mHandler.getLooper());
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) onMotion((MotionEvent) event);
            } finally {
                finishInputEvent(event, false);
            }
        }
    }
}
