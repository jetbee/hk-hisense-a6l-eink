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

import android.app.Activity;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.RadioGroup;
import android.widget.Switch;

/**
 * The "E-ink" settings page: the status screen (spec 02 section 6.6, clock on
 * the sleeping screen and its style; changes take effect at the next draw)
 * and, under "Buttons", the optional swap of the buttons while the E-ink is in
 * use (spec 02 section 9.2).
 */
public class StatusScreenSettingsActivity extends Activity {
    private Switch mClock;
    private RadioGroup mStyle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.status_screen_settings);
        mClock = findViewById(R.id.sleep_clock);
        mStyle = findViewById(R.id.clock_style);

        final ContentResolver cr = getContentResolver();
        final int user = UserHandle.myUserId();
        boolean clock = Settings.System.getIntForUser(cr, EpdSettings.SLEEP_CLOCK, 1, user) != 0;
        int style = Settings.System.getIntForUser(cr, EpdSettings.SLEEP_CLOCK_STYLE,
                EpdSettings.CLOCK_STYLE_ANALOG, user);

        mClock.setChecked(clock);
        mStyle.check(style == EpdSettings.CLOCK_STYLE_DIGITAL
                ? R.id.clock_style_digital : R.id.clock_style_analog);
        setStyleEnabled(clock);

        mClock.setOnCheckedChangeListener((button, checked) -> {
            Settings.System.putIntForUser(cr, EpdSettings.SLEEP_CLOCK, checked ? 1 : 0, user);
            setStyleEnabled(checked);
        });
        Switch swap = findViewById(R.id.swap_keys_on_eink);
        swap.setChecked(Settings.System.getIntForUser(cr, EpdSettings.SWAP_KEYS_ON_EINK, 0,
                user) != 0);
        swap.setOnCheckedChangeListener((button, checked) ->
                Settings.System.putIntForUser(cr, EpdSettings.SWAP_KEYS_ON_EINK,
                        checked ? 1 : 0, user));
        mStyle.setOnCheckedChangeListener((group, id) ->
                Settings.System.putIntForUser(cr, EpdSettings.SLEEP_CLOCK_STYLE,
                        id == R.id.clock_style_digital ? EpdSettings.CLOCK_STYLE_DIGITAL
                                : EpdSettings.CLOCK_STYLE_ANALOG, user));
    }

    private void setStyleEnabled(boolean enabled) {
        for (int i = 0; i < mStyle.getChildCount(); i++) {
            mStyle.getChildAt(i).setEnabled(enabled);
        }
    }
}
