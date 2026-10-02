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

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Notification listener of the sleep screen (spec 03 section 4). The service
 * grants itself listener access at start; this class only tells the status
 * screen that something changed. What is shown is decided by
 * {@link SleepNotifications} from the listener's current notifications.
 */
public class SleepNotificationListener extends NotificationListenerService {
    private static final String TAG = "A6lEpdNotifications";

    private static volatile SleepNotificationListener sConnected;

    static ComponentName component(Context context) {
        return new ComponentName(context, SleepNotificationListener.class);
    }

    /** The connected listener, or null. */
    static SleepNotificationListener connected() {
        return sConnected;
    }

    @Override
    public void onListenerConnected() {
        sConnected = this;
        Log.i(TAG, "Notification listener connected");
        changed();
    }

    @Override
    public void onListenerDisconnected() {
        if (sConnected == this) sConnected = null;
        Log.i(TAG, "Notification listener disconnected");
        changed();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        // Progress updates of ongoing notifications are frequent and never shown.
        if (sbn != null && isNeverShown(sbn) && !SleepNotifications.wasShown(sbn.getKey())) {
            return;
        }
        changed();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            int reason) {
        if (sbn != null && !SleepNotifications.wasShown(sbn.getKey())) return;
        changed();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        changed();
    }

    private boolean isNeverShown(StatusBarNotification sbn) {
        int flags = sbn.getNotification().flags;
        return (flags & (Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_FOREGROUND_SERVICE)) != 0
                || getPackageName().equals(sbn.getPackageName());
    }

    private static void changed() {
        EpdService service = EpdApplication.service();
        if (service != null) service.onNotificationsChanged();
    }
}
