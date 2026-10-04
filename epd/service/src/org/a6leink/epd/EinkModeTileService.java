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

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import com.hmct.epd.IEpdManager;

import java.util.Map;

/**
 * Quick Settings tile "E-ink: clear/smooth" (spec 05 section 6.5), the 18.1
 * replacement of the stock SystemUI pop-up toggle (spec 05 section 5.2).
 * Shows the stored choice of the front app (the shade does not change the
 * front app) and toggles it: smooth = no entry (removeAppMode), clear = an
 * entry with {@link EpdSettings#MODE_CLEAR} (addAppMode). The service
 * re-applies the mode at once, and the change is permanent, as in the
 * settings app. Unavailable while the E-ink is not in use, and for the home
 * app and this project's own apps. Talks to the {@code epd} binder, so it
 * also works in the process of a secondary user.
 */
public class EinkModeTileService extends TileService {
    private static final String TAG = "A6lEpdTile";
    private static final String SETTINGS_PACKAGE = "org.a6leink.settings";

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        IEpdManager epd = epd();
        String pkg = togglablePackage(epd);
        if (pkg == null) {
            refresh();
            return;
        }
        try {
            if (isClear(epd, pkg)) {
                epd.removeAppMode(pkg);
            } else {
                epd.addAppMode(pkg, EpdSettings.MODE_CLEAR);
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Cannot change the mode of " + pkg, e);
        }
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        IEpdManager epd = epd();
        String pkg = togglablePackage(epd);
        if (pkg == null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel(getString(R.string.tile_label));
            tile.setSubtitle(getString(epd == null ? R.string.tile_no_service
                    : isEinkInUse(epd) ? R.string.tile_not_here : R.string.tile_not_in_use));
        } else {
            boolean clear = isClear(epd, pkg);
            tile.setState(clear ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(getString(clear
                    ? R.string.tile_label_clear : R.string.tile_label_smooth));
            tile.setSubtitle(appLabel(pkg));
        }
        tile.updateTile();
    }

    private static IEpdManager epd() {
        IBinder binder = ServiceManager.getService(EpdSettings.SERVICE_NAME);
        return binder != null ? IEpdManager.Stub.asInterface(binder) : null;
    }

    private static boolean isEinkInUse(IEpdManager epd) {
        try {
            return EpdSettings.isEinkType(epd.getDisplayType());
        } catch (RemoteException | RuntimeException e) {
            return false;
        }
    }

    /** The front package if the tile can toggle it now, else null. */
    private String togglablePackage(IEpdManager epd) {
        if (epd == null || !isEinkInUse(epd)) return null;
        String pkg;
        try {
            pkg = epd.getTopPackage();
        } catch (RemoteException | RuntimeException e) {
            return null;
        }
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())
                || pkg.equals(SETTINGS_PACKAGE) || AppRules.STOCK_HOME_PACKAGES.contains(pkg)
                || pkg.equals(homePackage())) {
            return null;
        }
        return pkg;
    }

    private static boolean isClear(IEpdManager epd, String pkg) {
        try {
            Map<?, ?> modes = epd.getAllAppMode();
            Object mode = modes != null ? modes.get(pkg) : null;
            return mode instanceof Integer && (Integer) mode != EpdSettings.MODE_SMOOTH;
        } catch (RemoteException | RuntimeException e) {
            return false;
        }
    }

    private String homePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home,
                PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
    }

    private CharSequence appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.loadLabel(pm);
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
