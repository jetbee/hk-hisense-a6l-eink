<!--
Copyright (C) 2026 The a6l-eink-oss authors
SPDX-License-Identifier: Apache-2.0
-->

# Open questions (implementation of spec 01, com.hmct.epd)

Points the spec left open that affected `epd/framework`, and what was decided.

1. **Null service proxy.** Spec 4 (general) allows but does not require a
   null guard. Decision: every instance method returns its failure value
   without a call when the proxy is null.
2. **Thread safety of the direction listener.** Spec 4.11 allows adding
   synchronisation. Decision: callback/listener fields are guarded by a
   private lock; the register/unregister binder calls happen under that lock
   so that "stored only if the call succeeded" stays atomic; listener
   dispatch happens outside the lock.
3. **One-shot ("request") listener.** Spec 4.10 describes a dispatch path
   with no public entry point. Decision: kept as a private field honoured by
   dispatch (dropped after a call with `requestTime > 0`), never set. Should
   a later spec add a public entry point, it plugs in there.
4. **Register when the service proxy is null.** Not covered by the spec.
   Decision: nothing is stored (same as a failed binder call).
5. **Logging text.** Not part of the contract. Decision: own wording; the
   `lockEpdMode` failure log is error level and contains "notifyCameraOn" as
   the spec mentions. Log calls are on tag `EpdManager`.
6. **AIDL parameter names.** The spec gives only types and order (names do
   not affect the wire format). Decision: descriptive names chosen here.
7. **`Context.EPD_SERVICE` and `SystemServiceRegistry` registration.** These
   live in the framework, not this library, and are out of scope for this
   module (spec 01 open question 6 on hidden-ness also unresolved). Needs the
   framework spec.
   Update (spec 02): registration is done by
   `epd/patches/frameworks_base/0001-*.patch`; see items 31 and 32 below.
8. **Boot jar wiring.** `Android.bp` sets `installable`, `platform_apis` and
   `permitted_packages: ["com.hmct.epd"]`; adding it to `PRODUCT_BOOT_JARS`
   (and any hiddenapi/boot-image configuration) is left to the device tree.
   Whether the stock jar was compiled with a specific `sdk_version` is
   unknown and assumed irrelevant.
9. **`@hide` on the AIDL interfaces.** Spec does not say; marked `{@hide}`
   in the AIDL comments since the library is not part of any public SDK.
   No effect on the binary.

# Open questions (implementation of spec 02, the E-ink service)

Decisions taken for `epd/service`, `epd/rootdir`, `epd/sepolicy`,
`epd/overlay`, the makefiles and `epd/patches`. Numbers in brackets refer to
spec 02's open questions (section 8).

## Answered from AOSP sources

10. **init re-trigger on an unchanged value** [3]. In 18.1
    `system/core/init/property_service.cpp`, `PropertySet` calls
    `PropertyChanged` on every successful set, also when the value is
    unchanged, and `SystemProperties.set` / `__system_property_set` always
    reach init. So value properties do re-trigger and the front-touch
    repeats reach the node. Kept the port's property kinds (value for
    touches, mode and tuning; counters for one-shots); counters are seeded
    from `elapsedRealtime` so they also differ after a process restart.
    Still worth a check on the device.
11. **Null caching in `getSystemService("epd")`** [2]. Yes: the 18.1
    `CachedServiceFetcher` stores `STATE_NOT_FOUND` on
    `ServiceNotFoundException` and returns the cached null for the life of
    that context. The fetcher follows the spec (null when missing); the
    keyguard hook falls back to a direct `ServiceManager.checkService("epd")`
    lookup when its context cached a null. Other early callers must do the
    same.

## Product decisions (spec recommendation as default, setting where cheap)

12. **Default wake target** [6]. Default: the port's rule (wake to the
    last used screen unless something named one; the front power key names
    the LCD while `a6l_epd_power_key_wake` is 0). New setting
    `Settings.System a6l_epd_default_wake`: 0 = last used screen (default),
    1 = LCD as on stock. No UI; `settings put system ...`.
13. **E-ink key while the E-ink is in use** [8]. Default: back to the LCD
    (port). New setting `Settings.System a6l_epd_eink_key_action`: 0 = back
    to the LCD (default), 1 = sleep (stock). No UI.
14. **`setDisplayPowerMode`** [7]. No-op, as recommended.
15. **Camera/video locks** [9]. `lockEpdMode` implemented (types 0 and 1,
    mode 6 while a lock is on) and, as on stock, all locks are cleared when
    the type becomes -1. The service does not observe the camera or video
    playback itself (nobody calls `lockEpdMode` on 18.1).
16. **Gesture node and unlock-style settings** [10]. Not implemented:
    `setEinkThemeMode`, `setEinkKeyguardMode` and `setGestureUnlock` store
    their argument in memory only (shown in dumpsys), as the spec
    recommends for the first two. Enabling rear-touch screen-off gestures
    would produce key codes nothing on 18.1 handles; no bridge property for
    `/sys/ctp1/ctp_func/gesture` exists.
17. **Foreground tracking** [11]. Task stack listener as in the spec, plus
    a query of the top task (`getTasks(1)`) on every `onTaskStackChanged`,
    which also sees a new top activity inside the same task. Still package
    granularity; no per-activity modes.
18. **Presentation slots and shutdown picture** [12]. Not implemented; the
    slot methods are accepted and ignored. The status screen uses the same
    bitmap path, so slots can be added later.
19. **Per-app mode file** [13]. Device-protected storage, as recommended:
    `epd_app_modes.xml` in the app's device-protected files directory
    (`android:defaultToDeviceProtectedStorage` is also set), format
    `<app-modes><app package=".." mode=".."/></app-modes>`, written with
    `AtomicFile` on the service thread after each change (no debounce).

## Other decisions

20. **Package and module names.** Package `org.a6leink.epd`, module
    `A6lEinkService`, APK
    `/system_ext/priv-app/A6lEinkService/A6lEinkService.apk` (the overlay
    path). The overlay keeps the lineage-sdk default LineageParts handler as
    the first entry.
21. **Dexpreopt and R8 off** for the app, because system_server loads
    `EpdKeyHandler` from the APK with a `PathClassLoader` and the class is
    only referenced by name.
22. **Wake-up source rules.** Stock rules (spec 2.7): every screen change
    clears the wake-up lock, a screen-on sets the source to -1, the initial
    source is 1. Only source 1 with display 0 or 4 records a wake-up
    display.
23. **Unknown display types.** After dropping bit 0, any type other than
    0, 2 or 4 (and other than negative, which means -1) is ignored and
    logged. Not covered by the spec.
24. **Refresh mode range.** `setEpdDisplayMode` accepts 0..8 and ignores
    other values (the node takes the raw number; spec open question 1).
25. **`requestGoToSleep`** puts the device to sleep (port behaviour; the
    spec allows either).
26. **`finishBoot`** does as stock (sets `sys.einklauncher.ready=1`,
    restores `accelerometer_rotation`) whoever calls it.
27. **`getDisplayEnabled` for other ids** returns true (spec
    recommendation).
28. **Call restriction, reading timeout, `EINK_KEY_PRESSED`, screenshot
    note.** The dialer-only restriction during calls and
    `com.hmct.action.reading.timeout` are not implemented (optional).
    The key handler sends `com.hmct.intent.EINK_KEY_PRESSED` to the current
    user on every E-ink key press (optional, cheap). Double and long press
    are not detected.
29. **"E-ink ready" robustness.** The 5 s delay starts at the first of
    LOCKED_BOOT_COMPLETED / BOOT_COMPLETED (runtime and manifest receivers)
    or at process start when `sys.boot_completed` is already 1. If the delay
    ends while `sys.boot_completed` is still unset, it waits another 5 s
    instead of touching `fb1`.
30. **Status screen wake lock.** Held from the state change (500 ms wait)
    through the draw (1500 ms), so a screen-off cannot suspend the CPU
    before the draw. The digital clock follows the 12/24 h setting of the
    current user; the 12 h pattern keeps the locale's AM/PM marker. Date
    skeleton is a string resource (`EEEEMMMMd`, Japanese `MMMdEEE`).
31. **`ro.hmct.epd.support`.** The frameworks/base patch registers `epd`
    unconditionally (port behaviour, allowed by spec 02 section 5.2) because
    a gate read during zygote preload depends on the property's SELinux
    label. `epd.mk` still sets the property to 1 and the sepolicy labels it
    `exported_system_prop` so stock apps that check it can read it.
32. **`Context.EPD_SERVICE`** (spec 01) is not added: the constant is
    inlined into callers by javac, and adding a field to `Context` would
    touch the API surface. Callers use the literal `"epd"`.
33. **`sys.sysctl.display_type` readability.** It keeps the default
    `system_prop` label, which untrusted apps cannot read on 18.1; the HWC
    may read it under that label (spec 1.3), so it was not relabelled.
    Apps should read `Settings.System current_display_type` or listen to
    the broadcast. Open: whether a stock app needs the property.
34. **Broadcast flags.** `com.hmct.action.displaychanged` is sent with
    `FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT` only (as the port), so
    manifest receivers of apps targeting O+ do not get it. Open: whether a
    stock app relies on a manifest receiver (`FLAG_RECEIVER_INCLUDE_BACKGROUND`
    would cover it).
35. **Framework patches vs. the current tree.** The patches are made
    against frameworks/base HEAD of the 18.1 tree and apply there. The
    working tree currently carries uncommitted local changes in the same
    two files (the port's), so they must be reverted before applying.
