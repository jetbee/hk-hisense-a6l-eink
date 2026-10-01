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
import java.util.Map;

/**
 * Per-app refresh modes on disk (spec 02 section 4.1). The file lives in the
 * app's device-protected storage so it can be read before the first unlock.
 * The format is private to this service:
 * {@code <app-modes><app package="..." mode="..."/></app-modes>}.
 */
final class AppModeStore {
    private static final String TAG = "A6lEpdStore";
    private static final String FILE_NAME = "epd_app_modes.xml";
    private static final String TAG_ROOT = "app-modes";
    private static final String TAG_APP = "app";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_MODE = "mode";

    private final AtomicFile mFile;

    AppModeStore(Context context) {
        Context dp = context.createDeviceProtectedStorageContext();
        mFile = new AtomicFile(new File(dp.getFilesDir(), FILE_NAME));
    }

    /** Reads the map; a missing or unreadable file gives an empty map. */
    Map<String, Integer> load() {
        Map<String, Integer> result = new HashMap<>();
        if (!mFile.exists()) return result;
        try (FileInputStream in = mFile.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !TAG_APP.equals(parser.getName())) {
                    continue;
                }
                String pkg = parser.getAttributeValue(null, ATTR_PACKAGE);
                String mode = parser.getAttributeValue(null, ATTR_MODE);
                if (pkg == null || mode == null) continue;
                try {
                    result.put(pkg, Integer.parseInt(mode));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Bad mode for " + pkg + ": " + mode);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Cannot read " + mFile.getBaseFile() + ", starting empty", e);
            result.clear();
        }
        return result;
    }

    /** Writes the map atomically. */
    void save(Map<String, Integer> modes) {
        FileOutputStream out = null;
        try {
            out = mFile.startWrite();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(out, StandardCharsets.UTF_8.name());
            xml.startDocument(null, true);
            xml.startTag(null, TAG_ROOT);
            for (Map.Entry<String, Integer> e : modes.entrySet()) {
                xml.startTag(null, TAG_APP);
                xml.attribute(null, ATTR_PACKAGE, e.getKey());
                xml.attribute(null, ATTR_MODE, Integer.toString(e.getValue()));
                xml.endTag(null, TAG_APP);
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
