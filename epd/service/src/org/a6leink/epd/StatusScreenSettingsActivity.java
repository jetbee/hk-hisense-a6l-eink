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

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The "E-ink" settings page: the status screen (spec 02 section 6.6, clock on
 * the sleeping screen and its style; changes take effect at the next draw),
 * the sleeping screen's widgets and notifications (spec 03 sections 3 to 5)
 * and, under "Buttons", the optional swap of the buttons while the E-ink is in
 * use (spec 02 section 9.2), and under "Lock screen" the refresh mode of the
 * lock screen on the E-ink (spec 02 section 10 item 4a).
 */
public class StatusScreenSettingsActivity extends Activity {
    private static final String TAG = "A6lEpdWidgets";
    private static final int REQUEST_BIND = 1;
    private static final int REQUEST_CONFIGURE = 2;
    private static final String STATE_PENDING = "pending_widget_id";
    private static final int[] SIZE_IDS = { R.id.widget_size_default, R.id.widget_size_small,
            R.id.widget_size_medium, R.id.widget_size_large };
    private static final int[] SIZE_LABELS = { R.string.sleep_widget_size_default,
            R.string.sleep_widget_size_small, R.string.sleep_widget_size_medium,
            R.string.sleep_widget_size_large };

    private Switch mClock;
    private RadioGroup mStyle;
    private ContentResolver mResolver;
    private int mUser;

    // Widgets (system user only: the service and its widget host run there).
    private AppWidgetHost mHost;
    private AppWidgetManager mManager;
    private List<SleepWidgetConfig> mWidgets = new ArrayList<>();
    /** Allocated, not yet added (binding or configuration in progress); -1 if none. */
    private int mPendingId = -1;
    private LinearLayout mWidgetList;
    private TextView mWidgetEmpty;
    private View mWidgetAdd;
    private TextView mMaxValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.status_screen_settings);
        mClock = findViewById(R.id.sleep_clock);
        mStyle = findViewById(R.id.clock_style);

        final ContentResolver cr = getContentResolver();
        final int user = UserHandle.myUserId();
        mResolver = cr;
        mUser = user;
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
        Switch keyguardClear = findViewById(R.id.keyguard_clear);
        keyguardClear.setChecked(Settings.System.getIntForUser(cr, EpdSettings.KEYGUARD_CLEAR, 0,
                user) != 0);
        keyguardClear.setOnCheckedChangeListener((button, checked) ->
                Settings.System.putIntForUser(cr, EpdSettings.KEYGUARD_CLEAR,
                        checked ? 1 : 0, user));
        mStyle.setOnCheckedChangeListener((group, id) ->
                Settings.System.putIntForUser(cr, EpdSettings.SLEEP_CLOCK_STYLE,
                        id == R.id.clock_style_digital ? EpdSettings.CLOCK_STYLE_DIGITAL
                                : EpdSettings.CLOCK_STYLE_ANALOG, user));
        setUpWidgets(savedInstanceState);
        setUpNotifications();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PENDING, mPendingId);
    }

    // ---------------------------------------------------------------------------------------
    // Widgets (spec 03 section 3)

    private void setUpWidgets(Bundle savedInstanceState) {
        mWidgetList = findViewById(R.id.widget_list);
        mWidgetEmpty = findViewById(R.id.widget_empty);
        mWidgetAdd = findViewById(R.id.widget_add);
        if (mUser != UserHandle.USER_SYSTEM) {
            mWidgetList.setVisibility(View.GONE);
            mWidgetEmpty.setVisibility(View.GONE);
            mWidgetAdd.setVisibility(View.GONE);
            findViewById(R.id.widget_unavailable).setVisibility(View.VISIBLE);
            return;
        }
        mHost = new AppWidgetHost(this, EpdSettings.WIDGET_HOST_ID);
        mManager = AppWidgetManager.getInstance(this);
        if (savedInstanceState != null) {
            mPendingId = savedInstanceState.getInt(STATE_PENDING, -1);
        }
        mWidgets = SleepWidgetConfig.load(mResolver, mUser);
        deleteOrphanIds();
        mWidgetAdd.setOnClickListener(v -> pickProvider());
        refreshWidgets();
    }

    /** Frees ids of this host that are in no list (e.g. left by an interrupted add). */
    private void deleteOrphanIds() {
        try {
            for (int id : mHost.getAppWidgetIds()) {
                if (id != mPendingId && SleepWidgetConfig.indexOf(mWidgets, id) < 0) {
                    mHost.deleteAppWidgetId(id);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot list the widget ids", e);
        }
    }

    private void refreshWidgets() {
        mWidgetList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < mWidgets.size(); i++) {
            final int index = i;
            final SleepWidgetConfig cfg = mWidgets.get(i);
            View row = inflater.inflate(R.layout.sleep_widget_row, mWidgetList, false);
            AppWidgetProviderInfo info = mManager.getAppWidgetInfo(cfg.id);
            ((TextView) row.findViewById(R.id.widget_row_title)).setText(info != null
                    ? info.loadLabel(getPackageManager()) : getString(R.string.sleep_widget_missing));
            ((TextView) row.findViewById(R.id.widget_row_summary)).setText(summary(cfg, info));
            row.findViewById(R.id.widget_row_text).setOnClickListener(v -> showOptions(index));
            View up = row.findViewById(R.id.widget_row_up);
            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            up.setOnClickListener(v -> move(index, -1));
            View down = row.findViewById(R.id.widget_row_down);
            down.setEnabled(i < mWidgets.size() - 1);
            down.setAlpha(i < mWidgets.size() - 1 ? 1f : 0.3f);
            down.setOnClickListener(v -> move(index, 1));
            row.findViewById(R.id.widget_row_remove).setOnClickListener(v -> remove(index));
            mWidgetList.addView(row);
        }
        mWidgetEmpty.setVisibility(mWidgets.isEmpty() ? View.VISIBLE : View.GONE);
        mWidgetAdd.setEnabled(mWidgets.size() < EpdSettings.MAX_SLEEP_WIDGETS);
    }

    private CharSequence summary(SleepWidgetConfig cfg, AppWidgetProviderInfo info) {
        StringBuilder sb = new StringBuilder();
        if (info != null) {
            PackageManager pm = getPackageManager();
            try {
                sb.append(pm.getApplicationLabel(pm.getApplicationInfo(
                        info.provider.getPackageName(), 0)));
            } catch (PackageManager.NameNotFoundException e) {
                sb.append(info.provider.getPackageName());
            }
        }
        String sep = getString(R.string.sleep_widget_options_separator);
        if (sb.length() > 0) sb.append(sep);
        sb.append(getString(SIZE_LABELS[cfg.size]));
        if (cfg.invert) sb.append(sep).append(getString(R.string.sleep_widget_invert_short));
        if (cfg.contrast == SleepWidgetConfig.CONTRAST_HIGH) {
            sb.append(sep).append(getString(R.string.sleep_widget_contrast_high));
        }
        return sb;
    }

    private void pickProvider() {
        if (mWidgets.size() >= EpdSettings.MAX_SLEEP_WIDGETS) return;
        final List<AppWidgetProviderInfo> providers = new ArrayList<>(
                mManager.getInstalledProvidersForProfile(Process.myUserHandle()));
        if (providers.isEmpty()) {
            Toast.makeText(this, R.string.sleep_widgets_none_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        final PackageManager pm = getPackageManager();
        final Collator collator = Collator.getInstance();
        final List<String> labels = new ArrayList<>();
        Collections.sort(providers, (a, b) -> collator.compare(label(pm, a), label(pm, b)));
        for (AppWidgetProviderInfo p : providers) labels.add(label(pm, p));
        new AlertDialog.Builder(this)
                .setTitle(R.string.sleep_widgets_pick)
                .setItems(labels.toArray(new CharSequence[0]),
                        (d, which) -> bindProvider(providers.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Widget label (App)". */
    private static String label(PackageManager pm, AppWidgetProviderInfo info) {
        String widget = info.loadLabel(pm);
        String app;
        try {
            app = pm.getApplicationLabel(pm.getApplicationInfo(
                    info.provider.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            app = info.provider.getPackageName();
        }
        return widget == null || widget.equals(app) ? app : widget + " (" + app + ")";
    }

    private void bindProvider(AppWidgetProviderInfo info) {
        final int id;
        try {
            id = mHost.allocateAppWidgetId();
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot allocate a widget id", e);
            Toast.makeText(this, R.string.sleep_widgets_add_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        mPendingId = id;
        // uid system holds BIND_APPWIDGET, so no prompt is needed.
        if (mManager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null)) {
            configureOrAdd(id);
            return;
        }
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            startActivityForResult(bind, REQUEST_BIND);
        } catch (ActivityNotFoundException e) {
            cancelPending();
        }
    }

    /** Runs the provider's configuration activity if it has one, else adds the widget. */
    private void configureOrAdd(int id) {
        AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        if (info == null) {
            cancelPending();
            return;
        }
        if (info.configure != null) {
            try {
                mHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE,
                        null);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Cannot start the configuration of widget " + id, e);
                cancelPending();
                return;
            }
        }
        addPending();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mHost == null || mPendingId < 0) return;
        if (resultCode != RESULT_OK) {
            cancelPending();
        } else if (requestCode == REQUEST_BIND) {
            configureOrAdd(mPendingId);
        } else if (requestCode == REQUEST_CONFIGURE) {
            addPending();
        }
    }

    private void addPending() {
        if (SleepWidgetConfig.indexOf(mWidgets, mPendingId) < 0
                && mWidgets.size() < EpdSettings.MAX_SLEEP_WIDGETS) {
            SleepWidgetConfig cfg = new SleepWidgetConfig(mPendingId);
            mWidgets.add(cfg);
            saveWidgets();
            reportSize(cfg);
        }
        mPendingId = -1;
        refreshWidgets();
    }

    private void cancelPending() {
        if (mPendingId >= 0) {
            try {
                mHost.deleteAppWidgetId(mPendingId);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot delete widget " + mPendingId, e);
            }
            mPendingId = -1;
            Toast.makeText(this, R.string.sleep_widgets_add_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void remove(int index) {
        SleepWidgetConfig cfg = mWidgets.remove(index);
        saveWidgets();
        try {
            mHost.deleteAppWidgetId(cfg.id);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot delete widget " + cfg.id, e);
        }
        refreshWidgets();
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= mWidgets.size()) return;
        Collections.swap(mWidgets, index, to);
        saveWidgets();
        refreshWidgets();
    }

    private void showOptions(int index) {
        final SleepWidgetConfig cfg = mWidgets.get(index);
        final View view = getLayoutInflater().inflate(R.layout.sleep_widget_options, null);
        final RadioGroup size = view.findViewById(R.id.widget_size);
        final CheckBox invert = view.findViewById(R.id.widget_invert);
        final CheckBox high = view.findViewById(R.id.widget_contrast_high);
        size.check(SIZE_IDS[cfg.size]);
        invert.setChecked(cfg.invert);
        high.setChecked(cfg.contrast == SleepWidgetConfig.CONTRAST_HIGH);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sleep_widget_options)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int checked = size.getCheckedRadioButtonId();
                    for (int s = 0; s < SIZE_IDS.length; s++) {
                        if (SIZE_IDS[s] == checked) cfg.size = s;
                    }
                    cfg.invert = invert.isChecked();
                    cfg.contrast = high.isChecked() ? SleepWidgetConfig.CONTRAST_HIGH
                            : SleepWidgetConfig.CONTRAST_NORMAL;
                    saveWidgets();
                    reportSize(cfg);
                    refreshWidgets();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveWidgets() {
        SleepWidgetConfig.save(mResolver, mUser, mWidgets);
    }

    /**
     * Tells the provider the size it gets on the E-ink, in the E-ink's dp: 672 px
     * wide and the chosen height (once per change; no update is requested).
     */
    private void reportSize(SleepWidgetConfig cfg) {
        try {
            AppWidgetProviderInfo info = mManager.getAppWidgetInfo(cfg.id);
            if (info == null) return;
            float density = getResources().getDisplayMetrics().density;
            float scale = SleepWidgetHost.deviceToPanelScale(this);
            int heightPx = cfg.size > SleepWidgetConfig.SIZE_DEFAULT
                    ? SleepWidgetConfig.SIZE_HEIGHTS[cfg.size]
                    : Math.round(info.minHeight * scale);
            int widthDp = Math.round(
                    (EpdSettings.EINK_WIDTH - 48) / scale / density);
            int heightDp = Math.round(heightPx / scale / density);
            Bundle options = new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
            mManager.updateAppWidgetOptions(cfg.id, options);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot report the size of widget " + cfg.id, e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notifications and section order (spec 03 sections 2, 4 and 5)

    private void setUpNotifications() {
        Switch notifications = findViewById(R.id.sleep_notifications);
        notifications.setChecked(Settings.System.getIntForUser(mResolver,
                EpdSettings.SLEEP_NOTIFICATIONS, 1, mUser) != 0);
        notifications.setOnCheckedChangeListener((b, checked) ->
                Settings.System.putIntForUser(mResolver, EpdSettings.SLEEP_NOTIFICATIONS,
                        checked ? 1 : 0, mUser));

        mMaxValue = findViewById(R.id.notifications_max_value);
        updateMaxValue();
        findViewById(R.id.notifications_max).setOnClickListener(v -> {
            String[] entries = getResources().getStringArray(
                    R.array.sleep_notifications_max_entries);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.sleep_notifications_max)
                    .setSingleChoiceItems(entries, maxIndex(), (d, which) -> {
                        // Entry 0 = as many as fit (-1); entry k = k - 1.
                        Settings.System.putIntForUser(mResolver,
                                EpdSettings.SLEEP_NOTIFICATIONS_MAX, which - 1, mUser);
                        updateMaxValue();
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        RadioGroup order = findViewById(R.id.sections_order);
        order.check(Settings.System.getIntForUser(mResolver, EpdSettings.SLEEP_SECTIONS_ORDER,
                EpdSettings.ORDER_WIDGETS_FIRST, mUser) == EpdSettings.ORDER_NOTIFICATIONS_FIRST
                ? R.id.sections_notifications_first : R.id.sections_widgets_first);
        order.setOnCheckedChangeListener((g, id) ->
                Settings.System.putIntForUser(mResolver, EpdSettings.SLEEP_SECTIONS_ORDER,
                        id == R.id.sections_notifications_first
                                ? EpdSettings.ORDER_NOTIFICATIONS_FIRST
                                : EpdSettings.ORDER_WIDGETS_FIRST, mUser));
    }

    private int maxIndex() {
        int max = Settings.System.getIntForUser(mResolver, EpdSettings.SLEEP_NOTIFICATIONS_MAX,
                EpdSettings.NOTIFICATIONS_MAX_FIT, mUser);
        if (max < 0) return 0;
        return Math.min(max, EpdSettings.NOTIFICATIONS_MAX_LIMIT) + 1;
    }

    private void updateMaxValue() {
        mMaxValue.setText(getResources().getStringArray(
                R.array.sleep_notifications_max_entries)[maxIndex()]);
    }

    private void setStyleEnabled(boolean enabled) {
        for (int i = 0; i < mStyle.getChildCount(); i++) {
            mStyle.getChildAt(i).setEnabled(enabled);
        }
    }
}
