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

package org.a6leink.settings;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import com.hmct.epd.EpdManager;
import com.hmct.epd.IEpdManager;

/** Access to the "epd" service and the values this app uses with it. */
final class Epd {
    /** Binder / system service name of the E-ink service (spec 01, spec 02 section 1.1). */
    static final String SERVICE_NAME = "epd";
    private static final String TAG = "A6lEinkSettings";

    // The service's own per-app options (spec 05 section 6.4), reached through
    // its provider; these names are that interface (see EinkOptionsProvider in
    // the service app).
    private static final Uri OPTIONS_URI = Uri.parse("content://org.a6leink.epd.options");
    private static final String METHOD_GET_CONFIG = "get_config";
    private static final String METHOD_SET_SLIDE = "set_slide";
    private static final String METHOD_SET_OPTION = "set_option";
    static final String KEY_CLEAR_MODE = "clear_mode";
    static final String KEY_SMOOTH_MODE = "smooth_mode";
    static final String KEY_SLIDE = "slide";
    private static final String KEY_ON = "on";
    static final String OPTION_AUTO_CAMERA = "auto_camera";
    static final String OPTION_AUTO_VIDEO = "auto_video";

    /**
     * The service's configuration: the refresh mode stored for "Clear" (the
     * service owns that value, which a device test decides; spec 04 open
     * question 1), the "Smooth" value (the service default, stored as no
     * entry), the automatic switches and the slide-one-screen packages. Null
     * if the service is not reachable. "Smooth" is stored as no entry
     * (removeAppMode); "Clear" as an entry with the clear mode (addAppMode).
     */
    static Bundle getConfig(Context context) {
        Bundle config = call(context, METHOD_GET_CONFIG, null, null);
        return config != null && config.containsKey(KEY_CLEAR_MODE) ? config : null;
    }

    /** Slide one screen (experimental) on or off for a package. */
    static boolean setSlide(Context context, String pkg, boolean on) {
        Bundle extras = new Bundle();
        extras.putBoolean(KEY_ON, on);
        return call(context, METHOD_SET_SLIDE, pkg, extras) != null;
    }

    /** One of the automatic switches ({@link #OPTION_AUTO_CAMERA}, {@link #OPTION_AUTO_VIDEO}). */
    static boolean setOption(Context context, String name, boolean on) {
        Bundle extras = new Bundle();
        extras.putBoolean(KEY_ON, on);
        return call(context, METHOD_SET_OPTION, name, extras) != null;
    }

    private static Bundle call(Context context, String method, String arg, Bundle extras) {
        try {
            return context.getContentResolver().call(OPTIONS_URI, method, arg, extras);
        } catch (RuntimeException e) {
            // Provider missing (service app not installed) or permission denied.
            Log.w(TAG, "E-ink options call " + method + " failed", e);
            return null;
        }
    }

    /** Tuning types of getEinkEffect / adjustEinkEffect (spec 01 section 2). */
    static final int[] EFFECT_TYPES = {
        EpdManager.TYPE_EINK_CONTRAST, EpdManager.TYPE_EINK_BLACK, EpdManager.TYPE_EINK_WHITE,
    };
    /** Defaults of contrast, black and white thresholds (spec 02 section 3.3). */
    static final int[] EFFECT_DEFAULTS = { 0, 30, 20 };
    static final int EFFECT_MIN = 0;
    static final int EFFECT_MAX = 100;

    private Epd() {
    }

    /**
     * The client for the "epd" service, or null if the service is not
     * running. Uses getSystemService first (frameworks/base patch), then the
     * binder directly (the app is platform-signed).
     */
    static EpdManager get(Context context) {
        Object manager = context.getSystemService(SERVICE_NAME);
        if (manager instanceof EpdManager) return (EpdManager) manager;
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) return null;
        return new EpdManager(IEpdManager.Stub.asInterface(binder), context);
    }
}
