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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The service's own per-app options and switches for the settings app
 * (spec 05 section 6.4: not encoded into the {@code IEpdManager} mode map).
 * Only {@link #call} is used; it requires the signature permission
 * {@link EpdSettings#PERMISSION_OPTIONS}. The provider is a single-user
 * singleton in the service's persistent process, so every user reaches the
 * one device-wide state.
 *
 * <p>Methods (all return null if the service is not up yet):
 * <ul>
 * <li>{@code get_config}: ints {@code clear_mode}, {@code smooth_mode},
 *     {@code min_mode}, {@code max_mode}; booleans {@code auto_camera},
 *     {@code auto_video}; String array {@code slide} (packages with slide one
 *     screen on).</li>
 * <li>{@code set_slide}: arg = package, boolean extra {@code on}.</li>
 * <li>{@code set_option}: arg = {@code auto_camera} or {@code auto_video},
 *     boolean extra {@code on}.</li>
 * </ul>
 */
public class EinkOptionsProvider extends ContentProvider {
    static final String METHOD_GET_CONFIG = "get_config";
    static final String METHOD_SET_SLIDE = "set_slide";
    static final String METHOD_SET_OPTION = "set_option";
    static final String KEY_CLEAR_MODE = "clear_mode";
    static final String KEY_SMOOTH_MODE = "smooth_mode";
    static final String KEY_MIN_MODE = "min_mode";
    static final String KEY_MAX_MODE = "max_mode";
    static final String KEY_SLIDE = "slide";
    static final String KEY_ON = "on";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(EpdSettings.PERMISSION_OPTIONS,
                "E-ink options");
        EpdService service = EpdApplication.service();
        if (service == null || method == null) return null;
        switch (method) {
            case METHOD_GET_CONFIG: {
                Bundle out = new Bundle();
                out.putInt(KEY_CLEAR_MODE, EpdSettings.MODE_CLEAR);
                out.putInt(KEY_SMOOTH_MODE, EpdSettings.MODE_SMOOTH);
                out.putInt(KEY_MIN_MODE, EpdSettings.MODE_APP_MIN);
                out.putInt(KEY_MAX_MODE, EpdSettings.MODE_MAX);
                out.putBoolean(EpdSettings.OPTION_AUTO_CAMERA,
                        service.getOption(EpdSettings.OPTION_AUTO_CAMERA));
                out.putBoolean(EpdSettings.OPTION_AUTO_VIDEO,
                        service.getOption(EpdSettings.OPTION_AUTO_VIDEO));
                out.putStringArray(KEY_SLIDE, service.getSlidePackages());
                return out;
            }
            case METHOD_SET_SLIDE:
                if (arg == null || extras == null) return null;
                service.setSlide(arg, extras.getBoolean(KEY_ON));
                return Bundle.EMPTY;
            case METHOD_SET_OPTION:
                if (arg == null || extras == null) return null;
                if (!service.setOption(arg, extras.getBoolean(KEY_ON))) return null;
                return Bundle.EMPTY;
            default:
                return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
