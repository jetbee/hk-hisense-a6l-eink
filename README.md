<!--
Copyright (C) 2026 The hk-hisense-a6l-eink authors
SPDX-License-Identifier: CC-BY-4.0
-->

# hk-hisense-a6l-eink

Open-source, clean-room replacement of the E-ink ("epd") parts of the
Hisense A6L (front 1080x2340 LCD, rear 720x1440 E-ink) for LineageOS 18.1
(Android 11), as groundwork for later Android versions.

## Provenance

Everything here was written only from the clean-room specifications

- `01-com.hmct.epd.md` (spec 01: the client library),
- `02-epd-service.md` (spec 02: the E-ink service),
- `03-sleep-screen-stage2.md` (spec 03: widgets and notifications on the
  sleeping screen),
- `04-eink-settings.md` (spec 04: the E-ink settings app), and
- `05-per-app-eink.md` (spec 05: per-app E-ink behaviour),

the Android documentation and AOSP/LineageOS sources. Names, codes and
values needed for compatibility with stock apps (binder name `epd`, AIDL
descriptors and transaction codes, settings keys, properties, broadcasts,
sysfs nodes, the bitmap file format) are taken from those specs. Every
commit names the specs it used. Decisions on points the specs leave open
are in [`QUESTIONS.md`](QUESTIONS.md).

## Layout

| Path | Content |
|---|---|
| `epd/framework/` | `com.hmct.epd` boot jar: `EpdManager`, `IEpdManager`, `IDirectionDetect` (spec 01). See its README |
| `epd/service/` | `A6lEinkService` app (package `org.a6leink.epd`): the `epd` binder service, the E-ink key handler, two settings pages (spec 02), per-app E-ink behaviour and the Quick Settings tile "E-ink: clear/smooth" (spec 05) |
| `epd/settings/` | `A6lEinkSettings` app (package `org.a6leink.settings`): per-app display mode and options, automatic switching, E-ink tuning (specs 04, 05) |
| `epd/permissions/` | privileged-permission allowlist of the service app (`MODIFY_AUDIO_ROUTING`, spec 05) |
| `epd/rootdir/etc/init.a6l.epd.rc` | init property bridge: `sys.a6l.epd.*` properties to sysfs writes (spec 02 section 1.4) |
| `epd/sepolicy/private/` | system-side policy: `epd` service type, bridge property type (spec 02 section 1.3) |
| `epd/overlay/` | lineage-sdk overlay loading `org.a6leink.epd.EpdKeyHandler` into system_server (spec 02 section 5.1) |
| `epd/epd.mk` | product fragment: packages, boot jar, rc file, privapp allowlist, overlay, `ro.hmct.epd.support=1` |
| `epd/BoardConfigEpd.mk` | board fragment: `BOARD_PLAT_PRIVATE_SEPOLICY_DIR` |
| `epd/patches/frameworks_base/` | frameworks/base patches: `getSystemService("epd")` registration, the keyguard per-finger unlock screen and no keyguard presentation on the E-ink (spec 02 sections 5.2, 5.3, 10); `epd/patches/apply.sh` applies them |

## Using it in a device tree

1. Put the repository in the source tree (for example `vendor/a6l-eink`).
2. In the product makefile: `$(call inherit-product, vendor/a6l-eink/epd/epd.mk)`.
3. In `BoardConfig.mk`: `include vendor/a6l-eink/epd/BoardConfigEpd.mk`.
4. Apply the framework patches: `vendor/a6l-eink/epd/patches/apply.sh .`
   (`--check` only verifies). They are made against frameworks/base of
   LineageOS 18.1; revert any older local E-ink changes to the same files
   first.

The device tree keeps what spec 02 assigns to it: the vendor init rc with
the E-ink node permissions, `epd_connect`/rear-touch writes at
`sys.boot_completed=1`, the `/kdebuginfo/edpd` directory, the vendor
SELinux rules and the rear touch `.idc` file.

## The service in short

- Persistent, direct-boot-aware app with `android.uid.system`, registers
  `epd` in `Application.onCreate`. State lives under one lock; hardware and
  settings work runs on one handler thread.
- Display types 0 (LCD), 2 (mirror), 4 (E-ink only) and -1 (off), driven by
  `setDisplayType`, the screen on/off broadcasts, the E-ink key and the
  keyguard. E-ink only forces display 0 to 1080x2160, switches the front
  touch and LCD backlight off and the rear touch on; leaving it undoes all
  of that. Published through `sys.sysctl.display_type`,
  `Settings.System current_display_type` and `com.hmct.action.displaychanged`.
- No `fb1` node is written before "E-ink ready" (5 s after boot completion),
  because that crashes the stock HWC.
- Refresh mode per front app (smooth = default 8, clear = an entry),
  persisted in device-protected storage; the front app and activity come
  from a task-stack listener. Camera and video automatically switch the
  front app to smooth, the E-ink lock screen is clear, "slide one screen" is
  an experimental per-app option, and a Quick Settings tile flips the front
  app. `dumpsys epd` says why the current mode was chosen
  ([docs/epd-service.md](docs/epd-service.md#8-refresh-mode-and-per-app-behaviour)).
- While the E-ink is not in use it shows a status screen ("sleeping" with
  an analog or digital clock, or "using the LCD"), written as raw RGB565 to
  `/kdebuginfo/edpd/bitmap.raw` and committed through the bridge. While
  sleeping, the user's widgets and notifications are drawn below the clock
  ([docs/sleep-screen.md](docs/sleep-screen.md)).
- `dumpsys epd` prints the state; `dumpsys epd status lcd|sleep|stop`
  forces a status screen for testing.

User settings (`Settings.System`, current user):

| Key | Default | Meaning |
|---|---|---|
| `a6l_epd_sleep_clock` | 1 | clock on the sleeping screen ("E-ink" settings page) |
| `a6l_epd_sleep_clock_style` | 0 | 0 analog, 1 digital (settings page) |
| `fp_unlock_screen` | absent | per-finger unlock screen, JSON (settings page) |
| `a6l_epd_power_key_wake` | 0 | 0: front power key wakes to the LCD; 1: to the last screen |
| `a6l_epd_default_wake` | 0 | 0: other wake-ups go to the last screen; 1: to the LCD |
| `a6l_epd_eink_key_action` | 0 | E-ink key while on the E-ink: 0 back to the LCD, 1 sleep |
| `a6l_epd_swap_keys_on_eink` | 0 | 1: on the E-ink the power button goes back to the LCD and the E-ink key sleeps (settings page, "Buttons") |

Button roles (spec 02 section 9.1): with the screen on, the power button
sleeps and the E-ink key switches between LCD only and E-ink only; with the
screen off, the power button wakes to the LCD and the E-ink key to the
E-ink. Rear touch gestures are not used (9.3).

## Later

- Show a "Power off" status picture on the E-ink at shutdown, through the
  same bitmap path as the sleeping screen (spec 02 section 9.4, low
  priority). Presentation pages on the E-ink are deferred.

## License

- Code (Java, AIDL, makefiles, Android.bp, resources, init and sepolicy files, patches): Apache License 2.0 ([LICENSE](LICENSE)).
- Documentation (`docs/`, the README files and `QUESTIONS.md`): Creative Commons Attribution 4.0 International (CC BY 4.0, https://creativecommons.org/licenses/by/4.0/).

Copyright (C) 2026 The hk-hisense-a6l-eink authors.
