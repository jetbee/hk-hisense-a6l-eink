<!--
Copyright (C) 2026 The a6l-eink-oss authors, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# frameworks/base patches

> **概要（日本語）** `epd/patches/frameworks_base/` には LineageOS 18.1 の frameworks/base
> に当てる 2 つのパッチがあります。0001 は `getSystemService("epd")` で `EpdManager` を
> 返すようにシステムサービスレジストリへ登録するもの、0002 は SystemUI のキーガードで
> 指紋認証成功時に指ごとの解除画面（LCD／E-ink）を E-ink サービスへ伝えるものです。
> 本書では各パッチの変更内容と理由、適用方法、そして Android 16 へ移植する際に
> やり直す必要がある点を説明します。

Two patches, made against frameworks/base of LineageOS 18.1 (Android 11).
They are the only changes this project makes outside its own directory; the
rest of the E-ink stack is an app, a boot jar, an rc file, policy and an
overlay ([epd-service.md](epd-service.md)).

Sources: clean-room specs 01 (`01-com.hmct.epd.md`, section 1) and 02
(`02-epd-service.md`, sections 5.2 and 5.3), and the patches themselves.

The patches carry their own licence note (Apache-2.0 for the added code); the
patched files keep their own headers.

## 1. 0001: register the `epd` service

File: `0001-SystemServiceRegistry-register-the-A6L-epd-E-ink-ser.patch`
Changes: `core/java/android/app/SystemServiceRegistry.java` (+38 lines).

**What.** `Context.getSystemService("epd")` returns a
`com.hmct.epd.EpdManager` wrapping the `epd` binder.

- A cached (per-context) service fetcher is registered under `"epd"` from
  the registry's static initialisation, with class name `EpdManager`.
- The fetcher looks up the `epd` binder without waiting
  (`ServiceManager.checkService`). It wraps it with
  `com.hmct.epd.IEpdManager.Stub.asInterface` and constructs
  `new EpdManager(IEpdManager, Context)` with the outer context.
- If the binder is not published yet, or the library classes cannot be
  loaded, the fetcher reports "service not found" and the caller gets
  `null`.
- `"epd"` is added to the list of services for which a missing manager is
  expected (so the framework returns `null` instead of logging a "wtf").

**Why.** Stock apps and the keyguard hook (0002) obtain the manager with
`getSystemService("epd")`; on stock firmware the framework had that entry.
The name, the returned class and its constructor are kept identical for
compatibility with stock apps.

Design choices:

- **Reflection instead of a build dependency.** The manager lives in the
  `com.hmct.epd` boot jar, which is itself built against the framework, so
  frameworks/base reaches it by class name. Both are on the boot class path
  at run time.
- **No `ro.hmct.epd.support` gate.** Stock registered the entry only when
  that property was `1`. The patch registers unconditionally, because a gate
  read during zygote preload depends on the property's SELinux label.
  `epd.mk` still sets the property to `1` (labelled `exported_system_prop`)
  for apps that check it.
- **No `Context.EPD_SERVICE` field.** javac inlines the constant into
  callers, so stock apps compiled against it keep working, and adding a field
  would touch the public API surface. New code uses the literal `"epd"`.
- **Cached `null`.** The 18.1 cached fetcher stores "not found" for the life
  of the context. A process that asks before the service app registered
  (early SystemUI) keeps getting `null`; such callers must fall back to
  `ServiceManager.checkService("epd")`, as 0002 does.

## 2. 0002: per-finger E-ink unlock screen in the keyguard

File: `0002-Keyguard-per-finger-E-ink-unlock-screen-for-the-A6L.patch`
Changes:
`packages/SystemUI/src/com/android/keyguard/KeyguardUpdateMonitor.java`
(+58 lines).

**What.** In the fingerprint authentication callback, on success and before
SystemUI handles the unlock, a new private method:

1. reads `Settings.System fp_unlock_screen` for the authenticated user: a
   JSON object mapping fingerprint ids (decimal strings) to `"0"` (LCD) or
   `"1"` (E-ink);
2. looks up the authenticated finger's biometric id; no entry or another
   value means "no choice" and nothing is done;
3. obtains the service: `getSystemService("epd")`, or, if that is `null`
   (cached early), `IEpdManager.Stub.asInterface(ServiceManager.checkService("epd"))`;
4. device not interactive (the unlock wakes it):
   `setWakeupSourceType(1, type, type == 0)`; device interactive (unlock
   over a showing keyguard): `setDisplayType(type, false)`. `type` is 0 for
   `"0"` and 4 for `"1"`.

All calls go through reflection (SystemUI has no build dependency on the
library). Any error is logged and the unlock proceeds unchanged.

**Why.** Stock firmware let the user choose per finger which screen a
fingerprint unlock goes to. The setting key and JSON format are kept
identical for compatibility with the stock Settings app, which wrote it. The
call in the interactive case is this project's addition (stock did nothing
there). The service then wakes to, or switches to, that panel
([epd-service.md](epd-service.md#7-per-finger-unlock-screen)).

## 3. Applying them

```sh
# from the top of the LineageOS 18.1 source tree
vendor/a6l-eink-oss/epd/patches/apply.sh .           # git am --3way, one commit per patch
vendor/a6l-eink-oss/epd/patches/apply.sh . --check   # git apply --check only
```

`apply.sh <android-source-root> [--check]` maps the directory
`frameworks_base` to the project `frameworks/base` and applies every
`*.patch` in it in name order.

Notes:

- The patches apply to frameworks/base HEAD of the 18.1 tree. Revert any older
  local E-ink changes to `SystemServiceRegistry.java` and
  `KeyguardUpdateMonitor.java` first (an earlier port changed the same
  files), otherwise `git am` stops with a conflict.
- They become local commits in `frameworks/base`; `repo sync` rebases them.
  If one no longer applies after a sync, resolve with `git am --3way` or
  re-create the change by hand from the description above.
- Applying by hand works too:
  `git -C frameworks/base am --3way vendor/a6l-eink-oss/epd/patches/frameworks_base/*.patch`.
- Rebuild the framework and SystemUI (a normal full build does that).

Check on a device: `dumpsys epd` works independently of the patches;
`getSystemService("epd")` returning non-null in an app, and a finger
configured on "Back (E-ink)" waking to the E-ink, confirm 0001 and 0002.

## 4. Porting to Android 16

The service, its policy and its rc file are mostly independent of
frameworks/base; the two patches have to be redone by hand on the newer
sources. What has to be checked or redone:

### 4.1 0001 (service registration)

- Re-locate the registration point in `SystemServiceRegistry` (static
  initialiser) and the list of services allowed to be missing in
  `getSystemService`; both still exist in recent releases but their exact
  layout changes between versions.
- Re-check the cached-fetcher behaviour on "service not found" (whether a
  `null` is still cached for the context's lifetime). If not, callers'
  fallbacks are harmless.
- The boot jar: newer build systems require boot jars in the
  `<apex>:<module>` form (`platform:com.hmct.epd` in `PRODUCT_BOOT_JARS`) and
  may require the module to be listed for hidden-API processing and the boot
  image / `platform_bootclasspath` configuration. Keep the jar's
  `permitted_packages`.
- Decide again whether to reflect or depend: a real build dependency of
  framework on `com.hmct.epd` would create a cycle, so reflection is still
  the simplest choice.
- If the `ro.hmct.epd.support` gate is reintroduced, make sure the property
  is readable during zygote preload under its label.

### 4.2 0002 (keyguard hook)

- SystemUI's keyguard and biometric code was restructured after Android 11
  (device-entry and biometric interactors, flows). Find the place where a
  successful fingerprint authentication is first known and where the unlock
  (and the wake-up it causes) is dispatched; the hook must run **before** the
  wake-up so that the service sees the recorded wake-up display at the next
  screen-on.
- Re-check `AuthenticationResult.getFingerprint()` /
  `Fingerprint.getBiometricId()` and how the authenticated user id is
  obtained.
- Keep the setting key `fp_unlock_screen` and its JSON format unchanged.
- Keep the `ServiceManager` fallback for a `null` manager.

### 4.3 Outside the patches, for the same port

Not patches, but needed for an Android 16 build of the stack:

- The LineageOS `DeviceKeyHandler` overlay (`config_deviceKeyHandlerLibs` /
  `config_deviceKeyHandlerClasses`) and the interface must still exist in the
  target LineageOS; otherwise the E-ink key needs another hook.
- Hidden APIs used by the service changed: the physical-display calls of
  `SurfaceControl` used to blank the E-ink moved in later releases; task
  listing (`getTasks`) changed signature; check `IWindowManager` forced size,
  `DisplayManager.setTemporaryBrightness` and `PowerManager` calls.
- SELinux macros and property types (`system_internal_prop`, service
  attributes) and the HWC's behaviour (`fb1` nodes, bitmap file, blanking
  rule) must be re-verified with the vendor image used on Android 16.
