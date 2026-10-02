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

import android.os.SystemClock;
import android.util.Log;

/**
 * Step-by-step timing of a wake into the E-ink (spec 02 section 10 item 4b):
 * one log line per step, tag {@code A6lEpdWake}, with the milliseconds since
 * the screen-on broadcast was seen. A wake is tracked from {@link #start}
 * until {@link #WINDOW_MS} later or the next screen-off. Thread-safe.
 */
final class WakeTimer {
    static final String TAG = "A6lEpdWake";
    /** Covers the last delayed re-application (3000 ms) and the user activity. */
    private static final long WINDOW_MS = 6000;

    private long mStart = -1;
    private int mWakes;
    private String mLast = "none";

    /** Screen-on seen with an E-ink target. */
    synchronized void start(int targetType, boolean keyguard) {
        mStart = SystemClock.elapsedRealtime();
        mWakes++;
        log("screen-on seen (target type " + targetType + ", keyguard "
                + (keyguard ? "showing" : "not showing") + ")");
    }

    /** Screen-off or a wake to the LCD: stop tracking. */
    synchronized void stop() {
        mStart = -1;
    }

    /** Logs a step if a wake is being tracked. */
    synchronized void step(String what) {
        if (mStart < 0) return;
        if (SystemClock.elapsedRealtime() - mStart > WINDOW_MS) {
            mStart = -1;
            return;
        }
        log(what);
    }

    private void log(String what) {
        long ms = SystemClock.elapsedRealtime() - mStart;
        mLast = "+" + ms + " ms " + what;
        Log.i(TAG, mLast);
    }

    synchronized String dumpState() {
        return "wakes into the E-ink: " + mWakes + ", last step: " + mLast;
    }
}
