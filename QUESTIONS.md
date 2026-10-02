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

12. **Default wake target** [6]. *Settled by spec 02 section 9.1: the
    power button wakes to the LCD, the E-ink key to the E-ink; the defaults
    below match.* Default: the port's rule (wake to the
    last used screen unless something named one; the front power key names
    the LCD while `a6l_epd_power_key_wake` is 0). New setting
    `Settings.System a6l_epd_default_wake`: 0 = last used screen (default),
    1 = LCD as on stock. No UI; `settings put system ...`.
13. **E-ink key while the E-ink is in use** [8]. *Settled by spec 02
    section 9.1: back to the LCD; the default below matches.* Default: back to the LCD
    (port). New setting `Settings.System a6l_epd_eink_key_action`: 0 = back
    to the LCD (default), 1 = sleep (stock). No UI.
14. **`setDisplayPowerMode`** [7]. No-op, as recommended.
15. **Camera/video locks** [9]. `lockEpdMode` implemented (types 0 and 1,
    mode 6 while a lock is on) and, as on stock, all locks are cleared when
    the type becomes -1. The service does not observe the camera or video
    playback itself (nobody calls `lockEpdMode` on 18.1).
    *Superseded by spec 05 (items 63 to 65): the service observes the camera
    and video playback itself, and locks force the smooth mode (8).*
16. **Gesture node and unlock-style settings** [10]. *Settled by spec 02
    section 9.3: rear touch gestures are not wanted.* Not implemented:
    `setEinkThemeMode`, `setEinkKeyguardMode` and `setGestureUnlock` store
    their argument in memory only (shown in dumpsys), as the spec
    recommends for the first two. Enabling rear-touch screen-off gestures
    would produce key codes nothing on 18.1 handles; no bridge property for
    `/sys/ctp1/ctp_func/gesture` exists.
17. **Foreground tracking** [11]. Task stack listener as in the spec, plus
    a query of the top task (`getTasks(1)`) on every `onTaskStackChanged`,
    which also sees a new top activity inside the same task. Still package
    granularity; no per-activity modes.
    *Superseded by spec 05 (item 54): package and activity of the focused
    task, with per-activity rules.*
18. **Presentation slots and shutdown picture** [12]. *Deferred by spec 02
    section 9.4; see item 37.* Not implemented; the
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


## Spec 02 section 9 (product decisions of 2026-10-02)

36. **Button swap on the E-ink** (9.2). `Settings.System
    a6l_epd_swap_keys_on_eink` (default 0), shown under "Buttons" on the
    "E-ink" settings page (the status screen page, renamed). When it is 1,
    the screen is on and the published display type
    (`sys.sysctl.display_type`) is 4, the key handler takes the whole power
    button press (down and up, so neither sleep nor the long-press power
    menu happens) and switches back to the LCD on release; the E-ink key
    puts the phone to sleep. The key path makes no binder call: the
    display type comes from the property and the setting is cached by a
    content observer (registered on the handler's thread, retried until
    the settings provider is up, re-read on user switch). Decision: "E-ink
    in use" means type 4 only, not the mirror (2), where the LCD is also
    on. The own settings `a6l_epd_default_wake` and
    `a6l_epd_eink_key_action` are kept (no UI); their defaults match 9.1.
37. **Later item (9.4, low priority).** Show a "Power off" status picture
    on the E-ink at shutdown (`ACTION_SHUTDOWN`), drawn like the sleeping
    screen and committed through the same bitmap path
    (`/kdebuginfo/edpd/bitmap.raw`). Presentation pages on the E-ink stay
    deferred. Not implemented yet.

# Open questions (implementation of spec 03, sleep screen stage 2)

Decisions taken for the widgets and notifications of the sleeping screen
(`epd/service`, documented in `docs/sleep-screen.md`).

38. **Redaction rule.** Spec 03 section 4 reads as "redact when
    `lock_screen_allow_private_notifications` = 0 *or* the notification is
    PRIVATE". `Notification.VISIBILITY_PRIVATE` is the Android default, so
    taken literally nearly every notification would be redacted even with
    "Show all notification content". Implemented as SystemUI does
    (`NotificationLockscreenUserManagerImpl.needsRedaction`): redact when the
    user set the channel/app lock screen visibility to private, or when the
    notification is PRIVATE and private content is not allowed (setting 0,
    default 0, or the device admin flag
    `KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS`). PUBLIC notifications are
    shown in full. `lock_screen_show_notifications` defaults to 1 as in
    SystemUI; the admin flag `KEYGUARD_DISABLE_SECURE_NOTIFICATIONS` also
    hides all. To confirm with the spec author.
39. **"Silent and minimized".** Skipped: importance below
    `IMPORTANCE_DEFAULT` (low and min), suspended apps, and notifications
    Do Not Disturb hides from the list (`SUPPRESSED_EFFECT_NOTIFICATION_LIST`).
    Group summaries are those with `FLAG_GROUP_SUMMARY` and a group.
    Notifications for `USER_ALL` count as the current user's.
40. **Icon.** The item icon is the notification's small icon drawn as a
    black silhouette (it is designed as one), falling back to the launcher
    icon thresholded at 50 % luminance. Spec says "app icon"; the small icon
    is normally the app's and reads better in black and white.
41. **Hidden items** ("Contents hidden") show app icon and app name only,
    without the time, as the spec lists.
42. **Widget sizes.** `size` 0 = provider's `minHeight` (default), 1/2/3 =
    small/medium/large (160/280/400 px). A widget that does not fit gets the
    space left if that is at least its `minResizeHeight` (else `minHeight`),
    otherwise it is skipped. 16 px between widgets, 16 px bottom margin.
43. **Density.** Widgets are laid out at the phone density and scaled to an
    assumed 280 dpi for the panel (about 276 ppi). The size reported to the
    provider (`updateAppWidgetOptions`, on add and on height change only) is
    in those units (672 px = 384 dp wide).
44. **Clocks off-screen.** `TextClock.refreshTime()` and, through
    reflection (allowed for a platform-signed app), `AnalogClock`'s private
    `onTimeChanged` are called before each draw, because both update only
    while attached to a window.
45. **Collection wait.** The widget is waiting while an `AdapterView` has no
    adapter or a `RemoteViewsAdapter` that is not data-ready, or while the
    tree requests layout; polled every 100 ms, done after two quiet polls,
    at most 1 s. A 2 s watchdog on the service thread draws without widgets
    if the main thread does not answer.
46. **Failed widgets** are left out and the sections are laid out again, so
    they leave no hole.
47. **`BIND_APPWIDGET` is not declared.** uid `system` passes the check, and
    declaring a privileged permission in a priv-app would need a
    privapp-permissions allowlist entry. If the device enforces otherwise,
    add the permission and an allowlist file.
48. **Users.** Widgets are chosen on the system user only (the service runs
    there); the settings page says so on other users. The listener is
    granted for the system user.
49. **Notification redraw timing.** The 10 s window counts from the last
    draw of any kind (minute redraws included). The deferred redraw uses an
    exact `ELAPSED_REALTIME_WAKEUP` alarm; it redraws only if the list still
    differs from the last draw. The list compared is the full filtered list
    (key and post time), so updates of a shown notification redraw too.
50. **High contrast** maps luminance <= 100 to black, >= 156 to white,
    linear in between. Invert draws the widget on black before converting,
    so white-on-transparent widgets come out black on white.
51. **Write errors** of the bitmap file are logged once per process (a boot,
    for this persistent app); `dumpsys epd` keeps the last one.
52. **Settings page summary** in Settings > Display now reads "Sleep screen,
    widgets, notifications and buttons".

# Open questions (implementation of spec 05, per-app E-ink behaviour)

Decisions taken for the per-app behaviour in `epd/service` and
`epd/settings` (documented in `docs/epd-service.md` section 8 and
`docs/eink-settings.md`). The user calls per-app settings a must-have.

53. **Product decisions (decided by the user, spec 05 section 8,
    2026-10-03; final).** (a) Automatic
    video switch: new on 18.1, a setting, on by default. (b) Camera switch:
    only while the app using the camera is in front, a setting, on by
    default. (c) "Slide one screen": the framework-free approximation of
    spec 05 section 6.3, per app, off by default for every app, marked
    experimental in the UI and the docs. (d) No separate input method for
    the E-ink. (e) One device-wide map (keyed by package, not per user).
54. **Front app tracking.** Task-stack listener (`onTaskStackChanged`,
    `onTaskMovedToFront`, `onTaskFocusChanged`, `onTaskRemoved`,
    `onTaskDisplayChanged`, PiP, docked-stack dismissal, back on task root);
    each burst is coalesced for 60 ms and followed by re-queries at 350 ms and
    1200 ms; the query is `getFocusedStackInfo()` (focused side in split
    screen; PiP ignored), falling back to the topmost non-PiP task. While the
    E-ink is in use a 2 s poll is a safety net; the listener registration is
    retried; screen-on, user-present and every change into an E-ink type
    re-query. `begigResumeActivity` / `notifyResumedPackage` only trigger a
    query. All state is re-read when the process restarts (front, keyguard,
    cameras via the listener's initial report, active playback).
55. **Evaluation order.** Spec 05 section 6.2 puts the lock screen first.
    Implemented: camera lock, lock screen, video lock, slide drag, fixed
    rules, per-app entry, helper host, default. The camera lock goes first
    because with decision (b) it only applies while the camera app is in
    front, which over a locked device means the camera occludes the lock
    screen (secure camera); the viewfinder should be smooth.
56. **Matching** (spec 05 section 6.2, confirmed by the user): an entry
    equal to the activity class name first (more specific, it overrides its
    package's entry), then the exact package, then the longest entry that
    contains the package or is contained in it (ties: lexicographically
    smallest key), so the result no longer depends on hash map order.
57. **When the per-app mode is re-applied.** On a change of the front
    package; on an activity change inside the same package only when the old
    or new activity has an activity rule (built-in video activity or an
    activity-class entry), as stock did for its fast activities. Everything
    else (screen-on, type change, keyguard, locks, options, slide, map
    changes, `updateEpdMode`) evaluates fully. Setting the current mode again
    is a no-op, so repeated evaluations write nothing.
58. **Keep-mode dialogs.** The two of spec 05 (18.1 names: permission
    controller `GrantPermissionsActivity`, `ChooserActivity`) plus
    `com.android.internal.app.ResolverActivity` ("open with"), own addition:
    the same kind of dialog over the app. They are not recorded as the front
    app, so `getTopPackage()` and the tile keep the app underneath.
59. **Helper apps.** Generalised from the stock list: a package with no
    launcher activity (in the front user) that is not the home app is a
    helper. Without a match of its own it follows the previous front app
    (or that app's host when the previous one was a helper too). The host is
    forgotten when a non-helper comes to the front (stock kept it until
    reboot, spec 05 open question 6). The launcher-activity answer is cached
    per user and package and dropped on every package broadcast.
60. **Built-in video activities.** No stock names are shipped. The list has
    AOSP Gallery2's `com.android.gallery3d.app.MovieActivity` and
    `com.android.gallery3d.app.TrimVideo` (the latter is also on the stock
    list; it is an AOSP class name). Smooth while on top, even if the package
    is set to clear.
61. **E-ink launcher rule** (`com.hmct.einklauncher` always clear) is kept:
    a compatibility fact, harmless when the package is absent.
62. **Home rule.** "Home" = the default home activity of the front user
    (`resolveActivityAsUser`), plus the stock `com.hmct.einklauncher` and
    `com.hmct.vision`. While home is in front no camera lock applies,
    including a client `lockEpdMode(0, true)` (stock ignored the camera lock
    for home apps too).
63. **Camera lock.** Cameras count as open while unavailable
    (`AvailabilityCallback`); the opener package comes from the hidden
    `onCameraOpened`. The lock applies while an open camera's opener is the
    front package (or the host a helper follows). An unknown opener (cameras
    already open when the service started) counts as the front app. At
    screen-off the lock is released as decided: cameras open at that moment
    are ignored until they are closed (a new open locks again); client locks
    are cleared as on stock. Reference-counted by camera id. The torch
    question (spec 05 open question 7) is mostly hidden by decision (b)
    (the torch is switched from the shade, the front app does not open it),
    still to check on the device. Known limit (accepted): a camera kept open
    across a screen-off does not lock again after the screen-on until it is
    closed and reopened.
64. **Video lock.** `AudioPlaybackCallback`; uid system is treated by
    AudioService as holding `MODIFY_AUDIO_ROUTING`, so player uids are not
    anonymised. "Video-like" (heuristic, own decision): state started and
    content type movie; or usage media, game or unknown with a content type
    other than speech, music or sonification. So reading apps that read
    aloud (speech) and music players stay in their mode; games with sound and
    most video players (which often leave the content type unknown) switch.
    Only the front app's own uid counts. To check on the device with the
    usual players and browsers.
65. **Mode values.** `EpdSettings.MODE_SMOOTH` = `MODE_DEFAULT_EINK` = 8 (no
    entry); `EpdSettings.MODE_CLEAR` = 3, the single constant for "clear"
    (per-app entries, lock screen, E-ink launcher rule), confirmed on the
    panel by the device test of 2026-10-03 (spec 05 section 8: visibly
    clearer, scrolling stutters); the settings app and the tile get it from
    the service.
    Locks and the slide drag force `MODE_SMOOTH` (8), not 6 as on stock and
    in the earlier item 15, because 6 is unmeasured at the node. `addAppMode`
    accepts 1..8 (stock 1..7; 8 so the default can be stored explicitly; an
    explicit 8 is shown as smooth); `setEpdDisplayMode` keeps 0..8.
66. **E-ink lock screen.** `KeyguardManager.isKeyguardLocked()` read before
    the type change at every screen-on, at user-present and at every front
    query. A locked keyguard while the E-ink is in use (types 2 and 4, so
    also the mirror) gives the clear mode, also for a swipe-only keyguard.
    *Changed by spec 02 section 10 item 4a (item 81): the default mode unless
    `a6l_epd_keyguard_clear` is 1.*
    The app's mode returns at `ACTION_USER_PRESENT` or the next query that
    sees the keyguard gone.
67. **Interface for the own options** (slide flags, the two switches):
    a `ContentProvider` in the service app (`call` only, authority
    `org.a6leink.epd.options`, `singleUser`, signature permission
    `org.a6leink.epd.permission.EINK_OPTIONS` checked in `call`), stored in
    the same device-protected file as the map. Chosen over a second binder
    service because it needs no `service_contexts`/SELinux change and no
    AIDL shared by two APKs. Not encoded into the `getAllAppMode` values
    (spec 05 section 6.4).
68. **Slide one screen approximation.** Gesture monitor on display 0
    (touches still reach the app), only while the front app (or its host)
    has the flag, the E-ink is in use and not locked. Drag = movement beyond
    the system touch slop; end = 600 ms after the last finger-up without a
    new touch; at the end the app's mode is re-applied, nothing else (spec
    05 section 8 decision c: a fast mode only while the finger drags; an
    extra ghost-clearing refresh per drag, which spec 05 section 6.3 offers
    as optional, was left out because it flashes the panel). The rear
    touch is assumed to feed display 0 (spec 02 section 2.3); to check on
    the device.
69. **Quick Settings tile** in the service app (it already knows the clear
    value and talks to `epd` directly; works for secondary users through the
    binder). It toggles the exact-package entry of `getTopPackage()`;
    unavailable on the LCD, for the home app and for `org.a6leink.*`. It
    shows the stored choice, not the effective mode (a lock may override it
    for a while).
70. **Package removal.** On `ACTION_PACKAGE_FULLY_REMOVED` the exact-package
    entry and slide flag are dropped when the package is installed for no
    user (the map is device-wide). Activity or substring keys are kept.
71. **Permissions.** Declared in the service manifest:
    `MANAGE_ACTIVITY_STACKS`, `CAMERA_OPEN_CLOSE_LISTENER`, `MONITOR_INPUT`
    (signature, no allowlist needed) and `MODIFY_AUDIO_ROUTING`
    (signature|privileged in frameworks/base `core/res/AndroidManifest.xml`).
    A platform-signed priv-app still needs an allowlist entry for a
    privileged permission (PermissionManagerService checks the allowlist
    before the signature), so `epd/permissions/privapp-permissions-a6leink.xml`
    is installed to `system_ext/etc/permissions` by `epd.mk`. uid system
    passes all four checks without declaring them; they are declared to
    document the use. The settings app requests
    `org.a6leink.epd.permission.EINK_OPTIONS` (signature, granted).
72. **SELinux.** `system_app` may not find `cameraserver_service` in the 18.1
    platform policy; `epd/sepolicy/private/a6l_epd.te` allows it (needed by
    the camera listener). Everything else (activity task, audio, input
    services, binder calls back from cameraserver) is already allowed for
    apps.
73. **Settings app UI.** The row switch flips smooth/clear; tapping the row
    opens a dialog with the mode (a custom stored value is kept unless
    Smooth is picked) and "Slide one screen (experimental)". The automatic
    switches are on the "E-ink apps & tuning" page. Only exact-package
    entries are listed (activity or substring keys from other tools are
    applied but not shown).

## Device test of 2026-10-03 (userdebug-14), fixed with spec 05

74. **E-ink-only set-up after a screen-on** (spec 02 sections 2.3, 2.4). A
    wake with the E-ink key straight into type 4 (also with the button swap,
    and some fingerprint wakes) logged "Display type -1 -> 4 (screen on)" but
    not "Entering E-ink only": the set-up was still marked as applied from
    before the screen-off, so `enter()` did nothing and only the front-touch
    and backlight repeats ran; the LCD stayed lit. Now every screen-on into
    type 4 applies the whole set-up again (forced 1080x2160 size, front touch
    off, rear touch on, manual brightness with the saved mode kept,
    brightness observer, backlight off), logs it, reconnects the E-ink and
    schedules the repeats, whatever the wake reason. The swap path (E-ink key
    sleeps on the E-ink, E-ink key wakes) goes through the same screen-on
    handling: the key handler only records the wake-up display and wakes.
75. **`com.hmct.intent.EINK_KEY_PRESSED`** is sent from system_server, which
    triggered "Sending non-protected broadcast". Spec 02 section 5.1 allows
    sending it; it is now declared `<protected-broadcast>` in the service's
    manifest (privileged system app), next to `com.hmct.action.displaychanged`.
76. **"oneway ... UNKNOWN_TRANSACTION" in org.a6leink.epd.** No call of this
    project uses a wrong descriptor or a code outside `IEpdManager` 1..43.
    The log is what IPCThreadState prints when a one-way transaction is not
    claimed by a Java binder; the likely source is the "system properties
    changed" poke (`SYSPROPS_TRANSACTION`, `_SPR`), which the activity
    manager forwards one-way to every app process (the framework's own
    application thread does not claim it either, so every app logs this) and
    tools can send to services. The `epd` binder now claims that code (the
    native layer already handles it), so it no longer logs for `epd`; the
    same line from the app's application thread is framework behaviour.
77. **Per-app map empty after a change.** The user had set the app back to
    smooth, so not a bug. Hardened anyway: the file is read before anything
    can write it (client calls that arrive before the load are kept in memory
    and win over the file; the file is written only after the load), the
    load is logged with its counts, and each `addAppMode` / `removeAppMode`
    that changes the map is logged with the caller's uid.

## Device findings of spec 02 section 10 (2026-10-03, userdebug-15)

78. **Keyguard presentation on the E-ink** (item 1). Third frameworks/base
    patch, `0003-Keyguard-no-keyguard-presentation-on-the-A6L-E-ink-display.patch`:
    `KeyguardDisplayManager.isKeyguardShowable()` returns false for
    `Display.TYPE_EXTERNAL` displays when `ro.hmct.epd.support` is `"1"`.
    Chosen over matching the display by name or unique id because the 18.1
    layout has exactly one external display (the E-ink) and the property
    already marks the device; other devices are unaffected. `apply.sh` needs
    no change (it applies every `*.patch` in name order).
79. **Transient 4 -> 0 before a swapped E-ink-key sleep** (item 2). No code
    path of this project sets type 0 for the swapped sleep: the key handler
    sleeps directly, and only two calls set 0 (the swapped power-button
    release and the unswapped E-ink key). Two causes are guarded against: (a)
    the swap decision on the input thread uses a cached setting; the handler
    now reads it again before the unswapped "switch to the LCD", so a stale
    cache sleeps instead; (b) any `setDisplayType` with a screen-on type that
    arrives while the device is no longer interactive (going to sleep, before
    the screen-off broadcast) is ignored, so the type goes 4 -> -1 directly
    whoever asked. Every `setDisplayType` is now logged with the caller's uid
    and pid, so the device log names the source if it still happens.
80. **Bitmap one commit late** (item 3): noted, not investigated or changed
    here (spec 02 says no change yet).
81. **Lock screen mode** (spec 02 section 10 item 4a, user decision). While
    the keyguard is showing on the E-ink the mode is the default (8) unless
    `Settings.System a6l_epd_keyguard_clear` (current user, default 0) is 1,
    then the clear mode. Switch "Clear mode on the lock screen" under a new
    "Lock screen" heading on the service's "E-ink" page (English, Japanese).
    The service caches the value (re-read at every screen-on and on a change
    observed for all users) so the evaluation needs no provider call.
82. **Wake timing log** (item 4b). Tag `A6lEpdWake`, milliseconds since the
    service saw the screen-on broadcast (the actual power-on moment is not
    available to the app; the broadcast delay is therefore not included).
    Steps: screen-on seen, display type set (mode and reason), refresh mode
    written (only when it changes), E-ink-only setup applied, reconnect
    requested, each delayed re-application, the user activity. Window 6 s or
    until screen-off. The first frame is not observable by the service.
83. **User activity after a locked wake** (item 4c). One
    `PowerManager.userActivity(now, USER_ACTIVITY_EVENT_OTHER, 0)` 1.5 s
    after a screen-on into type 2 or 4 with the keyguard showing, skipped if
    the device is no longer awake or no longer on the E-ink; cancelled by a
    screen-off. `DEVICE_POWER` (signature) declared for documentation.
84. **SurfaceFlinger repaints after a reconnect** (spec 02 section 10 item
    5). `Repainter` sends transaction 1004 (token
    `android.ui.ISurfaceComposer`, service `SurfaceFlinger`, synchronous,
    no reply data read) at +1.2, +2.5 and +4 s after every reconnect: the
    screen-on into type 4, `applyHardware` for types 4 and 2 (switches,
    screen-on into the mirror, "E-ink ready" in type 4). A new reconnect
    restarts the schedule; screen-off and entering the LCD cancel it. A
    missing service or failed transaction is logged once per process and
    counted in `dumpsys epd`. Code 1004 is a SurfaceFlinger debug
    transaction, not a stable API: re-check it for Android 16. SELinux:
    `surfaceflinger_service` is an `app_api_service` and apps may call
    surfaceflinger, so no rule was added.
85. **HWC crash after a fingerprint wake** (spec 02 section 10 item 6):
    noted only (one SIGSEGV in the stock HWC's E-ink update thread about
    0.9 s after a fingerprint wake on userdebug-17, cause unknown); no change.

