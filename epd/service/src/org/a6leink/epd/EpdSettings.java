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

/**
 * Names of settings, properties, broadcasts and nodes used by the service
 * (spec 02, sections 2.6, 4.2, 4.3 and 7). Shared with the key handler, which
 * runs inside system_server, so this class must not reference resources.
 */
public final class EpdSettings {
    private EpdSettings() {}

    /** Binder name of the service. */
    public static final String SERVICE_NAME = "epd";

    // Display types (spec 01 section 2).
    public static final int TYPE_OFF = -1;
    public static final int TYPE_LCD = 0;
    public static final int TYPE_MIRROR = 2;
    public static final int TYPE_EINK = 4;

    // Refresh modes (spec 01 section 2).
    public static final int MODE_MIRROR = 0;
    public static final int MODE_FAST = 6;
    /** Steady everyday mode with the stock HWC (spec 02 section 3.2). */
    public static final int MODE_DEFAULT_EINK = 8;

    // Publishing (compatibility facts).
    public static final String PROP_DISPLAY_TYPE = "sys.sysctl.display_type";
    public static final String SETTING_DISPLAY_TYPE = "current_display_type";
    public static final String ACTION_DISPLAY_CHANGED = "com.hmct.action.displaychanged";
    public static final String EXTRA_DISPLAY_TYPE = "display_type";
    public static final String ACTION_EINK_KEY_PRESSED = "com.hmct.intent.EINK_KEY_PRESSED";

    // Settings.System keys.
    public static final String FP_UNLOCK_SCREEN = "fp_unlock_screen";
    public static final String SAVED_BRIGHTNESS_MODE = "a6l_epd_saved_brightness_mode";
    public static final String SAVED_WAKE_GESTURE = "a6l_epd_saved_wake_gesture";
    public static final String POWER_KEY_WAKE = "a6l_epd_power_key_wake";
    public static final String SLEEP_CLOCK = "a6l_epd_sleep_clock";
    public static final String SLEEP_CLOCK_STYLE = "a6l_epd_sleep_clock_style";
    /** Own addition: E-ink key while the E-ink is in use. 0 = back to the LCD, 1 = sleep. */
    public static final String EINK_KEY_ACTION = "a6l_epd_eink_key_action";
    /** Own addition: wake target without a named display. 0 = last used screen, 1 = LCD. */
    public static final String DEFAULT_WAKE = "a6l_epd_default_wake";
    public static final String ACCELEROMETER_ROTATION_RESTORE = "accelerometer_rotation_restore";
    /** Settings.Secure key restored for upgrades from older builds. */
    public static final String WAKE_GESTURE_ENABLED = "wake_gesture_enabled";

    public static final int CLOCK_STYLE_ANALOG = 0;
    public static final int CLOCK_STYLE_DIGITAL = 1;

    // Properties.
    public static final String PROP_CONTRAST = "persist.sys.epd.contrast";
    public static final String PROP_BLACK = "persist.sys.epd.black";
    public static final String PROP_WHITE = "persist.sys.epd.white";
    public static final int DEFAULT_CONTRAST = 0;
    public static final int DEFAULT_BLACK = 30;
    public static final int DEFAULT_WHITE = 20;
    public static final String PROP_LAUNCHER_READY = "sys.einklauncher.ready";
    public static final String PROP_BOOT_COMPLETED = "sys.boot_completed";

    // Packages.
    public static final String EINK_LAUNCHER = "com.hmct.einklauncher";

    // Status screen bitmap (compatibility fact with the stock HWC).
    public static final String BITMAP_PATH = "/kdebuginfo/edpd/bitmap.raw";
    public static final int EINK_WIDTH = 720;
    public static final int EINK_HEIGHT = 1440;

    /** Size the LCD is forced to while the E-ink is used alone (2:1 like the E-ink). */
    public static final int FORCED_WIDTH = 1080;
    public static final int FORCED_HEIGHT = 2160;

    public static boolean isEinkType(int type) {
        return type == TYPE_MIRROR || type == TYPE_EINK;
    }
}
