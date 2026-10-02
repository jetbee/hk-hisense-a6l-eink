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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import com.hmct.epd.EpdManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-app E-ink display mode (spec 04 sections 3 and 6.1).
 *
 * <p>Lists every package with a launcher activity except input methods, in
 * two groups. "Smooth" means no entry in the service (removeAppMode), so the
 * app gets the service's default; "Clear" means an explicit entry with
 * {@link Epd#MODE_CLEAR} (addAppMode). Any other stored value is shown in the
 * "Clear" group as a custom mode and left alone until the user changes it.
 * Changes go straight to the service, which persists them; there is no save
 * step, no automatic entry for new apps and no bundled list.
 */
public class AppModeActivity extends Activity {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    /** One listed package. {@code mode} is null for "Smooth" (no entry). */
    private static final class AppEntry {
        final String pkg;
        final String label;
        final Drawable icon;
        Integer mode;

        AppEntry(String pkg, String label, Drawable icon, Integer mode) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.mode = mode;
        }

        boolean isClear() {
            return mode != null;
        }
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** Binder calls and package queries, in order, off the main thread. */
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final List<AppEntry> mApps = new ArrayList<>();
    /** Headers (String) and apps (AppEntry), in display order. */
    private final List<Object> mRows = new ArrayList<>();

    private EpdManager mEpd;
    private Collator mCollator;
    private int mLoadGeneration;
    private TextView mStatus;
    private ListView mList;
    private Button mAllSmooth;
    private Button mAllClear;
    private Adapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.app_mode);
        mStatus = findViewById(R.id.status);
        mList = findViewById(android.R.id.list);
        mAllSmooth = findViewById(R.id.all_smooth);
        mAllClear = findViewById(R.id.all_clear);
        mCollator = Collator.getInstance();
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((parent, view, position, id) -> {
            Object row = mRows.get(position);
            if (row instanceof AppEntry) toggle((AppEntry) row);
        });
        mAllSmooth.setOnClickListener(v -> setAll(false));
        mAllClear.setOnClickListener(v -> setAll(true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reloaded on every resume: apps may have been installed or removed,
        // and another tool may have changed the service's entries.
        load();
    }

    @Override
    protected void onDestroy() {
        mWorker.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        final int generation = ++mLoadGeneration;
        final PackageManager pm = getPackageManager();
        final InputMethodManager imm = getSystemService(InputMethodManager.class);
        final EpdManager epd = Epd.get(this);
        mWorker.execute(() -> {
            List<AppEntry> apps = null;
            Map<?, ?> modes = epd != null ? epd.getAllAppMode() : null;
            if (modes != null) apps = queryApps(pm, imm, modes);
            final List<AppEntry> result = apps;
            mMain.post(() -> {
                if (isDestroyed() || generation != mLoadGeneration) return;
                mEpd = result != null ? epd : null;
                mApps.clear();
                if (result != null) mApps.addAll(result);
                rebuild();
            });
        });
    }

    /** Runs on the worker. One row per package that has a launcher activity. */
    private List<AppEntry> queryApps(PackageManager pm, InputMethodManager imm, Map<?, ?> modes) {
        Set<String> excluded = new HashSet<>();
        excluded.add(getPackageName());
        if (imm != null) {
            for (InputMethodInfo info : imm.getInputMethodList()) {
                excluded.add(info.getPackageName());
            }
        }
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, AppEntry> byPackage = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (excluded.contains(pkg) || byPackage.containsKey(pkg)) continue;
            Object value = modes.get(pkg);
            Integer mode = value instanceof Integer ? (Integer) value : null;
            CharSequence label = ri.activityInfo.applicationInfo.loadLabel(pm);
            Drawable icon = ri.activityInfo.applicationInfo.loadIcon(pm);
            byPackage.put(pkg, new AppEntry(pkg, label != null ? label.toString() : pkg,
                    icon, mode));
        }
        return new ArrayList<>(byPackage.values());
    }

    /** Rebuilds the two groups, sorted by label, and the button states. */
    private void rebuild() {
        List<AppEntry> smooth = new ArrayList<>();
        List<AppEntry> clear = new ArrayList<>();
        for (AppEntry app : mApps) {
            (app.isClear() ? clear : smooth).add(app);
        }
        Collections.sort(smooth, (a, b) -> mCollator.compare(a.label, b.label));
        Collections.sort(clear, (a, b) -> mCollator.compare(a.label, b.label));

        mRows.clear();
        if (!smooth.isEmpty()) {
            mRows.add(getString(R.string.app_mode_smooth_header, smooth.size()));
            mRows.addAll(smooth);
        }
        if (!clear.isEmpty()) {
            mRows.add(getString(R.string.app_mode_clear_header, clear.size()));
            mRows.addAll(clear);
        }
        mAdapter.notifyDataSetChanged();

        if (mEpd == null) {
            mStatus.setText(R.string.service_unavailable);
        } else if (mApps.isEmpty()) {
            mStatus.setText(R.string.app_mode_empty);
        } else {
            mStatus.setText(R.string.app_mode_explain);
        }
        mAllSmooth.setEnabled(mEpd != null && !clear.isEmpty());
        mAllClear.setEnabled(mEpd != null && !smooth.isEmpty());
    }

    private void toggle(AppEntry app) {
        if (mEpd == null) return;
        final boolean toClear = !app.isClear();
        app.mode = toClear ? Integer.valueOf(Epd.MODE_CLEAR) : null;
        final EpdManager epd = mEpd;
        final String pkg = app.pkg;
        mWorker.execute(() -> write(epd, pkg, toClear));
        rebuild();
    }

    /** "All smooth" / "All clear": one call per listed app that changes. */
    private void setAll(boolean clear) {
        if (mEpd == null) return;
        final EpdManager epd = mEpd;
        final List<String> changed = new ArrayList<>();
        for (AppEntry app : mApps) {
            if (app.isClear() == clear) continue;
            app.mode = clear ? Integer.valueOf(Epd.MODE_CLEAR) : null;
            changed.add(app.pkg);
        }
        mWorker.execute(() -> {
            for (String pkg : changed) write(epd, pkg, clear);
        });
        rebuild();
    }

    /** Runs on the worker. */
    private static void write(EpdManager epd, String pkg, boolean clear) {
        if (clear) {
            epd.addAppMode(pkg, Epd.MODE_CLEAR);
        } else {
            epd.removeAppMode(pkg);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position) instanceof AppEntry ? TYPE_APP : TYPE_HEADER;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return mRows.get(position) instanceof AppEntry;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            Object row = mRows.get(position);
            LayoutInflater inflater = getLayoutInflater();
            if (!(row instanceof AppEntry)) {
                if (view == null) {
                    view = inflater.inflate(R.layout.app_mode_header, parent, false);
                }
                ((TextView) view).setText((String) row);
                return view;
            }
            AppEntry app = (AppEntry) row;
            if (view == null) view = inflater.inflate(R.layout.app_mode_row, parent, false);
            ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) view.findViewById(R.id.label)).setText(app.label);
            TextView summary = view.findViewById(R.id.summary);
            if (!app.isClear()) {
                summary.setText(R.string.app_mode_smooth);
            } else if (app.mode == Epd.MODE_CLEAR) {
                summary.setText(R.string.app_mode_clear);
            } else {
                summary.setText(getString(R.string.app_mode_custom, app.mode));
            }
            Switch toggle = view.findViewById(R.id.clear_switch);
            toggle.setChecked(app.isClear());
            toggle.jumpDrawablesToCurrentState(); // no thumb animation on the E-ink
            view.setContentDescription(app.label + ", " + summary.getText());
            return view;
        }
    }
}
