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

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.hmct.epd.EpdManager;

/**
 * E-ink tuning (spec 04 section 6.1 item 4): contrast, black threshold and
 * white threshold through getEinkEffect / adjustEinkEffect (0..100, defaults
 * 0 / 30 / 20, spec 02 section 3.3), a reset, and "Clear ghosting now"
 * (forceClear, spec 02 section 3.2). The service stores the values in its
 * persist properties and applies them to the panel whenever the E-ink is
 * ready, so this page keeps no state of its own.
 */
public class TuningActivity extends Activity {
    private EpdManager mEpd;
    private final SeekBar[] mBars = new SeekBar[Epd.EFFECT_TYPES.length];
    private final TextView[] mValues = new TextView[Epd.EFFECT_TYPES.length];
    private final boolean[] mTracking = new boolean[Epd.EFFECT_TYPES.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tuning);
        mBars[0] = findViewById(R.id.contrast);
        mBars[1] = findViewById(R.id.black);
        mBars[2] = findViewById(R.id.white);
        mValues[0] = findViewById(R.id.contrast_value);
        mValues[1] = findViewById(R.id.black_value);
        mValues[2] = findViewById(R.id.white_value);

        for (int i = 0; i < mBars.length; i++) {
            final int index = i;
            mBars[i].setMax(Epd.EFFECT_MAX);
            mBars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    mValues[index].setText(Integer.toString(progress));
                    // Keyboard / accessibility changes have no touch tracking.
                    if (fromUser && !mTracking[index]) apply(index, progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    mTracking[index] = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    // Applied once on release: every change redraws the E-ink.
                    mTracking[index] = false;
                    apply(index, bar.getProgress());
                }
            });
        }

        Button reset = findViewById(R.id.reset);
        reset.setOnClickListener(v -> {
            for (int i = 0; i < mBars.length; i++) {
                mBars[i].setProgress(Epd.EFFECT_DEFAULTS[i]);
                apply(i, Epd.EFFECT_DEFAULTS[i]);
            }
        });
        Button clear = findViewById(R.id.force_clear);
        clear.setOnClickListener(v -> {
            if (mEpd != null) mEpd.forceClear();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mEpd = Epd.get(this);
        boolean available = mEpd != null;
        for (int i = 0; i < mBars.length; i++) {
            int value = available ? mEpd.getEinkEffect(Epd.EFFECT_TYPES[i]) : -1;
            if (value < Epd.EFFECT_MIN || value > Epd.EFFECT_MAX) {
                value = Epd.EFFECT_DEFAULTS[i];
            }
            mBars[i].setProgress(value);
            mValues[i].setText(Integer.toString(value));
            mBars[i].setEnabled(available);
        }
        findViewById(R.id.reset).setEnabled(available);
        findViewById(R.id.force_clear).setEnabled(available);
        TextView explain = findViewById(R.id.tuning_explain);
        explain.setText(available ? R.string.tuning_explain : R.string.service_unavailable);
    }

    private void apply(int index, int value) {
        if (mEpd == null) return;
        value = Math.max(Epd.EFFECT_MIN, Math.min(Epd.EFFECT_MAX, value));
        mEpd.adjustEinkEffect(Epd.EFFECT_TYPES[index], value);
    }
}
