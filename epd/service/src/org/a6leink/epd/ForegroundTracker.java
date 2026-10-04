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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.function.Consumer;

/**
 * Observes the front app and activity (spec 05 section 6.1). On 18.1 no app
 * process reports its resumes, so the service registers a task stack
 * listener and, after each burst of callbacks, asks the activity task
 * manager for the focused root task's top activity (package and class; the
 * focused side in split screen; never a picture-in-picture task).
 *
 * <p>Robustness: callbacks are coalesced ({@link #COALESCE_MS}) and followed
 * by two late re-queries, because some transitions (back to the launcher,
 * recents, an activity started from a notification) finish after the last
 * callback. While {@link #setPolling} is on (the E-ink is in use and the
 * screen is on) the front is also polled every {@link #POLL_MS} as a safety
 * net. A failed listener registration is retried. Everything runs on the
 * service handler; the sink is called on it after every query, also when
 * nothing changed (the caller detects changes).
 */
final class ForegroundTracker extends TaskStackListener {
    private static final String TAG = "A6lEpdForeground";

    static final long COALESCE_MS = 60;
    private static final long[] FOLLOW_UP_MS = { 350, 1200 };
    private static final long POLL_MS = 2000;
    private static final long RETRY_MS = 2000;

    /** One observation of the front. */
    static final class Front {
        final String pkg;
        final String cls;
        final int userId;

        Front(String pkg, String cls, int userId) {
            this.pkg = pkg;
            this.cls = cls;
            this.userId = userId;
        }

        boolean samePackage(Front other) {
            return other != null && pkg.equals(other.pkg) && userId == other.userId;
        }

        boolean sameActivity(Front other) {
            return samePackage(other) && cls.equals(other.cls);
        }

        @Override
        public String toString() {
            return pkg + "/" + cls + " u" + userId;
        }
    }

    private final Handler mHandler;
    private final Consumer<Front> mSink;
    private final Runnable mQuery = this::query;
    private final Runnable[] mFollowUps = new Runnable[FOLLOW_UP_MS.length];
    private final Runnable mPoll = this::poll;
    private final Runnable mRegister = this::register;

    // handler
    private boolean mRegistered;
    private boolean mPolling;
    private long mLastQueryUptime;
    private int mQueries;
    private int mFailures;

    ForegroundTracker(Handler handler, Consumer<Front> sink) {
        mHandler = handler;
        mSink = sink;
        for (int i = 0; i < mFollowUps.length; i++) mFollowUps[i] = this::query;
    }

    /** Handler thread. */
    void start() {
        register();
        query();
    }

    private void register() {
        if (mRegistered) return;
        try {
            ActivityTaskManager.getService().registerTaskStackListener(this);
            mRegistered = true;
            Log.i(TAG, "Task stack listener registered");
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Cannot register task stack listener, retrying", e);
            mHandler.postDelayed(mRegister, RETRY_MS);
        }
    }

    /** Requests a query soon, with the late follow-ups. Any thread. */
    void requestQuery() {
        mHandler.removeCallbacks(mQuery);
        mHandler.postDelayed(mQuery, COALESCE_MS);
        for (int i = 0; i < mFollowUps.length; i++) {
            mHandler.removeCallbacks(mFollowUps[i]);
            mHandler.postDelayed(mFollowUps[i], FOLLOW_UP_MS[i]);
        }
    }

    /** Safety-net polling on or off. Handler thread. */
    void setPolling(boolean on) {
        if (on == mPolling) return;
        mPolling = on;
        mHandler.removeCallbacks(mPoll);
        if (on) mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void poll() {
        if (!mPolling) return;
        // Skip if a callback-driven query ran recently.
        if (SystemClock.uptimeMillis() - mLastQueryUptime >= POLL_MS / 2) query();
        mHandler.postDelayed(mPoll, POLL_MS);
    }

    // Binder thread callbacks: only schedule.

    @Override
    public void onTaskStackChanged() {
        requestQuery();
    }

    @Override
    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
        requestQuery();
    }

    @Override
    public void onTaskFocusChanged(int taskId, boolean focused) {
        requestQuery();
    }

    @Override
    public void onTaskRemoved(int taskId) {
        requestQuery();
    }

    @Override
    public void onTaskDisplayChanged(int taskId, int newDisplayId) {
        requestQuery();
    }

    @Override
    public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
        requestQuery();
    }

    @Override
    public void onActivityUnpinned() {
        requestQuery();
    }

    @Override
    public void onActivityDismissingDockedStack() {
        requestQuery();
    }

    @Override
    public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        requestQuery();
    }

    /** Handler thread. */
    private void query() {
        mHandler.removeCallbacks(mQuery);
        mLastQueryUptime = SystemClock.uptimeMillis();
        mQueries++;
        Front front = null;
        try {
            front = queryFocused();
            if (front == null) front = queryTopTask();
        } catch (RemoteException | RuntimeException e) {
            mFailures++;
            Log.w(TAG, "Cannot query the front task", e);
        }
        if (front != null) mSink.accept(front);
    }

    /** Top activity of the focused root task, unless it is picture-in-picture. */
    private static Front queryFocused() throws RemoteException {
        ActivityManager.StackInfo info = ActivityTaskManager.getService().getFocusedStackInfo();
        if (info == null || info.topActivity == null) return null;
        if (info.configuration.windowConfiguration.getWindowingMode()
                == WindowConfiguration.WINDOWING_MODE_PINNED) {
            return null;
        }
        return from(info.topActivity, info.userId);
    }

    /** Fallback: the topmost task that is not picture-in-picture. */
    private static Front queryTopTask() throws RemoteException {
        List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getService().getTasks(3);
        if (tasks == null) return null;
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (task.topActivity == null) continue;
            if (task.configuration.windowConfiguration.getWindowingMode()
                    == WindowConfiguration.WINDOWING_MODE_PINNED) {
                continue;
            }
            return from(task.topActivity, task.userId);
        }
        return null;
    }

    private static Front from(ComponentName top, int userId) {
        return new Front(top.getPackageName(), top.getClassName(), userId);
    }

    /** Handler thread. */
    String dumpState() {
        return "listener=" + mRegistered + " polling=" + mPolling + " queries=" + mQueries
                + " failures=" + mFailures;
    }
}
