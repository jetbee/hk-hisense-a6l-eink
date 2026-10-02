<!--
Copyright (C) 2026 The a6l-eink-oss authors, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# `com.hmct.epd`: the E-ink client library

> **概要（日本語）** `com.hmct.epd` は Hisense A6L の背面 E-ink（「epd」）サービスを
> 呼び出すためのクライアントライブラリです。ブートクラスパス上の jar として提供され、
> アプリは `context.getSystemService("epd")` で `EpdManager` を取得します。
> 本書では公開定数、全公開メソッドの動作、2 つの AIDL インターフェース
> （ディスクリプタとトランザクションコード）、およびサービスが送受信する
> ブロードキャスト・設定キー・システムプロパティを説明します。名前・値・コードは
> 既存の純正アプリとの互換性のため純正と同一に保たれています。

This document describes the client side of the E-ink stack of the Hisense A6L
(front 1080x2340 LCD = display 0, rear 720x1440 E-ink = display 1) as
implemented in `epd/framework/` of this repository. The service side is in
[epd-service.md](epd-service.md).

Sources: clean-room spec 01 (`01-com.hmct.epd.md`), spec 02
(`02-epd-service.md`) for service behaviour, and the code in this repository.

## 1. What it is for

The library gives apps and the framework one object, `com.hmct.epd.EpdManager`,
to talk to the E-ink service published under the binder name `epd`. Through
it a caller can:

- switch the active panel (LCD only, E-ink mirror, E-ink only) and read it;
- set or query the E-ink refresh mode, globally or per app;
- tell the service about screen and wake-up events;
- adjust E-ink tuning values (contrast, black and white thresholds);
- post content to the E-ink while it is not in use (accepted for
  compatibility; see 5.1).

The package name, class names, method names and signatures, constant values,
AIDL descriptors and transaction codes are **kept identical for
compatibility with stock apps** (the Hisense E-ink launcher, E-ink settings,
hall-sensor service and others link against them). This includes the stock
misspellings `DetectCompleteLisenter`, `begigResumeActivity`,
`updateExteralPaper`, `ACTION_READING_TOMEOUT` and `WAKEUP_SORUCE_FROM_USER`,
which are part of the ABI.

## 2. Packaging and how apps obtain it

| Item | Value |
|---|---|
| Module | `com.hmct.epd` (`java_library`, `epd/framework/Android.bp`), on the boot class path via `PRODUCT_BOOT_JARS` |
| Java package | `com.hmct.epd` |
| Public types | `EpdManager`, `EpdManager.DetectCompleteLisenter` (interface), `EpdManager.DirectionDetect` (inner class), `IEpdManager`, `IDirectionDetect` (AIDL, with `Stub` / `Stub.Proxy`) |
| Binder name | `epd` |
| `getSystemService` name | `"epd"`, returns an `EpdManager` (cached per `Context`) |
| Gate property | `ro.hmct.epd.support` = `1` (set by `epd/epd.mk`) |

Because the jar is on the boot class path, apps must **not** bundle their own
copy; compile against it with `libs: ["com.hmct.epd"]` (platform builds) or
a stub jar.

Obtaining the manager:

```java
EpdManager epd = (EpdManager) context.getSystemService("epd");
if (epd != null) {
    int type = epd.getDisplayType();
}
```

- Use the literal `"epd"`. Stock firmware also had a constant
  `Context.EPD_SERVICE` with that value; this project does not add a field to
  `Context` (see [patches.md](patches.md)). Code compiled against the stock
  constant still works because javac inlines it.
- The result may be `null`: the service runs in an app process that starts
  during boot, and the framework's per-context cache keeps a `null` for the
  life of a context that asked before the service was registered. Callers
  that may run very early should fall back to
  `IEpdManager.Stub.asInterface(ServiceManager.checkService("epd"))`.
- Public constructor `EpdManager(IEpdManager service, Context context)`: keeps
  the proxy; the context is not used; no binder call is made.

## 3. Common behaviour of all methods

- Every instance method makes exactly one synchronous binder call on the
  calling thread and nothing else: no caching, threads, settings or property
  access on the client side.
- A `RemoteException` is swallowed and the method returns its failure value
  (void methods just return). If the manager was built with a `null` proxy,
  the failure value is returned without a call (an addition of this
  implementation; compatible).
- No method requires a permission, on either side (kept as on stock; stock
  apps depend on it).
- Logging uses the tag `EpdManager` and is not part of the contract.

## 4. Public constants (50)

All are `public static final`. Primitive and `String` values are compile-time
constants and are inlined into callers, so they never change.

### 4.1 Display types and flags

| Name | Value | Meaning |
|---|---|---|
| `DISPLAY_MODE_SCREEN_OFF` | `-1` | both screens off |
| `DISPLAY_MODE_PRIMARY_ONLY` | `0` | LCD only |
| `DISPLAY_MODE_PRIMARY_WITH_PRESENTATION` | `1` | LCD plus a presentation on the E-ink |
| `DISPLAY_MODE_WITH_PRESENTATION` | `1` | bit flag "with presentation" (bit 0) |
| `DISPLAY_MODE_EXTERNAL_MIRROR` | `2` | E-ink active, mirroring the main UI |
| `DISPLAY_MODE_EXTERNAL_EPAPER` | `4` | E-ink only ("e-paper") |
| `DISPLAY_MODE_SWITCHPOLICY_ENABLE` | `256` | flag a service may OR in when forwarding to the activity manager; never returned to clients |

Display type encoding: `-1` = off. For non-negative values bit 0 is "with
presentation" and the value with bit 0 cleared selects the panel: `0` LCD,
`2` E-ink mirror, `4` E-ink only. On LineageOS 18.1 the service always drops
bit 0 (no dual-screen tasks), so clients see only -1, 0, 2 and 4.

### 4.2 Refresh modes

| Name | Value | Meaning |
|---|---|---|
| `EPD_MODE_MIRROR` | `0` | mirror |
| `EPD_MODE_TYPING` | `1` | typing |
| `EPD_MODE_PICTURE` | `2` | picture (high quality) |
| `EPD_MODE_READING` | `3` | reading |
| `EPD_MODE_FAST` | `6` | fast |
| `EPD_MODE_VIDEO` | `8` | video; the steady everyday mode with the stock HWC, and this service's default on the E-ink |

### 4.3 Broadcasts, settings and properties

| Name | Value |
|---|---|
| `ACTION_DISPLAY_CHANGED` | `"com.hmct.action.displaychanged"` |
| `EXTRA_INT` | `"display_type"` (int extra of `ACTION_DISPLAY_CHANGED`) |
| `ACTION_READING_TOMEOUT` | `"com.hmct.action.reading.timeout"` |
| `DISPLAY_TYPE_PROPERTY` | `"sys.sysctl.display_type"` |
| `DISPLAY_TYPE_SETTINGS` | `"current_display_type"` (`Settings.System`) |

### 4.4 E-ink panel, content slots and tuning

| Name | Value | Meaning |
|---|---|---|
| `EXTERNAL_DISPLAY_WIDTH` | `720` | E-ink width, px |
| `EXTERNAL_DISPLAY_HEIGHT` | `1440` | E-ink height, px |
| `EXTERNAL_DISPLAY_UPDATE_NORMAL` | `0` | update kind |
| `EXTERNAL_DISPLAY_UPDATE_BITMAP` | `1` | update kind |
| `EXTERNAL_THEME_MODE_LIGHT` | `0` | theme mode |
| `EXTERNAL_THEME_MODE_DARK` | `1` | theme mode |
| `PRIORITY_EXTERNAL_VIEWS_KEYGUARD` | `0` | content slot (lowest) |
| `PRIORITY_EXTERNAL_VIEWS_COVER` | `1` | content slot |
| `PRIORITY_EXTERNAL_VIEWS_MUSIC` | `2` | content slot |
| `PRIORITY_EXTERNAL_VIEWS_CALL` | `3` | content slot (highest) |
| `PRIORITY_EXTERNAL_VIEWS_SIZE` | `4` | number of slots |
| `TYPE_EINK_CONTRAST` | `0` | tuning parameter |
| `TYPE_EINK_BLACK` | `1` | tuning parameter |
| `TYPE_EINK_WHITE` | `2` | tuning parameter |
| `TYPE_EPDLOCK_CAMERA` | `0` | refresh-mode lock |
| `TYPE_EPDLOCK_VIDEO` | `1` | refresh-mode lock |
| `TYPE_EPDLOCK_SIZE` | `2` | number of lock types |

### 4.5 Wake-up sources and listener types

| Name | Value |
|---|---|
| `WAKEUP_SOURCE_FROM_SENSOR` | `0` |
| `WAKEUP_SORUCE_FROM_USER` | `1` (misspelling kept) |
| `WAKEUP_SOURCE_FROM_SENSOR_WITH_LIGHT` | `3` |
| `LISTENER_TYPE_LISTENER` | `0` (persistent direction listener) |
| `LISTENER_TYPE_REQUEST` | `1` (one-shot request) |

### 4.6 Other

| Name | Type | Value |
|---|---|---|
| `DEFAULT_GESTURE_UNLOCK` | int | `0` |
| `EINK_HOME` | String | `"com.hmct.einklauncher"` |
| `EINK_HOME_CLASSNAME` | String | `"com.hmct.einklauncher.Launcher"` |
| `EPD_DEBUG` | boolean | `true` |
| `EPD_READING_TIMEOUT_DURATION` | int | `5000` (ms) |
| `EPD_TAG` | String | `"EpdManager"` |
| `MAX_PRIMARY_ANGLE` | int | `110` (degrees) |
| `TIMEOUT_WITHOUT_KEYGUARD` | int | `120001` (ms) |
| `READING_APP_LIST` | `ArrayList<String>` | `com.chaozh.iReader`, `com.chaozh.iReader.siswoo`, `com.duokan.reader`, `com.zhangyue.iReader`, `com.tencent.weread`, `com.jingdong.app.reader` (in this order; mutable) |
| `SHOW_WHEN_UNLOCKED_APP` | `ArrayList<String>` | one empty string (mutable) |

`DEFAULT_GESTURE_UNLOCK`, `EPD_READING_TIMEOUT_DURATION`,
`EXTERNAL_DISPLAY_UPDATE_*`, `MAX_PRIMARY_ANGLE`, `SHOW_WHEN_UNLOCKED_APP`,
`TIMEOUT_WITHOUT_KEYGUARD` and `READING_APP_LIST` have no known use in the
library or the service; they exist only for compatibility.

## 5. Public methods (60)

"Code" is the `IEpdManager` transaction code the method uses (section 6).
"Fail" is the value returned when the binder call fails. "Service" is what
this project's service does (spec 02 behaviour on LineageOS 18.1).

### 5.1 Content on the E-ink while it is not in use

Stock firmware kept four priority slots of images and painted the highest on
the E-ink while the LCD was in use. **This service accepts these calls and
ignores them**; instead it shows its own status screen
([epd-service.md](epd-service.md#9-the-status-screen)).

| Method | Code | Client behaviour |
|---|---|---|
| `addViewOnExternal(RemoteViews v)` | 1 | `addViewToPresentationDisplay(v, 0, 2, 0, false)` |
| `addViewOnExternal(RemoteViews v, int priority)` | 1 | `(v, priority, 2, 0, false)` |
| `addViewOnExternal(RemoteViews v, int priority, int epdMode)` | 1 | `(v, priority, epdMode, 0, false)` |
| `addViewOnExternal(RemoteViews v, int priority, int epdMode, int minShowTime)` | 1 | `(v, priority, epdMode, minShowTime, false)` |
| `addBitmapOnExternal(Bitmap b)` | 2 | `addBitmapToPresentationDisplayWithString(b, 0, 2, 0, false, null)` |
| `addBitmapOnExternal(Bitmap b, int epdMode)` | 2 | `(b, 0, epdMode, 0, false, null)` |
| `addBitmapOnExternal(Bitmap b, int priority, int epdMode, int minShowTime)` | 2 | `(b, priority, epdMode, minShowTime, false, null)` |
| `addBitmapOnExternal(Bitmap b, int priority, int epdMode, int minShowTime, boolean withBar)` | 2 | `(..., withBar, null)` |
| `addBitmapOnExternal(Bitmap b, int priority, int epdMode, int minShowTime, boolean withBar, String extra)` | 2 | `(..., withBar, extra)`; on stock `extra` replaced the centred hint text of the status bar |
| `removeViewOnExternal()` | 5 | `removeViewOnPresentationDisplay(0)` |
| `removeViewOnExternal(int priority)` | 5 | `removeViewOnPresentationDisplay(priority)` |
| `updateExteralPaper()` | 22 | repaint request |
| `updateShutdownWallPaper(Bitmap b)` | 4 | final picture before shutdown (stock: froze the E-ink for the rest of the boot) |
| `addPreViewPage(int page, Bitmap b)` | 4 | despite its name calls `updateShutdownWallPaper(b)`; `page` ignored (kept as on stock) |
| `addPreViewPage(int type, RemoteViews v)` | 41 | `addPreViewPage(type, v)`; ignored by stock too |
| `forceClear()` | 26 | Service: full ghost-clearing refresh of the E-ink (only after "E-ink ready") |

Transaction 3 (`addBitmapToPresentationDisplay`) is never used by the client.

### 5.2 Display type

| Method | Code | Fail | Behaviour |
|---|---|---|---|
| `setDisplayType(int type)` | 12 | - | `setDisplayType(type, false)`. The exact name and signature are called by reflection from a stock Settings screen |
| `setDisplayType(int type, boolean isAllowExitFromReadOnly)` | 12 | - | Requests a panel switch. The boolean is ignored. Service: drops bit 0, maps disabled displays (E-ink disabled and type >= 2 gives 0; LCD disabled and type 0 gives 4), ignores unknown types, then switches if the result differs from the current type. `-1` acts like a screen-off without turning the screen off |
| `goToExternalForce(Integer type)` | 12 | - | `setDisplayType(type.intValue(), false)`; a null argument throws `NullPointerException` in the caller |
| `int getDisplayType()` | 23 | `0` | Current type |
| `boolean isExternalDisplayOn()` | 27 | `false` | `isExternalType()`: type >= 0 and (type with bit 0 cleared) is 2 or 4 |
| `boolean isExternalDisplayAlready() throws RemoteException` | 23 | `false` | true only if `getDisplayType()` is exactly 2 or 4. Never actually throws; the `throws` clause is kept for source compatibility |
| `static boolean isExternalDisplayOn(int type)` | none | - | Pure: true for 2, 3, 4, 5 |
| `static boolean isPrimaryDisplayOn(int type)` | none | - | Pure: true for 0 and 1 |
| `int getLastScreenOnType()` | 25 | `0` | Last non-off type set |
| `setDisplayEnabled(int id, boolean enabled)` | 15 | - | Records whether display 0 or 1 may be used (memory only); other ids ignored. Does not switch by itself |
| `boolean getDisplayEnabled(int id)` | 16 | `true` | Recorded flag; `true` for ids other than 0 and 1 |
| `setDisplayPowerMode(int displayId, boolean enabled)` | 14 | - | No-op in the service (as on stock) |

### 5.3 Screen and wake-up source

| Method | Code | Fail | Behaviour |
|---|---|---|---|
| `onScreenChanged(boolean isScreenOn)` | 33 | - | Same as the service's own handling of `SCREEN_ON` / `SCREEN_OFF`; safe to call redundantly |
| `requestGoToSleep()` | 24 | - | Service: puts the device to sleep (`PowerManager.goToSleep`) |
| `startWakingUp()` | 32 | - | Service: no-op; the wake target is decided at the screen-on broadcast |
| `setWakeupSourceType(int source, int display)` | 30 | - | `setWakeupSourceType(source, display, source == 0)` |
| `setWakeupSourceType(int source, int display, boolean goHomeEnabled)` | 30 | - | Ignored while locked. Records `source`; when `source` is 1 and `display` is 0 or 4, `display` becomes the screen to wake to at the next screen-on. The boolean is ignored |
| `int getWakeupSourceType()` | 31 | `-1` | Recorded source (initially 1, `-1` after each screen-on) |
| `lockWakeupSource()` | 28 | - | Makes `setWakeupSourceType` ignored until unlocked or the next screen change |
| `unLockWakeupSource()` | 29 | - | Clears the lock |

### 5.4 Refresh mode

| Method | Code | Fail | Behaviour |
|---|---|---|---|
| `int getEpdMode()` | 21 | `6` | Current mode (service starts at 0) |
| `setEpdDisplayMode(int mode)` | 13 | - | Sets the mode; values outside 0..8 ignored; same value is a no-op. Applied to the panel only after "E-ink ready" |
| `updateEpdMode()` | 20 | - | While type 2 or 4: camera or video lock gives 6; a per-app entry matching the foreground package gives its mode; otherwise 8 |
| `lockEpdMode(int type, boolean on)` | 9 | - | Type 0 camera, 1 video, others ignored; re-evaluates. All locks are cleared when the type becomes -1. Logs an error (text contains "notifyCameraOn") on binder failure |
| `notifyCameraOn(boolean on)` | 9 | - | `lockEpdMode(0, on)` |
| `addAppMode(String pkg, int mode)` | 39 | - | Stores a per-app mode. Refused for a null package, `com.hmct.einklauncher`, or a mode outside 1..7. Persisted; re-evaluates if `pkg` is the foreground package |
| `removeAppMode(String pkg)` | 38 | - | Removes and persists |
| `Map getAllAppMode()` | 40 | `null` | Copy of package -> Integer mode (raw `java.util.Map`) |

A per-app entry "matches" the foreground package when either string contains
the other (substring match, kept as on stock).

### 5.5 Foreground app

| Method | Code | Fail | Behaviour |
|---|---|---|---|
| `begigResumeActivity(String pkg, String claseName)` | 10 | - | Records `pkg` as foreground package (the service also tracks it itself) |
| `notifyResumedPackage(String name)` | 11 | - | Same as above |
| `String getTopPackage()` | 34 | `null` | Recorded foreground package (empty string before the first) |
| `boolean isTopActivity(String pkg)` | 34 | `false` | `false` without a call when `pkg` is null; otherwise `getTopPackage().equals(pkg)` |

### 5.6 Theme, keyguard, gestures, touch

| Method | Code | Behaviour |
|---|---|---|
| `setEinkThemeMode(int mode)` | 17 | Stored in memory, shown in `dumpsys epd` |
| `doPageDown()` | 17 | Despite its name calls `setEinkThemeMode(2)` (kept as on stock); no paging |
| `setEinkKeyguardMode(int mode)` | 18 | Stored in memory, shown in dumpsys |
| `setGestureUnlock(int enabled)` | 19 | Stored in memory only; rear-touch gestures are not implemented |
| `setExternalTpEnabled(boolean enabled)` | 37 | Rear (E-ink side) touch panel on/off |

### 5.7 E-ink tuning

| Method | Code | Fail | Behaviour |
|---|---|---|---|
| `int getEinkEffect(int type)` | 43 | `-1` | Type 0/1/2: value of `persist.sys.epd.contrast` / `.black` / `.white` (defaults 0 / 30 / 20); other types 0 |
| `adjustEinkEffect(int type, int value)` | 42 | - | Values outside 0..100 ignored; stores the property and writes the panel node (after "E-ink ready") |

### 5.8 Boot and dual screen

| Method | Code | Fail | Behaviour |
|---|---|---|---|
| `finishBoot()` | 8 | - | Sets `sys.einklauncher.ready=1`; if `Settings.System accelerometer_rotation_restore` is non-zero, sets `accelerometer_rotation=1` and the restore flag to 0. Called by the stock E-ink launcher |
| `int getDualScreenTaskState()` | 6 | `-1` | Always -1 (no dual-screen tasks) |
| `setDualScreenEnabled(boolean enabled, boolean flag)` | 7 | - | No-op |

### 5.9 Direction listener

| Method | Code | Behaviour |
|---|---|---|
| `registerDirectionChangeListener(DetectCompleteLisenter l)` | 35 | The manager holds at most one callback object. If none exists it creates a `DirectionDetect` (type `LISTENER_TYPE_LISTENER`, request time = `SystemClock.currentThreadTimeMillis()`) and registers it; then `l` replaces any stored listener. If the binder call fails, nothing is stored |
| `unregisterDirectionChangeListener(DetectCompleteLisenter l)` | 36 | Argument ignored. If a callback exists, unregisters it and forgets callback and listener (kept on binder failure) |

Interface `EpdManager.DetectCompleteLisenter`: one method
`void onDetectComplete(int mode, long requestTime)`.

Class `EpdManager.DirectionDetect` (public, non-static, extends
`IDirectionDetect.Stub`): constructor `DirectionDetect(int type, long time)`;
`onDirectionChanged(mode, requestTime)` forwards to the stored listener;
`getListenerType()` and `getRequestTime()` return the constructor values.
Listener state is guarded by a lock in this implementation.

The service keeps registered callbacks but never calls them (stock never did
either), so listeners currently receive nothing.

## 6. AIDL interfaces

Standard AIDL; no method is `oneway`. Codes are
`IBinder.FIRST_CALL_TRANSACTION + n` (FIRST = 1). Parcelables use the
standard nullable encoding, booleans are ints, the `Map` is written with
`Parcel.writeMap` and read with `Parcel.readHashMap`. The declaration order
in the `.aidl` files fixes the codes: never reorder or insert.

### 6.1 `com.hmct.epd.IEpdManager`

Descriptor: `com.hmct.epd.IEpdManager`. Implemented by the service.

| Code | Method | Parameters | Returns |
|---|---|---|---|
| 1 | `addViewToPresentationDisplay` | `in RemoteViews`, int priority, int epdMode, int minShowTime, boolean withBar | void |
| 2 | `addBitmapToPresentationDisplayWithString` | `in Bitmap`, int, int, int, boolean, String extra | void |
| 3 | `addBitmapToPresentationDisplay` | `in Bitmap`, int, int, int, boolean | void |
| 4 | `updateShutdownWallPaper` | `in Bitmap` | void |
| 5 | `removeViewOnPresentationDisplay` | int priority | void |
| 6 | `getDualScreenTaskState` | - | int |
| 7 | `setDualScreenEnabled` | boolean, boolean | void |
| 8 | `finishBoot` | - | void |
| 9 | `lockEpdMode` | int type, boolean on | void |
| 10 | `begigResumeActivity` | String, String | void |
| 11 | `notifyResumedPackage` | String | void |
| 12 | `setDisplayType` | int, boolean | void |
| 13 | `setEpdDisplayMode` | int | void |
| 14 | `setDisplayPowerMode` | int, boolean | void |
| 15 | `setDisplayEnabled` | int, boolean | void |
| 16 | `getDisplayEnabled` | int | boolean |
| 17 | `setEinkThemeMode` | int | void |
| 18 | `setEinkKeyguardMode` | int | void |
| 19 | `setGestureUnlock` | int | void |
| 20 | `updateEpdMode` | - | void |
| 21 | `getEpdMode` | - | int |
| 22 | `updateExteralPaper` | - | void |
| 23 | `getDisplayType` | - | int |
| 24 | `requestGoToSleep` | - | void |
| 25 | `getLastScreenOnType` | - | int |
| 26 | `forceClear` | - | void |
| 27 | `isExternalType` | - | boolean |
| 28 | `lockWakeupSource` | - | void |
| 29 | `unLockWakeupSource` | - | void |
| 30 | `setWakeupSourceType` | int, int, boolean | void |
| 31 | `getWakeupSourceType` | - | int |
| 32 | `startWakingUp` | - | void |
| 33 | `onScreenChanged` | boolean | void |
| 34 | `getTopPackage` | - | String |
| 35 | `registerDirectionChangeListener` | `IDirectionDetect` | void |
| 36 | `unregisterDirectionChangeListener` | `IDirectionDetect` | void |
| 37 | `setExternalTpEnabled` | boolean | void |
| 38 | `removeAppMode` | String | void |
| 39 | `addAppMode` | String, int | void |
| 40 | `getAllAppMode` | - | `java.util.Map` |
| 41 | `addPreViewPage` | int, `in RemoteViews` | void |
| 42 | `adjustEinkEffect` | int, int | void |
| 43 | `getEinkEffect` | int | int |

### 6.2 `com.hmct.epd.IDirectionDetect`

Descriptor: `com.hmct.epd.IDirectionDetect`. Implemented by the client.

| Code | Method | Parameters | Returns |
|---|---|---|---|
| 1 | `onDirectionChanged` | int mode, long requestTime | void |
| 2 | `getListenerType` | - | int |
| 3 | `getRequestTime` | - | long |

Calling from the shell for a quick test (codes from 6.1):

```sh
adb shell service call epd 23            # getDisplayType
adb shell service call epd 12 i32 4 i32 0  # setDisplayType(4, false)
```

## 7. Broadcasts, settings and properties

The library reads and writes none of these; it only defines constants. The
service produces them as follows.

### 7.1 Broadcasts

| Action | Direction | Details |
|---|---|---|
| `com.hmct.action.displaychanged` | sent by the service after every effective display-type change (and once at start) | to `UserHandle.ALL`, no permission, int extra `display_type`. Declared protected (only the service may send it). Sent with `FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT`, so only registered receivers get it, not manifest receivers |
| `com.hmct.intent.EINK_KEY_PRESSED` | sent from the E-ink key handler on each E-ink key press | to the current user, no extras (kept for stock apps, e.g. an alarm dismiss) |
| `com.hmct.action.reading.timeout` | defined only | not handled by this service |

### 7.2 Settings

| Key | Table | Meaning |
|---|---|---|
| `current_display_type` | System, current user | current display type, written by the service |
| `fp_unlock_screen` | System, per user | per-finger unlock screen, JSON (see [epd-service.md](epd-service.md#7-per-finger-unlock-screen)) |
| `accelerometer_rotation_restore` | System, current user | restored by `finishBoot` |

The service's own keys are listed in [epd-service.md](epd-service.md#10-persistence-and-settings).

### 7.3 System properties

| Property | Meaning |
|---|---|
| `ro.hmct.epd.support` | `1` on devices with the E-ink stack (readable by every app) |
| `sys.sysctl.display_type` | current display type, decimal. Label `system_prop`: readable by system components, not by untrusted apps on 18.1. Apps should read `current_display_type` or listen to the broadcast |
| `persist.sys.epd.contrast`, `persist.sys.epd.black`, `persist.sys.epd.white` | tuning values 0..100, defaults 0 / 30 / 20 (same names as stock, so values survive) |
| `sys.einklauncher.ready` | `1` after `finishBoot` |

## 8. Example: follow the active panel

```java
IntentFilter f = new IntentFilter(EpdManager.ACTION_DISPLAY_CHANGED);
context.registerReceiver(new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
        int type = i.getIntExtra(EpdManager.EXTRA_INT, EpdManager.DISPLAY_MODE_PRIMARY_ONLY);
        boolean onEink = EpdManager.isExternalDisplayOn(type);
    }
}, f);
```
