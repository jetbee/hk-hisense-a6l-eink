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

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-finger unlock screen (spec 02 section 5.3). Edits {@code fp_unlock_screen}
 * in Settings.System for the user running this page: a JSON object mapping the
 * biometric id of each finger (decimal string) to {@code "0"} (LCD) or
 * {@code "1"} (E-ink). "Keep current screen" removes the finger's entry.
 */
@SuppressWarnings("deprecation") // FingerprintManager is what lists the enrolled fingers
public class FingerUnlockSettingsActivity extends Activity {
    private static final String TAG = "A6lEpdFingerUnlock";

    private static final int CHOICE_KEEP = 0;
    private static final int CHOICE_LCD = 1;
    private static final int CHOICE_EINK = 2;

    private ListView mList;
    private TextView mEmpty;
    private final List<Fingerprint> mFingers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.finger_unlock_settings);
        mList = findViewById(R.id.fingers);
        mEmpty = findViewById(R.id.empty);
        mList.setEmptyView(mEmpty);
        mList.setOnItemClickListener((parent, view, position, id) -> choose(position));
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        mFingers.clear();
        FingerprintManager fpm = getSystemService(FingerprintManager.class);
        if (fpm != null && fpm.isHardwareDetected()) {
            List<Fingerprint> enrolled = fpm.getEnrolledFingerprints(UserHandle.myUserId());
            if (enrolled != null) mFingers.addAll(enrolled);
        }
        final JSONObject map = readMap();
        final String[] labels = getResources().getStringArray(R.array.finger_unlock_choices);
        mList.setAdapter(new ArrayAdapter<Fingerprint>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, mFingers) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                Fingerprint fp = getItem(position);
                CharSequence name = fp.getName();
                ((TextView) v.findViewById(android.R.id.text1)).setText(
                        TextUtils.isEmpty(name)
                                ? getString(R.string.finger_unlock_unnamed, position + 1) : name);
                ((TextView) v.findViewById(android.R.id.text2)).setText(
                        labels[choiceOf(map, fp)]);
                return v;
            }
        });
    }

    private void choose(int position) {
        final Fingerprint fp = mFingers.get(position);
        new AlertDialog.Builder(this)
                .setTitle(fp.getName())
                .setSingleChoiceItems(R.array.finger_unlock_choices, choiceOf(readMap(), fp),
                        (dialog, which) -> {
                            write(fp, which);
                            dialog.dismiss();
                            reload();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int choiceOf(JSONObject map, Fingerprint fp) {
        String value = map.optString(Integer.toString(fp.getBiometricId()), null);
        if ("0".equals(value)) return CHOICE_LCD;
        if ("1".equals(value)) return CHOICE_EINK;
        return CHOICE_KEEP;
    }

    private JSONObject readMap() {
        String json = Settings.System.getStringForUser(getContentResolver(),
                EpdSettings.FP_UNLOCK_SCREEN, UserHandle.myUserId());
        if (!TextUtils.isEmpty(json)) {
            try {
                return new JSONObject(json);
            } catch (JSONException e) {
                Log.w(TAG, "Malformed " + EpdSettings.FP_UNLOCK_SCREEN + ", starting over");
            }
        }
        return new JSONObject();
    }

    private void write(Fingerprint fp, int choice) {
        JSONObject map = readMap();
        String key = Integer.toString(fp.getBiometricId());
        try {
            if (choice == CHOICE_LCD) {
                map.put(key, "0");
            } else if (choice == CHOICE_EINK) {
                map.put(key, "1");
            } else {
                map.remove(key);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Cannot update the map", e);
            return;
        }
        Settings.System.putStringForUser(getContentResolver(), EpdSettings.FP_UNLOCK_SCREEN,
                map.toString(), UserHandle.myUserId());
    }
}
