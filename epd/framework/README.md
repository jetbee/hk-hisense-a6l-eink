<!--
Copyright (C) 2026 The a6l-eink-oss authors
SPDX-License-Identifier: Apache-2.0
-->

# com.hmct.epd (client library)

Client library for the E-ink ("epd") system service of the Hisense A6L
(LCD front panel = display 0, E-ink rear panel = display 1). It provides
`com.hmct.epd.EpdManager`, which apps and the framework obtain with
`context.getSystemService("epd")` when `ro.hmct.epd.support` is `"1"`, and
the two binder interfaces it speaks, `com.hmct.epd.IEpdManager` and
`com.hmct.epd.IDirectionDetect`.

The module `com.hmct.epd` (see `Android.bp`) is a platform `java_library`
meant to be added to the boot class path so it can replace the stock jar of
the same name.

## Provenance

This is a clean-room implementation written only from the specification
`cleanroom/specs/01-com.hmct.epd.md` (spec 01), Android documentation and
AOSP/LineageOS sources. Public names, constant values, transaction codes and
misspellings (`DetectCompleteLisenter`, `begigResumeActivity`,
`updateExteralPaper`, `ACTION_READING_TOMEOUT`, `WAKEUP_SORUCE_FROM_USER`)
are compatibility facts taken from that spec. Spec decisions left open are
recorded in `/QUESTIONS.md`.

## Layout

| File | Content |
|---|---|
| `src/com/hmct/epd/IEpdManager.aidl` | Service interface, descriptor `com.hmct.epd.IEpdManager`, 43 methods (codes 1..43) |
| `src/com/hmct/epd/IDirectionDetect.aidl` | Client callback, descriptor `com.hmct.epd.IDirectionDetect`, 3 methods (codes 1..3) |
| `src/com/hmct/epd/EpdManager.java` | Manager, 50 public constants, 60 public methods, nested `DetectCompleteLisenter` and `DirectionDetect` |

Method declaration order in the AIDL files fixes the transaction codes; do
not reorder or insert methods.

## Behaviour common to all `EpdManager` methods

Each instance method makes one synchronous binder call. A `RemoteException`
is swallowed and the method returns its failure value. If the manager was
built with a null service proxy the same failure value is returned without a
call (a compatible addition). No caching, threads, settings or property
access happen on the client side.

## Public API to binder mapping

| `EpdManager` method | `IEpdManager` call (code) | Failure value |
|---|---|---|
| `addViewOnExternal(v)` | `addViewToPresentationDisplay(v, 0, 2, 0, false)` (1) | - |
| `addViewOnExternal(v, prio)` | `addViewToPresentationDisplay(v, prio, 2, 0, false)` (1) | - |
| `addViewOnExternal(v, prio, mode)` | `addViewToPresentationDisplay(v, prio, mode, 0, false)` (1) | - |
| `addViewOnExternal(v, prio, mode, min)` | `addViewToPresentationDisplay(v, prio, mode, min, false)` (1) | - |
| `addBitmapOnExternal(b)` | `addBitmapToPresentationDisplayWithString(b, 0, 2, 0, false, null)` (2) | - |
| `addBitmapOnExternal(b, mode)` | `addBitmapToPresentationDisplayWithString(b, 0, mode, 0, false, null)` (2) | - |
| `addBitmapOnExternal(b, prio, mode, min)` | `...WithString(b, prio, mode, min, false, null)` (2) | - |
| `addBitmapOnExternal(b, prio, mode, min, bar)` | `...WithString(b, prio, mode, min, bar, null)` (2) | - |
| `addBitmapOnExternal(b, prio, mode, min, bar, extra)` | `...WithString(b, prio, mode, min, bar, extra)` (2) | - |
| (none) | `addBitmapToPresentationDisplay` (3) is never used by the client | - |
| `updateShutdownWallPaper(b)` | `updateShutdownWallPaper(b)` (4) | - |
| `addPreViewPage(int, Bitmap)` | `updateShutdownWallPaper(b)` (4), page ignored | - |
| `removeViewOnExternal()` / `(prio)` | `removeViewOnPresentationDisplay(0 / prio)` (5) | - |
| `getDualScreenTaskState()` | (6) | -1 |
| `setDualScreenEnabled(e, f)` | (7) | - |
| `finishBoot()` | (8) | - |
| `lockEpdMode(type, on)` | (9), logs an error on failure | - |
| `notifyCameraOn(on)` | `lockEpdMode(0, on)` (9) | - |
| `begigResumeActivity(pkg, cls)` | (10) | - |
| `notifyResumedPackage(name)` | (11) | - |
| `setDisplayType(t)` | `setDisplayType(t, false)` (12) | - |
| `setDisplayType(t, allowExit)` | (12) | - |
| `goToExternalForce(Integer t)` | `setDisplayType(t.intValue(), false)` (12); NPE on null | - |
| `setEpdDisplayMode(m)` | (13) | - |
| `setDisplayPowerMode(id, e)` | (14) | - |
| `setDisplayEnabled(id, e)` | (15) | - |
| `getDisplayEnabled(id)` | (16) | true |
| `setEinkThemeMode(m)` | (17) | - |
| `doPageDown()` | `setEinkThemeMode(2)` (17) | - |
| `setEinkKeyguardMode(m)` | (18) | - |
| `setGestureUnlock(e)` | (19) | - |
| `updateEpdMode()` | (20) | - |
| `getEpdMode()` | (21) | 6 |
| `updateExteralPaper()` | (22) | - |
| `getDisplayType()` | (23) | 0 |
| `isExternalDisplayAlready()` | `getDisplayType()` (23), true only for exactly 2 or 4 | false |
| `requestGoToSleep()` | (24) | - |
| `getLastScreenOnType()` | (25) | 0 |
| `forceClear()` | (26) | - |
| `isExternalDisplayOn()` | `isExternalType()` (27) | false |
| `lockWakeupSource()` | (28) | - |
| `unLockWakeupSource()` | (29) | - |
| `setWakeupSourceType(src, disp)` | `setWakeupSourceType(src, disp, src == 0)` (30) | - |
| `setWakeupSourceType(src, disp, home)` | (30) | - |
| `getWakeupSourceType()` | (31) | -1 |
| `startWakingUp()` | (32) | - |
| `onScreenChanged(on)` | (33) | - |
| `getTopPackage()` | (34) | null |
| `isTopActivity(pkg)` | `getTopPackage()` (34) unless `pkg` is null | false |
| `registerDirectionChangeListener(l)` | (35), only when no callback exists yet | - |
| `unregisterDirectionChangeListener(l)` | (36), only when a callback exists | - |
| `setExternalTpEnabled(e)` | (37) | - |
| `removeAppMode(pkg)` | (38) | - |
| `addAppMode(pkg, mode)` | (39) | - |
| `getAllAppMode()` | (40), `Map` read with `Parcel.readHashMap` | null |
| `addPreViewPage(int, RemoteViews)` | (41) | - |
| `adjustEinkEffect(type, value)` | (42) | - |
| `getEinkEffect(type)` | (43) | -1 |
| `static isExternalDisplayOn(int)` | none (pure: type >= 0 and (type & ~1) is 2 or 4) | - |
| `static isPrimaryDisplayOn(int)` | none (pure: type >= 0 and (type & ~1) is 0) | - |

### Direction callback

`registerDirectionChangeListener` creates one `EpdManager.DirectionDetect`
(an `IDirectionDetect.Stub`, listener type 0, request time
`SystemClock.currentThreadTimeMillis()`) per manager and registers it with
the service; the given listener replaces any previous one. Incoming
`onDirectionChanged(mode, requestTime)` (code 1) is forwarded to the stored
listener's `onDetectComplete`. `getListenerType` (2) and `getRequestTime` (3)
return the values given at creation. Unlike stock, listener state is
guarded by a lock (allowed by spec 01, 4.11).

## Framework integration (not part of this module)

The framework must register `"epd"` in `SystemServiceRegistry` as a cached
service, gated on `ro.hmct.epd.support == "1"`, fetching the `epd` binder
from `ServiceManager` without waiting and returning `null` if absent,
otherwise `new EpdManager(IEpdManager.Stub.asInterface(b), ctx)`.

In this repository that is `epd/patches/frameworks_base/0001-*.patch`
(spec 02 section 5.2), which registers the service without the gate; see
`/QUESTIONS.md` item 31.
