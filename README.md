<!--
Copyright (C) 2026 The a6l-eink-oss authors
SPDX-License-Identifier: Apache-2.0
-->

# a6l-eink-oss

Open-source, clean-room replacement of the E-ink ("epd") parts of the
Hisense A6L (front 1080x2340 LCD, rear 720x1440 E-ink) for LineageOS 18.1
(Android 11), as groundwork for later Android versions.

## Provenance

Everything here was written only from the clean-room specifications

- `01-com.hmct.epd.md` (spec 01: the client library), and
- `02-epd-service.md` (spec 02: the E-ink service),

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
| `epd/service/` | `A6lEinkService` app (package `org.a6leink.epd`): the `epd` binder service, the E-ink key handler and two settings pages (spec 02) |
| `epd/rootdir/etc/init.a6l.epd.rc` | init property bridge: `sys.a6l.epd.*` properties to sysfs writes (spec 02 section 1.4) |
| `epd/sepolicy/private/` | system-side policy: `epd` service type, bridge property type (spec 02 section 1.3) |
| `epd/overlay/` | lineage-sdk overlay loading `org.a6leink.epd.EpdKeyHandler` into system_server (spec 02 section 5.1) |
| `epd/epd.mk` | product fragment: packages, boot jar, rc file, overlay, `ro.hmct.epd.support=1` |
| `epd/BoardConfigEpd.mk` | board fragment: `BOARD_PLAT_PRIVATE_SEPOLICY_DIR` |
| `epd/patches/frameworks_base/` | frameworks/base patches: `getSystemService("epd")` registration and the keyguard per-finger unlock screen (spec 02 sections 5.2, 5.3); `epd/patches/apply.sh` applies them |

## Using it in a device tree

1. Put the repository in the source tree (for example `vendor/a6l-eink-oss`).
2. In the product makefile: `$(call inherit-product, vendor/a6l-eink-oss/epd/epd.mk)`.
3. In `BoardConfig.mk`: `include vendor/a6l-eink-oss/epd/BoardConfigEpd.mk`.
4. Apply the framework patches: `vendor/a6l-eink-oss/epd/patches/apply.sh .`
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
- Refresh mode per foreground app (default 8 on the E-ink), persisted in
  device-protected storage.
- While the E-ink is not in use it shows a status screen ("sleeping" with
  an analog or digital clock, or "using the LCD"), written as raw RGB565 to
  `/kdebuginfo/edpd/bitmap.raw` and committed through the bridge.
- `dumpsys epd` prints the state; `dumpsys epd status lcd|sleep|stop`
  forces a status screen for testing.

User settings (`Settings.System`, current user):

| Key | Default | Meaning |
|---|---|---|
| `a6l_epd_sleep_clock` | 1 | clock on the sleeping screen (settings page) |
| `a6l_epd_sleep_clock_style` | 0 | 0 analog, 1 digital (settings page) |
| `fp_unlock_screen` | absent | per-finger unlock screen, JSON (settings page) |
| `a6l_epd_power_key_wake` | 0 | 0: front power key wakes to the LCD; 1: to the last screen |
| `a6l_epd_default_wake` | 0 | 0: other wake-ups go to the last screen; 1: to the LCD |
| `a6l_epd_eink_key_action` | 0 | E-ink key while on the E-ink: 0 back to the LCD, 1 sleep |

## License

Apache License 2.0. Copyright (C) 2026 The a6l-eink-oss authors.
