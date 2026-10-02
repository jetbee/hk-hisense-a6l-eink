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
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * Page injected into Settings > Display ("E-ink apps &amp; tuning", spec 04
 * section 6.4). Links to the per-app display mode and to the tuning page.
 */
public class EinkSettingsActivity extends Activity {
    private View mAppMode;
    private View mTuning;
    private View mUnavailable;

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
    }
}
