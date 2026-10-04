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

package com.hmct.epd;

import android.graphics.Bitmap;
import android.widget.RemoteViews;
import com.hmct.epd.IDirectionDetect;

/**
 * Binder interface of the E-ink system service published as "epd".
 *
 * Declaration order fixes the transaction codes (FIRST_CALL_TRANSACTION + n)
 * and must not be changed; the number in front of each method is its code.
 * Names (including misspellings) are compatibility facts; see spec 01,
 * section 3.1.
 *
 * {@hide}
 */
interface IEpdManager {
    /*  1 */ void addViewToPresentationDisplay(in RemoteViews views, int priority, int epdMode,
                     int minShowTime, boolean withBar);
    /*  2 */ void addBitmapToPresentationDisplayWithString(in Bitmap bitmap, int priority,
                     int epdMode, int minShowTime, boolean withBar, String extra);
    /*  3 */ void addBitmapToPresentationDisplay(in Bitmap bitmap, int priority, int epdMode,
                     int minShowTime, boolean withBar);
    /*  4 */ void updateShutdownWallPaper(in Bitmap bitmap);
    /*  5 */ void removeViewOnPresentationDisplay(int priority);
    /*  6 */ int getDualScreenTaskState();
    /*  7 */ void setDualScreenEnabled(boolean enabled, boolean flag);
    /*  8 */ void finishBoot();
    /*  9 */ void lockEpdMode(int type, boolean on);
    /* 10 */ void begigResumeActivity(String packageName, String className);
    /* 11 */ void notifyResumedPackage(String name);
    /* 12 */ void setDisplayType(int type, boolean isAllowExitFromReadOnly);
    /* 13 */ void setEpdDisplayMode(int mode);
    /* 14 */ void setDisplayPowerMode(int displayId, boolean enabled);
    /* 15 */ void setDisplayEnabled(int id, boolean enabled);
    /* 16 */ boolean getDisplayEnabled(int id);
    /* 17 */ void setEinkThemeMode(int mode);
    /* 18 */ void setEinkKeyguardMode(int mode);
    /* 19 */ void setGestureUnlock(int enabled);
    /* 20 */ void updateEpdMode();
    /* 21 */ int getEpdMode();
    /* 22 */ void updateExteralPaper();
    /* 23 */ int getDisplayType();
    /* 24 */ void requestGoToSleep();
    /* 25 */ int getLastScreenOnType();
    /* 26 */ void forceClear();
    /* 27 */ boolean isExternalType();
    /* 28 */ void lockWakeupSource();
    /* 29 */ void unLockWakeupSource();
    /* 30 */ void setWakeupSourceType(int sourceType, int display, boolean goHomeEnabled);
    /* 31 */ int getWakeupSourceType();
    /* 32 */ void startWakingUp();
    /* 33 */ void onScreenChanged(boolean isScreenOn);
    /* 34 */ String getTopPackage();
    /* 35 */ void registerDirectionChangeListener(IDirectionDetect callback);
    /* 36 */ void unregisterDirectionChangeListener(IDirectionDetect callback);
    /* 37 */ void setExternalTpEnabled(boolean enabled);
    /* 38 */ void removeAppMode(String pkg);
    /* 39 */ void addAppMode(String pkg, int mode);
    /* 40 */ Map getAllAppMode();
    /* 41 */ void addPreViewPage(int type, in RemoteViews views);
    /* 42 */ void adjustEinkEffect(int type, int value);
    /* 43 */ int getEinkEffect(int type);
}
