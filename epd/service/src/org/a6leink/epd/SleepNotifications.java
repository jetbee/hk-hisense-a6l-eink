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
import android.app.Notification;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses the notifications of the sleep screen (spec 03 section 4). The
 * E-ink can be read by anyone while the phone sleeps, so the choice follows
 * the lock screen settings of the current user. Handler thread of the service.
 */
final class SleepNotifications {
    private static final String TAG = "A6lEpdNotifications";

    /** One notification to show, newest first. */
    static final class Item {
        final String key;
        final long postTime;
        final int userId;
        final String packageName;
        /** Shown as app icon, app name and "Contents hidden" only. */
        final boolean hidden;
        final Icon smallIcon;
        final long when;
        final String title;
        final String text;

        Item(String key, long postTime, int userId, String packageName, boolean hidden,
                Icon smallIcon, long when, String title, String text) {
            this.key = key;
            this.postTime = postTime;
            this.userId = userId;
            this.packageName = packageName;
            this.hidden = hidden;
            this.smallIcon = smallIcon;
            this.when = when;
            this.title = title;
            this.text = text;
        }
    }

    /** Keys of the notifications chosen by the last {@link #collect()}. */
    private static volatile Set<String> sLastKeys = Collections.emptySet();

    private final Context mContext;
    private String mLastError;

    SleepNotifications(Context context) {
        mContext = context;
    }

    /** Whether the last collection counted this notification (shown or in "+N more"). */
    static boolean wasShown(String key) {
        return sLastKeys.contains(key);
    }

    /**
     * The notifications the lock screen rules allow, newest first by post time.
     * Empty when the section is off or the listener is not connected.
     */
    List<Item> collect() {
        List<Item> items = new ArrayList<>();
        try {
            collectInto(items);
            mLastError = null;
        } catch (RuntimeException e) {
            // Never stop the clock from being drawn (spec 03 section 6).
            mLastError = e.toString();
            Log.w(TAG, "Cannot read notifications", e);
            items.clear();
        }
        Set<String> keys = new HashSet<>();
        for (Item i : items) keys.add(i.key);
        sLastKeys = keys;
        return items;
    }

    private void collectInto(List<Item> items) {
        final ContentResolver cr = mContext.getContentResolver();
        if (Settings.System.getIntForUser(cr, EpdSettings.SLEEP_NOTIFICATIONS, 1,
                UserHandle.USER_CURRENT) == 0) {
            return;
        }
        if (Settings.System.getIntForUser(cr, EpdSettings.SLEEP_NOTIFICATIONS_MAX,
                EpdSettings.NOTIFICATIONS_MAX_FIT, UserHandle.USER_CURRENT) == 0) {
            return;
        }
        SleepNotificationListener listener = SleepNotificationListener.connected();
        if (listener == null) return;

        final int user = ActivityManager.getCurrentUser();
        // Settings > Notifications > "Notifications on lock screen".
        if (Settings.Secure.getIntForUser(cr, Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1,
                user) == 0 || adminDisables(user,
                DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS)) {
            return;
        }
        final boolean allowPrivate = Settings.Secure.getIntForUser(cr,
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, user) != 0
                && !adminDisables(user, DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS);

        StatusBarNotification[] active = listener.getActiveNotifications();
        RankingMap rankings = listener.getCurrentRanking();
        if (active == null || rankings == null) return;

        final String self = mContext.getPackageName();
        final Ranking ranking = new Ranking();
        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            final Notification n = sbn.getNotification();
            final int sbnUser = sbn.getUser().getIdentifier();
            // Only the current user (work profiles and other users excluded).
            if (sbnUser != user && sbnUser != UserHandle.USER_ALL) continue;
            if (self.equals(sbn.getPackageName())) continue;
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
                continue;
            }
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && n.getGroup() != null) continue;
            if (!rankings.getRanking(sbn.getKey(), ranking)) continue;
            // Silent (low) and minimized (min) notifications are skipped.
            if (ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) continue;
            if (ranking.isSuspended()) continue;
            if ((ranking.getSuppressedVisualEffects()
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0) {
                continue;
            }
            // Secret: by the app, or by the user for the channel or the app.
            final int override = ranking.getVisibilityOverride();
            if (n.visibility == Notification.VISIBILITY_SECRET
                    || override == Notification.VISIBILITY_SECRET) {
                continue;
            }
            // Redacted like the lock screen does: the user forces it for the channel or
            // the app, or the notification is private and private content is hidden.
            final boolean redact = override == Notification.VISIBILITY_PRIVATE
                    || (n.visibility == Notification.VISIBILITY_PRIVATE && !allowPrivate);

            Notification shown = n;
            boolean hidden = false;
            if (redact) {
                if (n.publicVersion != null) {
                    shown = n.publicVersion;
                } else {
                    hidden = true;
                }
            }
            String title = null;
            String text = null;
            if (!hidden) {
                title = text(shown.extras, Notification.EXTRA_TITLE_BIG,
                        Notification.EXTRA_TITLE, Notification.EXTRA_CONVERSATION_TITLE);
                text = text(shown.extras, Notification.EXTRA_BIG_TEXT,
                        Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT);
            }
            long when = n.showsTime() && n.when != 0 ? n.when : sbn.getPostTime();
            Icon icon = shown.getSmallIcon() != null ? shown.getSmallIcon() : n.getSmallIcon();
            items.add(new Item(sbn.getKey(), sbn.getPostTime(), sbnUser, sbn.getPackageName(),
                    hidden, icon, when, title, text));
        }
        // Newest first.
        Collections.sort(items, (a, b) -> Long.compare(b.postTime, a.postTime));
    }

    private boolean adminDisables(int user, int feature) {
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        return dpm != null && (dpm.getKeyguardDisabledFeatures(null, user) & feature) != 0;
    }

    /** The first non-empty text among the extras, without styling, or null. */
    private static String text(Bundle extras, String... keys) {
        if (extras == null) return null;
        for (String k : keys) {
            CharSequence cs = extras.getCharSequence(k);
            if (!TextUtils.isEmpty(cs)) return cs.toString().trim();
        }
        return null;
    }

    /** A signature of the list: changes when an item is added, removed or updated. */
    static String signature(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) {
            sb.append(i.key).append('@').append(i.postTime).append(i.hidden ? 'h' : 's')
                    .append('\n');
        }
        return sb.toString();
    }

    /** The app's label as shown to the notification's user. */
    String appName(Item item) {
        PackageManager pm = mContext.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfoAsUser(item.packageName, 0,
                    item.userId == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM : item.userId);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return item.packageName;
        }
    }

    /** The notification's small icon (drawn as a silhouette by its alpha), or null. */
    Drawable smallIcon(Item item) {
        if (item.smallIcon == null) return null;
        try {
            return item.smallIcon.loadDrawable(mContext);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot load the icon of " + item.packageName, e);
            return null;
        }
    }

    /** The app's launcher icon, or null. */
    Drawable appIcon(Item item) {
        try {
            return mContext.getPackageManager().getApplicationIcon(item.packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    String lastError() {
        return mLastError;
    }
}
