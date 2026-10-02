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

import android.content.ContentResolver;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One widget of the sleep screen and its display options (spec 03 sections 3
 * and 5). The list is stored, in display order, as a JSON array in
 * Settings.System {@code a6l_epd_sleep_widgets}, e.g.
 * {@code [{"id":12,"size":1,"invert":false,"contrast":0}]}.
 */
final class SleepWidgetConfig {
    private static final String TAG = "A6lEpdWidgets";

    /** Height from the provider's minimum height. */
    static final int SIZE_DEFAULT = 0;
    static final int SIZE_SMALL = 1;
    static final int SIZE_MEDIUM = 2;
    static final int SIZE_LARGE = 3;
    /** Panel pixels of the small, medium and large sizes. */
    static final int[] SIZE_HEIGHTS = { 0, 160, 280, 400 };

    static final int CONTRAST_NORMAL = 0;
    static final int CONTRAST_HIGH = 1;

    final int id;
    int size;
    boolean invert;
    int contrast;

    SleepWidgetConfig(int id) {
        this.id = id;
    }

    /** Parses the setting; malformed entries, duplicates and entries past the 4th are dropped. */
    static List<SleepWidgetConfig> parse(String json) {
        List<SleepWidgetConfig> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length()
                    && list.size() < EpdSettings.MAX_SLEEP_WIDGETS; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null || !o.has("id")) continue;
                int id = o.optInt("id", -1);
                if (id <= 0 || indexOf(list, id) >= 0) continue;
                SleepWidgetConfig c = new SleepWidgetConfig(id);
                c.size = clamp(o.optInt("size", SIZE_DEFAULT), SIZE_DEFAULT, SIZE_LARGE);
                c.invert = o.optBoolean("invert", false);
                c.contrast = clamp(o.optInt("contrast", CONTRAST_NORMAL), CONTRAST_NORMAL,
                        CONTRAST_HIGH);
                list.add(c);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring malformed " + EpdSettings.SLEEP_WIDGETS + ": " + e.getMessage());
        }
        return list;
    }

    static String serialize(List<SleepWidgetConfig> list) {
        JSONArray array = new JSONArray();
        try {
            for (SleepWidgetConfig c : list) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("size", c.size);
                o.put("invert", c.invert);
                o.put("contrast", c.contrast);
                array.put(o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e); // only thrown for non-finite numbers
        }
        return array.toString();
    }

    static List<SleepWidgetConfig> load(ContentResolver cr, int user) {
        return parse(Settings.System.getStringForUser(cr, EpdSettings.SLEEP_WIDGETS, user));
    }

    static void save(ContentResolver cr, int user, List<SleepWidgetConfig> list) {
        Settings.System.putStringForUser(cr, EpdSettings.SLEEP_WIDGETS,
                list.isEmpty() ? "" : serialize(list), user);
    }

    static int indexOf(List<SleepWidgetConfig> list, int id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) return i;
        }
        return -1;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : v > max ? max : v;
    }
}
