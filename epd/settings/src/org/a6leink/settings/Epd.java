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
import android.os.IBinder;
import android.os.ServiceManager;

import com.hmct.epd.EpdManager;
import com.hmct.epd.IEpdManager;

/** Access to the "epd" service and the values this app uses with it. */
final class Epd {
    /** Binder / system service name of the E-ink service (spec 01, spec 02 section 1.1). */
    static final String SERVICE_NAME = "epd";

    /**
     * Refresh mode stored for an app set to "Clear" (spec 04 section 6.1).
     *
     * <p>"Smooth" is stored as no entry at all (removeAppMode), so such an app
     * gets the service's default for the E-ink (8, the steady mode on the
     * stock HWC, spec 02 section 3.2). "Clear" is an explicit entry with this
     * value. Stock used 3 ({@code EPD_MODE_READING}), which is also what spec
     * 04 recommends, but what 3 does at {@code fb1/epd_display_mode} on 18.1
     * has never been measured (spec 04 open question 1, spec 02 open question
     * 1). MUST BE CONFIRMED ON THE DEVICE; change only this constant if
     * another value (1..7, the range addAppMode accepts) renders better.
     */
    static final int MODE_CLEAR = EpdManager.EPD_MODE_READING;

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
