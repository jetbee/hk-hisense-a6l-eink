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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.function.Consumer;

/**
 * Observes the foreground package (spec 02 section 3.4): on 18.1 nobody calls
 * {@code notifyResumedPackage}, so the service asks the activity task manager
 * itself. Besides tasks moving to the front, every task stack change is
 * followed by a query of the top task, which also catches a new top activity
 * inside the same task.
 */
final class ForegroundTracker extends TaskStackListener {
    private static final String TAG = "A6lEpdForeground";

    private final Handler mHandler;
    private final Consumer<String> mSink;
    private final Runnable mQuery = this::queryTopPackage;

    ForegroundTracker(Handler handler, Consumer<String> sink) {
        mHandler = handler;
        mSink = sink;
    }

    void start() {
        try {
            ActivityTaskManager.getService().registerTaskStackListener(this);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Cannot register task stack listener", e);
        }
        mHandler.post(mQuery);
    }

    @Override
    public void onTaskStackChanged() {
        mHandler.removeCallbacks(mQuery);
        mHandler.post(mQuery);
    }

    @Override
    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
        final ComponentName top = taskInfo != null ? taskInfo.topActivity : null;
        if (top != null) {
            final String pkg = top.getPackageName();
            mHandler.post(() -> mSink.accept(pkg));
        }
    }

    private void queryTopPackage() {
        try {
            List<ActivityManager.RunningTaskInfo> tasks =
                    ActivityTaskManager.getService().getTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                mSink.accept(tasks.get(0).topActivity.getPackageName());
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Cannot query the top task", e);
        }
    }
}
