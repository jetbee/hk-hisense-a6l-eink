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

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-app refresh modes on disk (spec 02 section 4.1) and the service's own
 * per-app options and switches (spec 05 section 6.4). The file lives in the
 * app's device-protected storage so it can be read before the first unlock.
 * The format is private to this service:
 * {@code <app-modes><app package="..." mode="..."/><slide package="..."/>
 * <option name="..." value="0|1"/></app-modes>}. Files written before the
 * options existed only have {@code app} elements and load unchanged.
 */
final class AppModeStore {
    private static final String TAG = "A6lEpdStore";
    private static final String FILE_NAME = "epd_app_modes.xml";
    private static final String TAG_ROOT = "app-modes";
    private static final String TAG_APP = "app";
    private static final String TAG_SLIDE = "slide";
    private static final String TAG_OPTION = "option";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_MODE = "mode";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_VALUE = "value";

    /** Everything the file holds. */
    static final class Data {
        /** Package (or activity class / stock-style key) to refresh mode. */
        final Map<String, Integer> modes = new HashMap<>();
        /** Packages with "slide one screen" on (experimental, spec 05 section 6.3). */
        final Set<String> slide = new HashSet<>();
        /** Global switches by name; absent = default. */
        final Map<String, Boolean> options = new HashMap<>();
    }

    private final AtomicFile mFile;

    AppModeStore(Context context) {
        Context dp = context.createDeviceProtectedStorageContext();
        mFile = new AtomicFile(new File(dp.getFilesDir(), FILE_NAME));
    }

    /** Reads the file; a missing or unreadable file gives empty data. */
    Data load() {
        Data result = new Data();
        if (!mFile.exists()) return result;
        try (FileInputStream in = mFile.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                if (TAG_SLIDE.equals(parser.getName())) {
                    String pkg = parser.getAttributeValue(null, ATTR_PACKAGE);
                    if (pkg != null && !pkg.isEmpty()) result.slide.add(pkg);
                    continue;
                }
                if (TAG_OPTION.equals(parser.getName())) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    String value = parser.getAttributeValue(null, ATTR_VALUE);
                    if (name != null && value != null) {
                        result.options.put(name, "1".equals(value));
                    }
                    continue;
                }
                if (!TAG_APP.equals(parser.getName())) continue;
                String pkg = parser.getAttributeValue(null, ATTR_PACKAGE);
                String mode = parser.getAttributeValue(null, ATTR_MODE);
                if (pkg == null || mode == null) continue;
                try {
                    result.modes.put(pkg, Integer.parseInt(mode));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Bad mode for " + pkg + ": " + mode);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Cannot read " + mFile.getBaseFile() + ", starting empty", e);
            result = new Data();
        }
        return result;
    }

    /** Writes everything atomically. */
    void save(Data data) {
        FileOutputStream out = null;
        try {
            out = mFile.startWrite();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(out, StandardCharsets.UTF_8.name());
            xml.startDocument(null, true);
            xml.startTag(null, TAG_ROOT);
            for (Map.Entry<String, Integer> e : data.modes.entrySet()) {
                xml.startTag(null, TAG_APP);
                xml.attribute(null, ATTR_PACKAGE, e.getKey());
                xml.attribute(null, ATTR_MODE, Integer.toString(e.getValue()));
                xml.endTag(null, TAG_APP);
            }
            for (String pkg : data.slide) {
                xml.startTag(null, TAG_SLIDE);
                xml.attribute(null, ATTR_PACKAGE, pkg);
                xml.endTag(null, TAG_SLIDE);
            }
            for (Map.Entry<String, Boolean> e : data.options.entrySet()) {
                xml.startTag(null, TAG_OPTION);
                xml.attribute(null, ATTR_NAME, e.getKey());
                xml.attribute(null, ATTR_VALUE, e.getValue() ? "1" : "0");
                xml.endTag(null, TAG_OPTION);
            }
            xml.endTag(null, TAG_ROOT);
            xml.endDocument();
            mFile.finishWrite(out);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Cannot write " + mFile.getBaseFile(), e);
            if (out != null) mFile.failWrite(out);
        }
    }
}
