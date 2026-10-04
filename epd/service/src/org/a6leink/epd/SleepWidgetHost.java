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

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.RemoteViewsAdapter;
import android.widget.TextClock;

import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * The sleep screen's AppWidgetHost (spec 03 section 3). It lives on the
 * application's main thread, where the host delivers widget updates and
 * collection adapters deliver their rows; views are never attached to a
 * window. Each render request measures, lays out and draws the requested
 * widgets off-screen and hands the bitmaps back through a callback.
 *
 * Widgets are laid out at the phone's density and scaled down to the
 * E-ink's, so that a widget keeps roughly its physical size on the panel.
 */
final class SleepWidgetHost {
    private static final String TAG = "A6lEpdWidgets";

    /** Assumed density of the 720 x 1440 E-ink panel (about 276 ppi). */
    static final int EINK_DENSITY_DPI = 280;
    /** Spec 03 section 3: bounded wait for collection widgets, per redraw. */
    static final long COLLECTION_WAIT_MS = 1000;
    private static final long POLL_MS = 100;

    /** One widget to draw: its id, the panel size and the colour behind transparent parts. */
    static final class Request {
        final int id;
        final int width;
        final int height;
        final int background;

        Request(int id, int width, int height, int background) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.background = background;
        }
    }

    interface Callback {
        /** Main thread. One bitmap per request (panel size), null where a widget failed. */
        void onRendered(Bitmap[] bitmaps);
    }

    private final Context mContext;
    private final Handler mMain;
    // main thread
    private AppWidgetHost mHost;
    private AppWidgetManager mManager;
    private final SparseArray<AppWidgetHostView> mViews = new SparseArray<>();
    private volatile boolean mListening;
    private volatile boolean mReceiverRegistered;

    // dumpsys (written on the main thread)
    private volatile String mLastRender = "never";
    private volatile String mLastError;

    SleepWidgetHost(Context context) {
        mContext = context;
        mMain = new Handler(Looper.getMainLooper());
    }

    /** Starts listening now or, while the user is still locked, at unlock. Any thread. */
    void start() {
        mMain.post(() -> {
            // Created here, not in the constructor: the binder service is registered first.
            mHost = new AppWidgetHost(mContext, EpdSettings.WIDGET_HOST_ID);
            mManager = AppWidgetManager.getInstance(mContext);
            if (startListening()) return;
            IntentFilter f = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            f.addAction(Intent.ACTION_BOOT_COMPLETED);
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    startListening();
                }
            }, f, null, mMain);
            mReceiverRegistered = true;
        });
    }

    /** Main thread. Never stopped while the service runs (spec 03 section 3). */
    private boolean startListening() {
        if (mListening) return true;
        if (mHost == null) return false;
        try {
            mHost.startListening();
            mListening = true;
            Log.i(TAG, "Widget host " + EpdSettings.WIDGET_HOST_ID + " listening");
        } catch (RuntimeException e) {
            // AppWidgetService refuses hosts of a locked user.
            Log.i(TAG, "Widget host not listening yet: " + e.getMessage());
        }
        return mListening;
    }

    /**
     * Renders the widgets off-screen. Any thread; the callback runs on the main
     * thread after at most about {@link #COLLECTION_WAIT_MS}.
     */
    void render(Request[] requests, int[] configuredIds, Callback callback) {
        mMain.post(() -> new Job(requests, configuredIds, callback).start());
    }

    private final class Job {
        final Request[] requests;
        final int[] configuredIds;
        final Callback callback;
        final AppWidgetHostView[] views;
        final int[] devWidth;
        final int[] devHeight;
        final boolean[] failed;
        long deadline;
        int stablePolls;

        Job(Request[] requests, int[] configuredIds, Callback callback) {
            this.requests = requests;
            this.configuredIds = configuredIds;
            this.callback = callback;
            views = new AppWidgetHostView[requests.length];
            devWidth = new int[requests.length];
            devHeight = new int[requests.length];
            failed = new boolean[requests.length];
        }

        void start() {
            startListening();
            dropUnconfiguredViews(configuredIds);
            float scale = deviceToPanelScale(mContext);
            for (int i = 0; i < requests.length; i++) {
                Request r = requests[i];
                devWidth[i] = Math.max(1, Math.round(r.width / scale));
                devHeight[i] = Math.max(1, Math.round(r.height / scale));
                try {
                    views[i] = viewFor(r.id);
                    if (views[i] == null) {
                        failed[i] = true;
                        continue;
                    }
                    refreshClocks(views[i]);
                    layout(i);
                } catch (Throwable t) {
                    fail(i, "layout", t);
                }
            }
            deadline = SystemClock.uptimeMillis() + COLLECTION_WAIT_MS;
            poll();
        }

        /** Gives collection widgets a chance to load their rows (re-layout on change). */
        void poll() {
            boolean collections = false;
            boolean pending = false;
            for (int i = 0; i < views.length; i++) {
                if (failed[i]) continue;
                try {
                    int state = collectionState(views[i]);
                    if (state == COLLECTION_NONE) continue;
                    collections = true;
                    if (state == COLLECTION_LOADING) pending = true;
                    if (views[i].isLayoutRequested()) {
                        layout(i);
                        pending = true;
                    }
                } catch (Throwable t) {
                    fail(i, "layout", t);
                }
            }
            stablePolls = pending ? 0 : stablePolls + 1;
            // Rows load after the adapter is ready: wait for two quiet polls.
            boolean done = !collections || stablePolls >= 2;
            if (!done && SystemClock.uptimeMillis() + POLL_MS <= deadline) {
                mMain.postDelayed(this::poll, POLL_MS);
                return;
            }
            finish(collections && !done);
        }

        void finish(boolean timedOut) {
            Bitmap[] out = new Bitmap[requests.length];
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < requests.length; i++) {
                summary.append(i == 0 ? "" : ", ").append(requests[i].id);
                if (failed[i]) {
                    summary.append(" skipped");
                    continue;
                }
                try {
                    out[i] = draw(i);
                    summary.append(' ').append(requests[i].width).append('x')
                            .append(requests[i].height);
                } catch (Throwable t) {
                    fail(i, "draw", t);
                    summary.append(" failed");
                }
            }
            mLastRender = SystemClock.elapsedRealtime() + ": " + summary
                    + (timedOut ? " (collection wait ended)" : "");
            callback.onRendered(out);
        }

        private void layout(int i) {
            View v = views[i];
            v.measure(View.MeasureSpec.makeMeasureSpec(devWidth[i], View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(devHeight[i], View.MeasureSpec.EXACTLY));
            v.layout(0, 0, devWidth[i], devHeight[i]);
        }

        private Bitmap draw(int i) {
            if (views[i].isLayoutRequested()) layout(i);
            Bitmap dev = Bitmap.createBitmap(devWidth[i], devHeight[i], Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(dev);
                c.drawColor(requests[i].background);
                views[i].draw(c);
                if (dev.getWidth() == requests[i].width && dev.getHeight() == requests[i].height) {
                    Bitmap same = dev;
                    dev = null;
                    return same;
                }
                return Bitmap.createScaledBitmap(dev, requests[i].width, requests[i].height, true);
            } finally {
                if (dev != null) dev.recycle();
            }
        }

        private void fail(int i, String what, Throwable t) {
            failed[i] = true;
            mLastError = "widget " + requests[i].id + " " + what + ": " + t;
            Log.w(TAG, "Skipping widget " + requests[i].id + " (" + what + ")", t);
        }
    }

    /** Main thread. The host view of a widget, created on first use; null if it is gone. */
    private AppWidgetHostView viewFor(int id) {
        AppWidgetHostView v = mViews.get(id);
        if (v != null) return v;
        if (mHost == null) return null;
        AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        if (info == null) return null; // deleted, or the provider was uninstalled
        v = mHost.createView(mContext, id, info);
        if (v != null) mViews.put(id, v);
        return v;
    }

    private void dropUnconfiguredViews(int[] configuredIds) {
        for (int i = mViews.size() - 1; i >= 0; i--) {
            int id = mViews.keyAt(i);
            boolean keep = false;
            for (int c : configuredIds) {
                if (c == id) {
                    keep = true;
                    break;
                }
            }
            // The host keeps its own reference until the id is deleted by the settings page.
            if (!keep) mViews.removeAt(i);
        }
    }

    /** Factor from the phone's pixels to the panel's (at most 1). */
    static float deviceToPanelScale(Context context) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        if (dpi <= EINK_DENSITY_DPI) return 1f;
        return EINK_DENSITY_DPI / (float) dpi;
    }

    private static final int COLLECTION_NONE = 0;
    private static final int COLLECTION_READY = 1;
    private static final int COLLECTION_LOADING = 2;

    /** Whether the tree holds collection views, and whether any still waits for its adapter. */
    private static int collectionState(View v) {
        if (v instanceof AdapterView) {
            Adapter a = ((AdapterView<?>) v).getAdapter();
            if (a == null) return COLLECTION_LOADING; // remote adapter not connected yet
            if (a instanceof RemoteViewsAdapter && !((RemoteViewsAdapter) a).isDataReady()) {
                return COLLECTION_LOADING;
            }
            return COLLECTION_READY;
        }
        int state = COLLECTION_NONE;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                state = Math.max(state, collectionState(g.getChildAt(i)));
            }
        }
        return state;
    }

    private static Method sAnalogClockTimeChanged;

    /**
     * Clock views update themselves only while attached to a window, which these
     * views never are: set them to the current time before each draw.
     */
    @SuppressWarnings("deprecation") // AnalogClock is still used by widgets
    private static void refreshClocks(View v) {
        if (v instanceof TextClock) {
            ((TextClock) v).refreshTime();
        } else if (v instanceof android.widget.AnalogClock) {
            try {
                if (sAnalogClockTimeChanged == null) {
                    Method m = android.widget.AnalogClock.class.getDeclaredMethod("onTimeChanged");
                    m.setAccessible(true);
                    sAnalogClockTimeChanged = m;
                }
                sAnalogClockTimeChanged.invoke(v);
                v.invalidate();
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "Cannot set the time of an analog clock widget", e);
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) refreshClocks(g.getChildAt(i));
        }
    }

    /** Any thread; reads only volatile fields. */
    void dump(PrintWriter pw) {
        pw.println("  widget host: id " + EpdSettings.WIDGET_HOST_ID + ", listening: "
                + mListening + (mReceiverRegistered && !mListening ? " (waiting for unlock)" : "")
                + ", last render: " + mLastRender);
        if (mLastError != null) pw.println("  widget host last error: " + mLastError);
    }
}
