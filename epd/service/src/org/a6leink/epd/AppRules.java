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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fixed per-app rules and the matching of per-app entries (spec 05 sections
 * 2.3 and 6.2). Pure functions; the caller holds the state.
 */
final class AppRules {
    private AppRules() {}

    /**
     * Keep-mode activities (spec 05 sections 2.3 and 6.2 step 2): dialogs shown
     * over the app the user is in. Resuming one changes nothing, and it is not
     * recorded as the new front app. Names are the 18.1 (AOSP) ones.
     */
    private static final Set<String> KEEP_MODE_ACTIVITIES = new HashSet<>(Arrays.asList(
            // Runtime permission dialog (package com.android.permissioncontroller).
            "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity",
            // Share sheet (package android).
            "com.android.internal.app.ChooserActivity",
            // "Open with" dialog (package android); own addition, same kind of dialog.
            "com.android.internal.app.ResolverActivity"));

    /**
     * Built-in video activities (spec 05 sections 2.3 and 4.3): smooth while
     * on top, even if their package has an entry. Stock names are not shipped
     * (spec 05 section 6.3); these are the video activities of AOSP Gallery2,
     * which LineageOS ships.
     */
    private static final Set<String> SMOOTH_ACTIVITIES = new HashSet<>(Arrays.asList(
            "com.android.gallery3d.app.MovieActivity",
            "com.android.gallery3d.app.TrimVideo"));

    /** Stock home apps that are always treated as home (spec 05 section 2.3). */
    static final Set<String> STOCK_HOME_PACKAGES = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(EpdSettings.EINK_LAUNCHER, EpdSettings.VISION_HOME)));

    static boolean isKeepModeActivity(String cls) {
        return cls != null && KEEP_MODE_ACTIVITIES.contains(cls);
    }

    static boolean isSmoothActivity(String cls) {
        return cls != null && SMOOTH_ACTIVITIES.contains(cls);
    }

    static Set<String> keepModeActivities() {
        return Collections.unmodifiableSet(KEEP_MODE_ACTIVITIES);
    }

    static Set<String> smoothActivities() {
        return Collections.unmodifiableSet(SMOOTH_ACTIVITIES);
    }

    /** Result of {@link #match}: the entry's key, its mode and how it matched. */
    static final class Match {
        static final String EXACT = "per-app entry";
        static final String ACTIVITY = "activity entry";
        static final String SUBSTRING = "substring entry";

        final String key;
        final int mode;
        final String how;

        Match(String key, int mode, String how) {
            this.key = key;
            this.mode = mode;
            this.how = how;
        }
    }

    /**
     * Matching of spec 05 section 6.2: an entry equal to the activity class
     * name (more specific, so it overrides the package's entry); else an entry
     * equal to the package; else, for
     * stock-style keys, the longest entry that contains the package or is
     * contained in it (ties broken by the key's natural order, so the result
     * never depends on map iteration order). Null if nothing matches.
     */
    static Match match(Map<String, Integer> modes, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (cls != null && !cls.isEmpty()) {
            Integer activity = modes.get(cls);
            if (activity != null) return new Match(cls, activity, Match.ACTIVITY);
        }
        Integer exact = modes.get(pkg);
        if (exact != null) return new Match(pkg, exact, Match.EXACT);
        String best = null;
        for (String key : modes.keySet()) {
            if (key == null || key.isEmpty()) continue;
            if (!key.contains(pkg) && !pkg.contains(key)) continue;
            if (best == null || key.length() > best.length()
                    || (key.length() == best.length() && key.compareTo(best) < 0)) {
                best = key;
            }
        }
        return best != null ? new Match(best, modes.get(best), Match.SUBSTRING) : null;
    }

    /** True if an entry keyed by this activity class exists, or a fixed activity rule. */
    static boolean hasActivityRule(Map<String, Integer> modes, String cls) {
        return cls != null && !cls.isEmpty()
                && (modes.containsKey(cls) || SMOOTH_ACTIVITIES.contains(cls));
    }
}
