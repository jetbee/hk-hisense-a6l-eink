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

    // Refresh modes (spec 01 section 2). Every other place uses these names;
    // no refresh-mode number is written anywhere else in the service.
    public static final int MODE_MIRROR = 0;
    /** Steady everyday mode with the stock HWC (spec 02 section 3.2). */
    public static final int MODE_DEFAULT_EINK = 8;
    /**
     * "Smooth" (spec 04 section 6.1, spec 05 section 6.4): an app without an
     * entry, i.e. the service default. Locks (camera, video) and the
     * slide-one-screen drag force this mode too.
     */
    public static final int MODE_SMOOTH = MODE_DEFAULT_EINK;
    /**
     * "Clear": the value stored for an app set to clear, and the mode of the
     * E-ink lock screen (spec 05 section 6.2 step 1). Stock used 3
     * ({@code EPD_MODE_READING}). The device test decides the value (spec 04
     * open question 1): 3 was confirmed to take effect on the panel
     * (2026-10-03, spec 05 section 8). If it is ever retuned, change only this
     * constant; the settings app and the Quick Settings tile read it from the
     * service.
     */
    public static final int MODE_CLEAR = 3;
    /** Lowest refresh mode accepted by addAppMode (0 = mirror makes no sense per app). */
    public static final int MODE_APP_MIN = 1;
    /**
     * Highest refresh mode accepted by setEpdDisplayMode and addAppMode: the
     * highest value written to the node on 18.1 (spec 02 section 3.2), so that
     * the default can also be stored explicitly (stock accepted 1..7).
     */
    public static final int MODE_MAX = MODE_DEFAULT_EINK;

    // Own per-app options and switches (spec 05 section 6.4), served by
    // EinkOptionsProvider to the settings app.
    /** Signature permission guarding the options provider. */
    public static final String PERMISSION_OPTIONS = "org.a6leink.epd.permission.EINK_OPTIONS";
    public static final String OPTIONS_AUTHORITY = "org.a6leink.epd.options";
    /** Option: smooth while the front app uses a camera. Default on. */
    public static final String OPTION_AUTO_CAMERA = "auto_camera";
    /** Option: smooth while the front app plays video-like audio. Default on. */
    public static final String OPTION_AUTO_VIDEO = "auto_video";

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
    /**
     * Spec 02 section 9.2: 1 = while the E-ink is in use and the screen is on, the
     * power button switches back to the LCD and the E-ink key sleeps. Default 0.
     */
    public static final String SWAP_KEYS_ON_EINK = "a6l_epd_swap_keys_on_eink";
    /**
     * Own addition: E-ink key while the E-ink is in use (no swap). 0 = back to the
     * LCD (default, spec 02 section 9.1), 1 = sleep (stock).
     */
    public static final String EINK_KEY_ACTION = "a6l_epd_eink_key_action";
    /**
     * Own addition: wake target of wake-ups that name no display (not the power
     * button, which names the LCD, nor the E-ink key, which names the E-ink).
     * 0 = last used screen (default), 1 = LCD.
     */
    public static final String DEFAULT_WAKE = "a6l_epd_default_wake";
    /**
     * Spec 02 section 10 item 4a: refresh mode while the keyguard is showing on
     * the E-ink. 0 = the default mode (default; faster), 1 = the clear mode.
     */
    public static final String KEYGUARD_CLEAR = "a6l_epd_keyguard_clear";
    public static final String ACCELEROMETER_ROTATION_RESTORE = "accelerometer_rotation_restore";
    /** Settings.Secure key restored for upgrades from older builds. */
    public static final String WAKE_GESTURE_ENABLED = "wake_gesture_enabled";

    public static final int CLOCK_STYLE_ANALOG = 0;
    public static final int CLOCK_STYLE_DIGITAL = 1;

    // Sleep screen stage 2 (spec 03 section 5), Settings.System, current user.
    /** 0 = widgets then notifications (default), 1 = notifications then widgets. */
    public static final String SLEEP_SECTIONS_ORDER = "a6l_epd_sleep_sections_order";
    /** JSON array of the chosen widgets with their options, in display order. */
    public static final String SLEEP_WIDGETS = "a6l_epd_sleep_widgets";
    /** 1 = show notifications (default), 0 = none. */
    public static final String SLEEP_NOTIFICATIONS = "a6l_epd_sleep_notifications";
    /** -1 = as many as fit (default), else 0..6 (0 hides the section). */
    public static final String SLEEP_NOTIFICATIONS_MAX = "a6l_epd_sleep_notifications_max";

    public static final int ORDER_WIDGETS_FIRST = 0;
    public static final int ORDER_NOTIFICATIONS_FIRST = 1;
    public static final int NOTIFICATIONS_MAX_FIT = -1;
    public static final int NOTIFICATIONS_MAX_LIMIT = 6;
    public static final int MAX_SLEEP_WIDGETS = 4;
    /** Host id of the service's AppWidgetHost (host ids are per package). */
    public static final int WIDGET_HOST_ID = 0x0a6e;

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
    /** Second stock home app (spec 02 section 7.5); exempt from the camera lock. */
    public static final String VISION_HOME = "com.hmct.vision";

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
