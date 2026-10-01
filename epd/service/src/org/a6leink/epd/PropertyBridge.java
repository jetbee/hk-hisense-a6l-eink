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
import android.os.SystemProperties;
import android.util.Log;

/**
 * Sysfs writes through init (spec 02 section 1.4). The service may not write
 * the nodes itself; it sets a property in its own prefix and the rc file
 * installed with the service ({@code epd/rootdir/etc/init.a6l.epd.rc}) writes
 * the node. Value properties carry the value; counter properties carry an
 * ever-increasing number so every request is a distinct change.
 *
 * Callers must not touch the {@code fb1} nodes before the E-ink is ready
 * (spec 02 section 1.2); this class does not check that itself.
 */
final class PropertyBridge {
    private static final String TAG = "A6lEpdBridge";
    private static final String PREFIX = "sys.a6l.epd.";

    private static final String[] TUNING = { "contrast", "black", "white" };

    // Seeded from the uptime so that values differ after a process restart.
    private long mCounter = SystemClock.elapsedRealtime();

    /** Rear (E-ink side) touch. */
    void setRearTouch(boolean on) {
        set("ext_touch", on ? "1" : "0");
    }

    /** Front (LCD side) touch. */
    void setFrontTouch(boolean on) {
        set("front_touch", on ? "1" : "0");
    }

    /** Writes 0 then 1 to fb1/epd_connect: re-attaches and unblanks the E-ink. */
    void reconnectEink() {
        set("reconnect", nextCounter());
    }

    /** Writes 0 to the LCD backlight. */
    void lcdBacklightOff() {
        set("lcd_off", nextCounter());
    }

    /** Writes the refresh mode to fb1/epd_display_mode. */
    void setRefreshMode(int mode) {
        set("mode", Integer.toString(mode));
    }

    /** Makes the HWC show the bitmap file. */
    void commitBitmap() {
        set("commit_bitmap", nextCounter());
    }

    /** Full ghost-clearing refresh. */
    void forceClear() {
        set("force_clear", nextCounter());
    }

    /** Tuning value; type is EpdManager.TYPE_EINK_CONTRAST/BLACK/WHITE (0/1/2). */
    void setTuning(int type, int value) {
        if (type < 0 || type >= TUNING.length) return;
        set(TUNING[type], Integer.toString(value));
    }

    private synchronized String nextCounter() {
        return Long.toString(++mCounter);
    }

    private static void set(String name, String value) {
        try {
            SystemProperties.set(PREFIX + name, value);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot set " + PREFIX + name + "=" + value, e);
        }
    }
}
