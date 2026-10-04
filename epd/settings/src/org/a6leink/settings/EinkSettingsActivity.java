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

package org.a6leink.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

/**
 * Page injected into Settings > Display ("E-ink apps &amp; tuning", spec 04
 * section 6.4). Links to the per-app display mode and to the tuning page, and
 * holds the automatic switches of spec 05 section 6.3 (smooth while the front
 * app uses the camera or plays a video), which the service stores.
 */
public class EinkSettingsActivity extends Activity {
    private View mAppMode;
    private View mTuning;
    private View mUnavailable;
    private Switch mAutoCamera;
    private Switch mAutoVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.eink_settings);
        mAppMode = findViewById(R.id.entry_app_mode);
        mTuning = findViewById(R.id.entry_tuning);
        mUnavailable = findViewById(R.id.unavailable);
        mAppMode.setOnClickListener(v ->
                startActivity(new Intent(this, AppModeActivity.class)));
        mTuning.setOnClickListener(v ->
                startActivity(new Intent(this, TuningActivity.class)));
        mAutoCamera = findViewById(R.id.auto_camera);
        mAutoVideo = findViewById(R.id.auto_video);
    }

    private void bindOption(Switch view, String option, Bundle config) {
        view.setOnCheckedChangeListener(null);
        view.setEnabled(config != null);
        view.setChecked(config != null && config.getBoolean(option, true));
        view.jumpDrawablesToCurrentState(); // no thumb animation on the E-ink
        view.setOnCheckedChangeListener((CompoundButton button, boolean on) -> {
            if (!Epd.setOption(this, option, on)) {
                // Not stored: show the service's state again.
                bindOption(view, option, Epd.getConfig(this));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean available = Epd.get(this) != null;
        mAppMode.setEnabled(available);
        mTuning.setEnabled(available);
        mAppMode.setAlpha(available ? 1f : 0.4f);
        mTuning.setAlpha(available ? 1f : 0.4f);
        mUnavailable.setVisibility(available ? View.GONE : View.VISIBLE);
        Bundle config = available ? Epd.getConfig(this) : null;
        bindOption(mAutoCamera, Epd.OPTION_AUTO_CAMERA, config);
        bindOption(mAutoVideo, Epd.OPTION_AUTO_VIDEO, config);
    }
}
