<!--
Copyright (C) 2026 jetbee, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# The E-ink service (`A6lEinkService`)

> **概要（日本語）** `A6lEinkService`（パッケージ `org.a6leink.epd`）は、Hisense A6L の
> 背面 E-ink を制御するバインダーサービス `epd` を提供する常駐システムアプリです。
> 本書ではプロセス構成、起動時のタイミング制約（「E-ink ready」まで fb1 に触れない理由）、
> 表示タイプ（-1/0/2/4）と遷移、各状態でハードウェアに適用する内容（sysfs ノードと
> init プロパティブリッジ）、E-ink キーと電源ボタンの役割（入れ替え設定を含む）、
> 指ごとの解除画面、アプリごとの E-ink 動作（手前のアプリの検出、くっきり/なめらか、
> カメラと動画の自動切り替え、E-ink のロック画面、試験運用の「1 画面スライド」、
> クイック設定タイル）、永続化と設定キー、権限と SELinux、E-ink のステータス画面
> （第 1 段階：表示内容、ビットマップのパスと形式、コミット、更新間隔）、
> デバイスへの組み込み方法、実機での確認方法（`dumpsys epd` など）、既知の制限を説明します。

The service implements `com.hmct.epd.IEpdManager` (see
[epd-api.md](epd-api.md)) for LineageOS 18.1 on the Hisense A6L. Code:
`epd/service/`, `epd/rootdir/`, `epd/sepolicy/`, `epd/overlay/`,
`epd/epd.mk`, `epd/BoardConfigEpd.mk`, framework hooks in `epd/patches/`
([patches.md](patches.md)).

Sources: clean-room specs 01 (`01-com.hmct.epd.md`), 02
(`02-epd-service.md`, including the product decisions of its section 9) and
05 (`05-per-app-eink.md`, per-app behaviour, section 8 below), and the code
in this repository. Decisions on points the specs left open are
numbered in [`QUESTIONS.md`](../QUESTIONS.md); they are referred to as "Q12"
etc.

Terms: "LCD" = display 0, the 1080x2340 front panel. "E-ink" = display 1,
the 720x1440 rear panel (framebuffer `fb1`, an external display of the stock
HWC). "Display type" and "refresh mode" are the values of
[epd-api.md](epd-api.md#4-public-constants-50).

## 1. Process model

On the stock Android 9 firmware the E-ink service lived inside
system_server. LineageOS 18.1 has no such service, so this project runs it
in an app process.

| Item | Value |
|---|---|
| Module / APK | `A6lEinkService`, installed to `/system_ext/priv-app/A6lEinkService/A6lEinkService.apk` |
| Package | `org.a6leink.epd` |
| Signing / uid | platform certificate, `android:sharedUserId="android.uid.system"` (SELinux domain `system_app`) |
| Start | `android:persistent="true"`, `coreApp`, `directBootAware`: started by the activity manager during boot, before the first unlock, and restarted if it dies |
| Storage | `android:defaultToDeviceProtectedStorage="true"` |
| Registration | `Application.onCreate` registers the binder under the name `epd` (`ServiceManager.addService`) before any other work. Only the process of user 0 hosts the service; settings pages opened by another user do not create a second one |
| Threading | One handler thread (`A6lEpd`, display priority) does all hardware, settings and broadcast work in posting order. Binder calls only take one state lock and post work; nothing on a binder thread sleeps or waits for the HWC |
| Client library | `com.hmct.epd` from the boot class path (`libs:`, never bundled) |
| Build flags | dexpreopt and R8 off, because system_server loads the key handler class from the APK by name |

Everything else that needs the service (the key handler in system_server,
the keyguard hook in SystemUI) looks it up on each use and tolerates its
absence.

## 2. Boot timing

| When | What happens | Who |
|---|---|---|
| `on boot` | ownership and modes of the E-ink nodes | vendor init rc of the device tree |
| app start (before boot completes) | `epd` registered; forced size of display 0 cleared and saved user settings restored (both outlive a reboot taken on the E-ink); per-app modes loaded; display type 0 published. **No `fb1` node is written** | service |
| `sys.boot_completed=1` | init writes `1` to `/sys/class/graphics/fb1/epd_connect` (connects the E-ink as HWC display 1) and `1` to `/sys/ctp1/ctp_func/tpenable` (rear touch on); `/kdebuginfo/edpd` is created (mode 0777, owner system) | device tree rc files |
| first of `LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED` + **5000 ms** | **"E-ink ready"** (see below) | service |

Rule: **nothing may write an `fb1/epd_*` node before `epd_connect` has been
written.** Doing so crashes the stock HWC (it has no external display yet),
which boot-loops the device; this was observed on a device. Until "E-ink
ready" the service records every request (display type, refresh mode,
tuning) in memory only. The 5 s delay gives the HWC time to bring the
external display up.

Details:

- The delay starts at the first boot broadcast seen (runtime receiver or the
  manifest receiver `BootReceiver`); later ones are ignored.
- If the process starts while `sys.boot_completed` is already `1` (a restart
  after boot), the delay starts at process start.
- If the delay ends while `sys.boot_completed` is still not `1`, the service
  waits another 5 s and checks again.

At "E-ink ready" the service, once:

1. for display type 4 applies the E-ink-only set-up and reconnects the
   E-ink; for any other type switches the rear touch off (init switched it
   on);
2. writes tuning values that differ from the driver defaults (contrast 0,
   black 30, white 20);
3. writes the current refresh mode;
4. shows the status screen for the current type (otherwise the E-ink would
   keep showing the mirror after boot).

## 3. Display types and transitions

### 3.1 Types used on LineageOS 18.1

| Type | Meaning | Hardware |
|---|---|---|
| -1 | screen off | nothing is changed; an applied E-ink-only set-up is kept so that the next screen-on decides. The stock HWC blanks the E-ink by itself. Status screen "Sleeping" |
| 0 | LCD only | LCD normal, front touch on, rear touch off. Status screen "Using the LCD" |
| 2 | E-ink mirror (LCD stays on) | leave E-ink only if applied, then reconnect the E-ink |
| 4 | E-ink only ("e-paper") | E-ink-only set-up if not applied, then reconnect the E-ink |
| 1, 3, 5 | with presentation | not available: bit 0 is always dropped (1 becomes 0, 3 becomes 2, 5 becomes 4) |

The E-ink is not an independent display in this layout: the stock HWC
mirrors display 0 onto it. "E-ink only" therefore means that display 0 keeps
rendering while the LCD is dark and untouchable, and display 0 is resized so
that the mirror fills the E-ink.

### 3.2 Triggers

| Trigger | Resulting type |
|---|---|
| process start | 0 |
| `ACTION_SCREEN_OFF`, `onScreenChanged(false)` | -1 |
| `ACTION_SCREEN_ON`, `onScreenChanged(true)` | see 3.4 |
| `setDisplayType(type, ...)` from any client | `type` after restriction |
| E-ink key, power button | section 6 |
| fingerprint unlock with a per-finger choice | 0 or 4 (section 7) |
| stock hall-sensor service, if installed | `setDisplayType(0)` / `setWakeupSourceType(1, 4)` |

Restriction of a requested type: negative gives -1; bit 0 is dropped; types
other than 0, 2, 4 are ignored and logged; if the E-ink is disabled
(`setDisplayEnabled(1, false)`) types 2 and 4 become 0; if the LCD is
disabled, 0 becomes 4.

### 3.3 What a change does

A change to the type already current is a no-op. Otherwise, in order:

1. remember the old type, record the new one; a non-off type also becomes
   the "last screen-on type";
2. after "E-ink ready", and unless the change came from a screen-on (which
   does its own hardware step), apply the hardware for the new type (3.1);
3. after "E-ink ready", update the status screen (section 9);
4. publish the type: property `sys.sysctl.display_type`,
   `Settings.System current_display_type` (current user), broadcast
   `com.hmct.action.displaychanged` with int extra `display_type` to all
   users (no permission, `FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT`);
5. refresh mode: for 2 or 4 re-evaluate for the foreground app (section 8);
   for 0 set mode 0; for -1 keep the mode and clear the camera/video locks.

The property, setting, broadcast name and extra are kept identical for
compatibility with stock apps.

### 3.4 Wake-up display

The service keeps a wake-up source (initially 1), a lock flag and a
"wake-up display" (0, 4 or none).

- `setWakeupSourceType(source, display, ...)` is ignored while locked;
  otherwise it records `source`, and for `source` 1 with `display` 0 or 4 it
  records the wake-up display.
- At screen-on the target is, in this order: the recorded wake-up display;
  the current type if the screen-on is redundant (type not -1); the LCD if
  `a6l_epd_default_wake` is 1; otherwise the last screen-on type. The
  wake-up display is then cleared, the source set to -1 and the lock
  cleared. A screen-off also clears the lock.

Who records a wake-up display before waking the device: the E-ink key (4),
the power button (0, unless `a6l_epd_power_key_wake` is 1), the keyguard on a
fingerprint unlock with a per-finger choice (0 or 4).

## 4. Hardware applied in each state

All node writes go through the init property bridge (section 5) because
`system_app` may not write these nodes on 18.1.

### 4.1 Entering E-ink only (type 4)

Applied once when entering type 4 while not applied. The service tracks
"E-ink only applied" separately from the display type, because screen-off
and wake-ups happen in between and every way back to the LCD must undo the
same things.

| Step | Action | Why |
|---|---|---|
| 1 | force display 0 to **1080 x 2160** (`IWindowManager.setForcedDisplaySize`) | 2:1 like the E-ink, so the mirror fills it and rear touch coordinates line up |
| 2 | front touch off (`/sys/ctp/ctp_func/tpenable` = 0) | front and rear touch both feed display 0 |
| 3 | rear touch on (`/sys/ctp1/ctp_func/tpenable` = 1) | |
| 4 | save `screen_brightness_mode` into `a6l_epd_saved_brightness_mode` if nothing is saved (-1), then set it to manual | automatic brightness would keep rewriting the backlight |
| 5 | observe `screen_brightness` and `screen_brightness_float` (all users); on any change switch the backlight off again | the power manager rewrites the backlight on each change |
| 6 | LCD backlight off (`/sys/class/leds/lcd-backlight/brightness` = 0) | the LCD panel stays powered; only the backlight is dark |

Then (for every entry into 4) the E-ink is reconnected: `0` then `1` to
`/sys/class/graphics/fb1/epd_connect`. A screen-off makes the stock HWC
blank the E-ink and a screen-on does not unblank it; the reconnect does.

### 4.2 Leaving E-ink only (to 0 or 2)

1. mark not applied; cancel pending screen-on repetitions; stop the
   brightness observer;
2. clear the forced size of display 0;
3. front touch on, rear touch off (on the LCD a hand holding the E-ink side
   would otherwise steer display 0);
4. restore saved settings: `a6l_epd_saved_brightness_mode` back to
   `screen_brightness_mode` (then -1); for upgrades from older builds also
   `a6l_epd_saved_wake_gesture` back to `Settings.Secure
   wake_gesture_enabled` (then -1);
5. bring the backlight back: set a temporary brightness
   (`DisplayManager.setTemporaryBrightness`) to the current
   `screen_brightness_float` minus 0.01 (not below the minimum) and clear it
   100 ms later. The display power controller would otherwise not rewrite
   the backlight.

Steps 2 and 4 also run once at process start.

### 4.3 After a screen-on into type 4

A screen-on undoes two things: the front touch driver re-enables itself when
the LCD powers on, and the power manager ramps the backlight up. The service
therefore switches the front touch and the backlight off again immediately
and after **300, 800, 1500 and 3000 ms**, as long as E-ink only is still
applied. A new screen-on or leaving E-ink only cancels pending repetitions.
A client `setDisplayType` with a screen-on type that arrives while the device
is going to sleep (no longer interactive, screen-off broadcast not yet
received) is ignored, so E-ink only goes to -1 directly (spec 02 section 10
item 2); every `setDisplayType` is logged with the caller's uid and pid.
Before that, every screen-on into type 4 applies the whole E-ink-only set-up
of 4.1 again (forced size, touches, brightness mode, observer, backlight) and
logs "Entering E-ink only (screen on...)", whatever woke the device (E-ink
key, swapped keys, fingerprint) and even if it is still marked as applied
from before the screen-off (device test 2026-10-03: an E-ink key wake after a
sleep from E-ink only left the LCD lit).

### 4.4 Node reference

| Node | Value written | Through |
|---|---|---|
| `/sys/class/graphics/fb1/epd_connect` | `1` at boot (init); `0` then `1` to reconnect/unblank | `sys.a6l.epd.reconnect` |
| `/sys/class/graphics/fb1/epd_display_mode` | refresh mode, decimal | `sys.a6l.epd.mode` |
| `/sys/class/graphics/fb1/epd_commit_bitmap` | `1`: show the bitmap file | `sys.a6l.epd.commit_bitmap` |
| `/sys/class/graphics/fb1/epd_force_clear` | `1`: full ghost-clearing refresh | `sys.a6l.epd.force_clear` |
| `/sys/class/graphics/fb1/epd_contrast` | 0..100 (default 0) | `sys.a6l.epd.contrast` |
| `/sys/class/graphics/fb1/epd_black_threshold` | 0..100 (default 30) | `sys.a6l.epd.black` |
| `/sys/class/graphics/fb1/epd_white_threshold` | 0..100 (default 20) | `sys.a6l.epd.white` |
| `/sys/ctp1/ctp_func/tpenable` | rear touch `0`/`1` | `sys.a6l.epd.ext_touch` |
| `/sys/ctp/ctp_func/tpenable` | front touch `0`/`1` | `sys.a6l.epd.front_touch` |
| `/sys/class/leds/lcd-backlight/brightness` | `0` | `sys.a6l.epd.lcd_off` |

Not written by this service: `/sys/ctp1/ctp_func/gesture` (rear touch
gestures, not wanted), `/sys/class/leds/epd-backlight/brightness` (E-ink
front light), `/sys/debug_control/mirror/state`.

## 5. The init property bridge

The service sets a property under `sys.a6l.epd.`; the rc file
`/system/etc/init/init.a6l.epd.rc` (source `epd/rootdir/etc/init.a6l.epd.rc`)
has one `on property:<name>=*` trigger per property that writes the node.

| Property | Kind | init writes |
|---|---|---|
| `sys.a6l.epd.ext_touch` | value `0`/`1` | `${value}` to `/sys/ctp1/ctp_func/tpenable` |
| `sys.a6l.epd.front_touch` | value `0`/`1` | `${value}` to `/sys/ctp/ctp_func/tpenable` |
| `sys.a6l.epd.reconnect` | counter | `0` then `1` to `fb1/epd_connect` |
| `sys.a6l.epd.lcd_off` | counter | `0` to `/sys/class/leds/lcd-backlight/brightness` |
| `sys.a6l.epd.mode` | value (refresh mode) | `${value}` to `fb1/epd_display_mode` |
| `sys.a6l.epd.commit_bitmap` | counter | `1` to `fb1/epd_commit_bitmap` |
| `sys.a6l.epd.force_clear` | counter | `1` to `fb1/epd_force_clear` |
| `sys.a6l.epd.contrast` / `.black` / `.white` | value 0..100 | `${value}` to `fb1/epd_contrast` / `epd_black_threshold` / `epd_white_threshold` |

- **Value** properties carry the value to write.
- **Counter** properties always write the same value; the service sets a new,
  increasing number for each request. The counter is seeded from
  `elapsedRealtime`, so it also differs after a process restart.
- On 18.1, init runs a trigger on every successful set, also when the value
  is unchanged, so repeated value requests (the front-touch repeats of 4.3)
  reach the node (Q10, from the AOSP init sources; worth a device check).
- The property names are this project's own; only its own rc file reads
  them.

## 6. The E-ink key and the buttons

### 6.1 Mechanism

The rear button is reported by `gpio-keys` with Linux scan code **616**
(`KEY_LEFT_UP`, "EPD_PWR key" in the kernel log). It has no Android key code
in the 18.1 key layout, so it is matched by scan code.

The handler `org.a6leink.epd.EpdKeyHandler` lives in the service APK and is
loaded into system_server by the LineageOS `DeviceKeyHandler` mechanism,
configured by the lineage-sdk overlay in `epd/overlay`:

| Resource | Entries (matched by index) |
|---|---|
| `config_deviceKeyHandlerLibs` | `/system/priv-app/LineageParts/LineageParts.apk`, `/system_ext/priv-app/A6lEinkService/A6lEinkService.apk` |
| `config_deviceKeyHandlerClasses` | `org.lineageos.lineageparts.gestures.KeyHandler`, `org.a6leink.epd.EpdKeyHandler` |

The first pair keeps the lineage-sdk default. The handler sees every key in
the policy's pre-queue stage, also with the screen off; it never blocks
(binder calls, wake-ups and settings reads run on its own handler thread),
looks `epd` up on each use and does nothing if the service is absent.

### 6.2 Button roles

Default (spec 02 section 9.1), the same on both screens:

| | Screen on | Screen off |
|---|---|---|
| Power button | sleep (left to the framework) | wake to the **LCD** |
| E-ink key | switch: on type 4 go to 0, otherwise go to 4 | wake to the **E-ink** (type 4) |

How:

- E-ink key: down and up are both consumed; the action runs on the up event
  with repeat count 0. Screen off: `setWakeupSourceType(1, 4, false)` then
  `PowerManager.wakeUp` (reason "wake key"). Screen on: `getDisplayType()`;
  4 gives `setDisplayType(0)`, anything else `setDisplayType(4)`. Every press
  also sends `com.hmct.intent.EINK_KEY_PRESSED` to the current user.
- Power button, down event while the screen is off: records
  `setWakeupSourceType(1, 0, false)` before the screen-on it causes, unless
  `a6l_epd_power_key_wake` is 1. The event always passes through.
- Double and long presses of the E-ink key are not detected.

Own settings without UI (`adb shell settings put system <key> <value>`):

| Key | Default | Meaning |
|---|---|---|
| `a6l_epd_power_key_wake` | 0 | 0: power button wakes to the LCD; 1: it names no screen (rule of 3.4 applies) |
| `a6l_epd_default_wake` | 0 | wake-ups that name no screen (lift, double tap, USB, alarms): 0 last used screen, 1 LCD |
| `a6l_epd_eink_key_action` | 0 | E-ink key on type 4 without swap: 0 back to the LCD, 1 sleep (the stock behaviour) |

### 6.3 Swap on the E-ink

`Settings.System a6l_epd_swap_keys_on_eink` (default 0), shown as "Swap
buttons on the E-ink" under "Buttons" on the "E-ink" settings page. When it
is 1, the screen is on and the published display type is **4**:

- the power button does not sleep: its whole press (down, repeats and up) is
  consumed and the release switches back to the LCD (`setDisplayType(0)`;
  nothing happens if the press was cancelled). The long-press power menu is
  therefore not available on the E-ink;
- the E-ink key puts the phone to sleep (and still sends
  `EINK_KEY_PRESSED`).

"E-ink in use" is read from the property `sys.sysctl.display_type`, so the
key path makes no binder call. The setting is cached by a content observer
(all users), re-read on user switch; registration is retried every 5 s until
the settings provider is up. The mirror (type 2) does not count as "in use",
because the LCD is on there too.

## 7. Per-finger unlock screen

Setting `fp_unlock_screen`, `Settings.System`, per user: a JSON object whose
keys are fingerprint ids (the biometric id of the enrolled finger, decimal
string) and whose values are `"0"` (LCD) or `"1"` (E-ink). A finger without
an entry, or any other value, means "no choice". The format is kept
identical for compatibility with the stock Settings app, which wrote it.

The keyguard hook (frameworks/base patch 0002, [patches.md](patches.md))
runs on each successful fingerprint authentication, before the unlock is
handled. For a finger with a choice (display type 0 for `"0"`, 4 for `"1"`):

- screen off (the unlock wakes the device): `setWakeupSourceType(1, type,
  type == 0)`, so the screen-on goes to that panel;
- screen on (unlock over a showing keyguard): `setDisplayType(type, false)`.

Errors (malformed JSON, service absent) are logged and the unlock proceeds.

Settings page: **Settings > Security > Fingerprint unlock screen**
(`FingerUnlockSettingsActivity`). It lists the enrolled fingerprints of the
user by name ("Finger N" if unnamed) and offers "Keep current screen",
"Front (LCD)" and "Back (E-ink)" per finger; "Keep current screen" removes
the entry.

## 8. Refresh mode and per-app behaviour

Spec 05 section 6, with the product decisions recorded in
[`QUESTIONS.md`](../QUESTIONS.md) (Q53 to Q73; device-test fixes Q74 to Q77). Code: `EpdService`
(evaluation), `ForegroundTracker`, `AppRules`, `CameraWatcher`,
`PlaybackWatcher`, `SlideMonitor`, `EinkOptionsProvider`,
`EinkModeTileService`.

### 8.1 Refresh mode basics

- The current refresh mode is held in memory (starts at 0). A new value is
  written to `fb1/epd_display_mode` only after "E-ink ready". Setting the same
  value again does nothing. `setEpdDisplayMode` accepts 0..8; its value holds
  until the next evaluation.
- Named values (`EpdSettings`; no other place in the service writes a number):

  | Name | Value | Meaning |
  |---|---|---|
  | `MODE_DEFAULT_EINK` = `MODE_SMOOTH` | 8 | default on the E-ink, "smooth" (no per-app entry); with the stock HWC the steady everyday mode (1 and 2 flash). Stock used 6 |
  | `MODE_CLEAR` | 3 | "clear" per-app entries, and the E-ink lock screen when `a6l_epd_keyguard_clear` is 1. **Confirmed to take effect on the panel (device test 2026-10-03; spec 05 section 8).** The settings app and the tile read the value from the service, so only this constant changes |
  | `MODE_MIRROR` | 0 | LCD in use |
  | `MODE_APP_MIN`..`MODE_MAX` | 1..8 | range accepted by `addAppMode` (stock 1..7; 8 so that the default can be stored explicitly) |

- `forceClear()` writes `fb1/epd_force_clear` after "E-ink ready".

### 8.2 Front app tracking

Nobody calls `begigResumeActivity` / `notifyResumedPackage` on 18.1, so the
service observes the front itself (spec 05 section 6.1):

- A task-stack listener (`onTaskStackChanged`, `onTaskMovedToFront`,
  `onTaskFocusChanged`, `onTaskRemoved`, `onTaskDisplayChanged`, PiP
  pinned/unpinned, docked-stack dismissal, back on a task root). Callbacks only
  schedule a query: coalesced for 60 ms, plus two late re-queries 350 ms and
  1200 ms after the last callback (returning to the launcher, recents and
  activities started from notifications finish after their callbacks).
- A query reads the **focused root task's top activity** (package, class and
  user; `getFocusedStackInfo`, so in split screen the focused side), ignoring
  picture-in-picture; fallback: the topmost non-PiP task.
- While the E-ink is in use (types 2 and 4) a safety-net poll queries every
  2 s. A failed listener registration is retried every 2 s.
- The front is also re-read at every screen-on, at `ACTION_USER_PRESENT` and
  on every change into an E-ink type. `begigResumeActivity` and
  `notifyResumedPackage` are accepted as hints that trigger a query.
- After a restart of the service process, the first query, the camera
  listener (which reports the cameras already open) and a read of the active
  audio playback restore the state.
- `getTopPackage()` returns the observed front package (keep-mode dialogs
  excluded, see 8.4).

### 8.3 Evaluation

Runs only while the type is 2 or 4 (on 0 the mode is 0; with the screens off
it is left). The first that applies wins:

| # | Situation | Mode | Reason shown in `dumpsys` |
|---|---|---|---|
| 1 | Camera lock: a camera opened by the front app (or by the app a helper follows; an opener that is unknown counts), or a client `lockEpdMode(0, true)`; never while a home app is in front | smooth | `camera lock (...)` |
| 2 | Keyguard locked while the E-ink is in use (E-ink lock screen) | default (8), or clear if `a6l_epd_keyguard_clear` = 1 | `rule: E-ink lock screen (default mode)` / `(clear mode)` |
| 3 | Video lock: the front app's uid plays video-like audio, or a client `lockEpdMode(1, true)` | smooth | `video lock (...)` |
| 4 | Slide one screen (experimental): a finger is dragging in an app with the flag | smooth | `slide one screen: finger dragging` |
| 5 | Front package `com.hmct.einklauncher` (stock rule) | clear | `rule: E-ink launcher` |
| 6 | Built-in video activities `com.android.gallery3d.app.MovieActivity`, `...TrimVideo` (AOSP Gallery2) | smooth | `rule: built-in video activity` |
| 7 | Per-app entry: an entry equal to the activity class (overrides the package's entry), else the exact package, else the longest entry that contains the package or is contained in it | the entry | `per-app entry 'pkg'`, `activity entry '...'`, `substring entry '...'` |
| 8 | Helper app (no launcher activity, not home) without a match: the entry of the app that opened it | that entry | `... (helper app, follows pkg)` |
| 9 | Nothing matched | 8 | `default` |

The camera lock comes before the lock screen because the camera can be used
over the lock screen; then the camera app is the front app.

When it runs: on every change of the front **package**; on an activity change
inside the same package only if the old or new activity has an activity rule
(6 or an activity-class entry); at every screen-on and type change into 2/4;
at `ACTION_USER_PRESENT` and whenever a query finds the keyguard state
changed; on every camera, playback, lock, option or slide change; after
`addAppMode` / `removeAppMode` / `updateEpdMode`.

### 8.4 Fixed rules

- **Keep-mode dialogs**: `com.android.permissioncontroller.permission.ui.GrantPermissionsActivity`,
  `com.android.internal.app.ChooserActivity` (share sheet) and
  `com.android.internal.app.ResolverActivity` ("open with"). Nothing changes
  while one is on top, and it is not recorded as the front app.
- **Helper apps** follow the app that opened them: a package without a
  launcher activity that is not the home app (for example the package
  installer, permission and system UI screens, `android`) uses, when it has no
  match of its own, the entry of the previous front app (or of that app's host
  if the previous one was a helper too). Its own entry wins if it has one.
- **Home rule**: the camera lock never applies while the default home app (or
  `com.hmct.einklauncher`, `com.hmct.vision`) is in front.
- **Video activities**: rule 6 above.

### 8.5 Automatic switches (both on by default)

- **Camera** (`CameraWatcher`): `CameraManager.AvailabilityCallback`; a camera
  id is open while unavailable; the opener's package comes from the hidden
  `onCameraOpened` callback (permission `CAMERA_OPEN_CLOSE_LISTENER`); the
  listener is registered at "E-ink ready" (cameraserver is up by then). The lock
  is released when the camera closes, and at screen-off: cameras open at
  screen-off no longer lock until closed and opened again. Client locks are
  also cleared at screen-off (stock).
- **Video** (`PlaybackWatcher`): `AudioManager.AudioPlaybackCallback`; the
  service runs as uid system, which AudioService treats as holding
  `MODIFY_AUDIO_ROUTING`, so the configurations carry the player's uid.
  Video-like = state started and content type "movie", or a media / game /
  unknown usage whose content type is not speech, music or sonification. The
  lock lasts while the front app's uid plays; it ends when playback stops or
  the app leaves the front.
- Switches: options `auto_camera` and `auto_video` in the service's own
  storage (10.1), set from the settings app. Client locks
  (`lockEpdMode`) work whatever the switches.

### 8.6 E-ink lock screen

At every screen-on the service reads `KeyguardManager.isKeyguardLocked()`
before applying the type, so a wake-up into the E-ink with the keyguard
shows the lock screen in the lock-screen mode: the **default mode (8)**, or
the clear mode if `Settings.System a6l_epd_keyguard_clear` (current user) is 1
(switch "Clear mode on the lock screen" under "Lock screen" on the "E-ink"
page). The default mode is the default because the clear mode made the lock
screen take more than 5 s to appear after a wake (spec 02 section 10 item 4a).
The app's mode comes back at `ACTION_USER_PRESENT` (or the next query that
sees the keyguard gone).

About 1.5 s after a wake into the E-ink (types 2 and 4) with the keyguard
showing, the service calls `PowerManager.userActivity` once (if the device
is still awake and on the E-ink), so the keyguard's short screen-off timer
restarts once the E-ink has started drawing (item 4c). Permission
`DEVICE_POWER` (signature; uid system passes anyway).

### 8.6a Wake timing log

Each wake into the E-ink is logged step by step (spec 02 section 10 item
4b), tag `A6lEpdWake`, one line per step, `+<ms> <step>` with the
milliseconds since the service saw the screen-on broadcast: screen-on seen
(target type, keyguard), display type set (with the refresh mode and its
reason), refresh mode written to the bridge (only if it changed), E-ink-only
setup applied, reconnect requested, each delayed re-application (300, 800,
1500, 3000 ms) and the user activity. Tracking stops 6 s after the screen-on
or at the next screen-off. The first frame on the panel cannot be observed by
the service (the HWC drives the E-ink), so it is not logged. `dumpsys epd`
shows the number of tracked wakes and the last step.

Repaint requests (spec 02 section 10 item 5): the first panel update after a
reconnect is blank and the content otherwise only arrives with
SurfaceFlinger's next frame (5 to 20 s on a still screen). After every
reconnect for a wake or a switch into type 2 or 4 (and at "E-ink ready" in
type 4), the service asks SurfaceFlinger for a full repaint at +1.2 s,
+2.5 s and +4 s: binder transaction 1004 with the interface token
`android.ui.ISurfaceComposer` on the service `SurfaceFlinger` (allowed for
uid system). Pending requests are cancelled at a screen-off and when the LCD
is entered. Each request is a line in the `A6lEpdWake` trace ("repaint n/3
requested"; a plain line on that tag outside a tracked wake). A missing
service or a failed transaction is logged once (tag `A6lEpdRepaint`);
`dumpsys epd` shows the request and failure counts.

```sh
adb logcat -s A6lEpdWake A6lEpdRepaint
```

### 8.7 Slide one screen (EXPERIMENTAL, off for every app by default)

Stock suppressed the app's drawing during a drag (a framework feature 18.1
lacks). The approximation: for an app with the flag, while the E-ink is in
use and not locked, a gesture monitor on display 0 (`InputManager`
`monitorGestureInput`, permission `MONITOR_INPUT`; touches still reach the
app) sees the touches. When a finger moves more than the touch slop, the
mode becomes smooth; 600 ms after the last finger is lifted (a new touch in
that time continues the drag) the app's mode comes back; no extra refresh
follows (spec 05 section 8 decision c: a fast mode only while the finger
drags). It is useful for apps set to clear; for an app that is already smooth
it changes nothing. The monitor exists only while needed. If the effect is not
good enough, a framework change is to be considered separately.

### 8.8 Quick Settings tile

`EinkModeTileService`, label "E-ink: clear/smooth" (Japanese
"E-ink: くっきり/なめらか"). It shows the stored choice of the front app
("E-ink: clear" / "E-ink: smooth", the app's name as subtitle) and toggles it:
smooth → `addAppMode(pkg, MODE_CLEAR)`, clear → `removeAppMode(pkg)`; the
service re-applies the mode at once and the change is permanent (same map as
the settings app). Unavailable while the E-ink is not in use, for the home
app and for this project's own apps. It talks to the `epd` binder, so it also
works for a secondary user.

### 8.9 Options provider (for the settings app)

`EinkOptionsProvider`, authority `org.a6leink.epd.options`, single-user,
guarded by the signature permission `org.a6leink.epd.permission.EINK_OPTIONS`
(checked in `call`). Methods: `get_config` (ints `clear_mode`,
`smooth_mode`, `min_mode`, `max_mode`; booleans `auto_camera`,
`auto_video`; string array `slide`), `set_slide` (arg package, boolean extra
`on`), `set_option` (arg option name, boolean extra `on`). The per-app
options are not encoded into the `getAllAppMode` value space (spec 05
section 6.4).

### 8.10 Package removal

On `ACTION_PACKAGE_FULLY_REMOVED`, if the package is installed for no user
any more, its exact-package entry and slide flag are removed.

## 9. The status screen

Stage 1 of the E-ink "sleep screen": the header. Stage 2 (widgets and
notifications below the header of the sleeping screen) is described in
[sleep-screen.md](sleep-screen.md). Purpose: the E-ink never keeps the last
page or the live mirror of the phone's content while it is not in use; the
service paints its own picture there.

### 9.1 When

| Display type | Screen |
|---|---|
| -1 | "Sleeping", redrawn every minute if the clock is enabled |
| 0 | "Using the LCD", drawn once per entry into the LCD state; keeps the time of the switch |
| 2, 4 | none: pending draws and the minute alarm are cancelled; the E-ink shows the mirror |

Only after "E-ink ready"; at "E-ink ready" the screen for the current type is
drawn at once.

### 9.2 What is shown

Canvas 720 x 1440, white background, black anti-aliased text in the current
language (English and Japanese strings), each line shrunk to fit its width.
Everything is in the **top 360 px**. On the sleeping screen, widgets and
notifications may follow below it ([sleep-screen.md](sleep-screen.md#2-layout));
without them the rest stays white, and the "Using the LCD" screen never has
them.

- Sleeping, with clock (`a6l_epd_sleep_clock` = 1, default): a 360 x 360
  square on the left with
  - an analog clock (`a6l_epd_sleep_clock_style` = 0, default): circle, 12
    hour marks (heavier at 12, 3, 6, 9), hour and minute hands, centre dot,
    no second hand; or
  - a digital clock (style 1): hour and minute, bold, centred, 24 h or 12 h
    following the user's setting, locale pattern;

  and to the right three lines at about 33 %, 58 % and 83 % of the band:
  "Sleeping" (bold); weekday and date in the locale's best pattern (long
  form, e.g. "Friday, October 2"; Japanese "10月2日(金)"); "Battery N%" or,
  while charging or full, "Charging N%" (omitted if unknown).
- Sleeping, without clock: "Sleeping", bold, one line.
- LCD in use: "Using the LCD" (bold) and "Since <time of the switch>".

### 9.3 Bitmap file and commit

| Item | Value |
|---|---|
| File | `/kdebuginfo/edpd/bitmap.raw` |
| Format | raw pixels, no header: 720 x 1440, row-major from the top-left, **RGB565 little-endian** (2 bytes per pixel) |
| Size | 2,073,600 bytes |
| Writing | the service (`system_app`) truncates and rewrites the whole file |
| Commit | `1` to `/sys/class/graphics/fb1/epd_commit_bitmap` via `sys.a6l.epd.commit_bitmap`; the HWC reads the file and updates the panel |

Path, format and commit node are fixed by the stock vendor HWC (compatibility
facts). If the file cannot be written, the commit is skipped and the error is
shown in `dumpsys epd`.

**Blanking first.** The stock HWC shows a committed bitmap only while the
E-ink display is blanked. After a screen-off it already is. Before drawing
the "Using the LCD" screen the service powers display 1 off through
SurfaceFlinger (`SurfaceControl.setDisplayPowerMode(token, POWER_MODE_OFF)`
on the physical display that is not the internal one), which makes the HWC
blank the E-ink. The reconnect on the way back to type 2 or 4 undoes it.

### 9.4 Refresh policy

- Each draw waits **500 ms** after entering the state (lets the HWC finish
  blanking). A partial wake lock covers the wait and the draw (500 ms +
  1500 ms), so a screen-off cannot suspend the CPU before the HWC has picked
  up the file.
- Sleeping with clock: after each draw an exact `RTC_WAKEUP` alarm is set for
  the next minute boundary; it redraws while still sleeping and re-arms.
  Leaving the state cancels the alarm and any pending draw.
- Without clock: once per screen-off.
- Re-entering the state already shown does not redraw.
- Settings changes take effect at the next draw.

### 9.5 Settings page

**Settings > Display > E-ink** (`StatusScreenSettingsActivity`): "Status
screen" with "Clock on the sleeping screen" (`a6l_epd_sleep_clock`) and
"Clock style" Analog / Digital (`a6l_epd_sleep_clock_style`), the
sleeping screen's "Widgets" and "Notifications" (see
[sleep-screen.md](sleep-screen.md#6-settings-page)), and "Buttons" with
"Swap buttons on the E-ink" (`a6l_epd_swap_keys_on_eink`). The page
writes the settings of the user who opened it. A fuller E-ink settings app
is described in [eink-settings.md](eink-settings.md).

## 10. Persistence and settings

### 10.1 Per-app refresh modes

File `epd_app_modes.xml` in the app's **device-protected** files directory
(readable before the first unlock), written with `AtomicFile` after each
change. It also holds the service's own per-app options (spec 05 section
6.4). Format, private to this service:

```xml
<app-modes>
  <app package="com.example.reader" mode="3"/>
  <slide package="com.example.reader"/>
  <option name="auto_video" value="0"/>
</app-modes>
```

`app`: per-app refresh mode (keys may also be activity class names or
stock-style substrings); `slide`: slide one screen on; `option`: the
automatic switches `auto_camera` / `auto_video` (absent = on). Files of
earlier builds (only `app`) load unchanged. A missing or unreadable file
means empty data. Apps use `getAllAppMode()` and the options provider, not
the file. One map for the whole device (not per user).

### 10.2 Settings keys

| Key | Table, user | Default | Written by | Meaning |
|---|---|---|---|---|
| `current_display_type` | System, current | - | service | current display type (compatibility) |
| `fp_unlock_screen` | System, per user | absent | finger page | section 7 (compatibility) |
| `a6l_epd_sleep_clock` | System, current | 1 | E-ink page | clock on the sleeping screen |
| `a6l_epd_sleep_clock_style` | System, current | 0 | E-ink page | 0 analog, 1 digital |
| `a6l_epd_swap_keys_on_eink` | System, current | 0 | E-ink page | section 6.3 |
| `a6l_epd_power_key_wake` | System, current | 0 | user (no UI) | section 6.2 |
| `a6l_epd_default_wake` | System, current | 0 | user (no UI) | section 6.2 |
| `a6l_epd_eink_key_action` | System, current | 0 | user (no UI) | section 6.2 |
| `a6l_epd_keyguard_clear` | System, current | 0 | E-ink page | 1 = clear mode on the E-ink lock screen, 0 = default mode (section 8.6) |
| `a6l_epd_saved_brightness_mode` | System, current | -1 | service | saved `screen_brightness_mode` while E-ink only is applied |
| `a6l_epd_saved_wake_gesture` | System, current | -1 | service (restore only) | legacy, from older builds |
| `screen_brightness_mode` | System | - | service | set to manual while E-ink only |
| `accelerometer_rotation_restore`, `accelerometer_rotation` | System, current | 0 | `finishBoot` | restore auto-rotate |

### 10.3 Properties

| Property | Meaning |
|---|---|
| `sys.sysctl.display_type` | current display type (compatibility) |
| `persist.sys.epd.contrast` / `.black` / `.white` | tuning 0..100, defaults 0 / 30 / 20, re-applied at "E-ink ready" when not default (stock names, so values survive) |
| `sys.einklauncher.ready` | `1` after `finishBoot` |
| `sys.boot_completed` | read: process-restart detection and the "E-ink ready" guard |
| `sys.a6l.epd.*` | init bridge |
| `ro.hmct.epd.support` | `1`, set by `epd.mk` |

The theme mode, keyguard mode, gesture-unlock value, display-enabled flags,
wake-up state and locks are memory only.

## 11. Permissions and SELinux

### 11.1 Permissions

| Item | Value |
|---|---|
| Binder calls | none required (kept as on stock: stock apps call the service without permissions) |
| `dumpsys epd` | `android.permission.DUMP` |
| Settings pages | exported, guarded by `android.permission.WRITE_SECURE_SETTINGS` (only system apps such as Settings can open them); injected through the `com.android.settings.action.IA_SETTINGS` filter with categories `com.android.settings.category.ia.display` and `...ia.security` |
| Manifest `uses-permission` | `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `WAKE_LOCK`; for section 8: `MANAGE_ACTIVITY_STACKS`, `CAMERA_OPEN_CLOSE_LISTENER`, `MONITOR_INPUT` (signature) and `MODIFY_AUDIO_ROUTING` (signature\|privileged, allowlisted in `epd/permissions/privapp-permissions-a6leink.xml`, installed to `system_ext/etc/permissions`). uid system passes all these checks anyway; they are declared to document the use |
| Own permission | `org.a6leink.epd.permission.EINK_OPTIONS` (signature): options provider (8.9) |
| Quick Settings tile | `BIND_QUICK_SETTINGS_TILE` on the tile service |
| Protected broadcasts | `com.hmct.action.displaychanged`, `com.hmct.intent.EINK_KEY_PRESSED` (sent from system_server by the key handler; unprotected it caused a "Sending non-protected broadcast" WTF) |

### 11.2 System-side policy (this repository)

`epd/sepolicy/private/`, added with `BOARD_PLAT_PRIVATE_SEPOLICY_DIR`:

| File | Content |
|---|---|
| `a6l_epd.te` | type `a6l_epd_service` (`app_api_service`, `service_manager_type`: any app may find it); `add_service(system_app, a6l_epd_service)`; `system_server` may `find` it (key handler); `system_internal_prop(a6l_epd_prop)`; `set_prop(system_app, a6l_epd_prop)`; `system_app` may `find` `cameraserver_service` (camera lock, 8.5) |
| `service_contexts` | `epd` -> `a6l_epd_service` |
| `property_contexts` | `sys.a6l.epd.` -> `a6l_epd_prop`; `ro.hmct.epd.support` -> `exported_system_prop` (readable by every app) |

`sys.sysctl.display_type` keeps the default `system_prop` label (Q33).

### 11.3 Vendor-side policy (device tree)

Relied on, provided by the device tree:

| Object | Label | Allowed |
|---|---|---|
| `/sys/class/graphics/fb1/epd_*` | `sysfs_graphics` | init writes; `system_app` may not on 18.1 |
| `/sys/ctp1/ctp_func/*`, `/sys/ctp/ctp_func/*` | `vendor_sysfs_ctp_func` | init writes |
| `/sys/class/leds/lcd-backlight/brightness` | platform default | init writes |
| `/kdebuginfo(/.*)?` | `kdebuginfo_data_file` | `system_app` writes, the HWC reads |

## 12. Building it into a device

1. Put the repository in the source tree, e.g. `vendor/a6l-eink`.
2. Product makefile:
   `$(call inherit-product, vendor/a6l-eink/epd/epd.mk)`.
   `epd.mk` adds the packages `com.hmct.epd`, `A6lEinkService` and
   `A6lEinkSettings`, puts `com.hmct.epd` in `PRODUCT_BOOT_JARS`, copies the
   bridge rc file to `system/etc/init/init.a6l.epd.rc` and the privileged
   permission allowlist to
   `system_ext/etc/permissions/privapp-permissions-a6leink.xml` (without it a
   device with `ro.control_privapp_permissions=enforce` does not boot), adds
   the overlay
   (`PRODUCT_PACKAGE_OVERLAYS`) and sets `ro.hmct.epd.support=1`
   (`PRODUCT_SYSTEM_EXT_PROPERTIES`).
3. `BoardConfig.mk`:
   `include vendor/a6l-eink/epd/BoardConfigEpd.mk`
   (adds `epd/sepolicy/private` to `BOARD_PLAT_PRIVATE_SEPOLICY_DIR`).
4. Apply the frameworks/base patches: `vendor/a6l-eink/epd/patches/apply.sh .`
   (`--check` only verifies). See [patches.md](patches.md).
5. The device tree keeps: the vendor init rc with the E-ink node permissions,
   the writes of `epd_connect` and rear `tpenable` at `sys.boot_completed=1`,
   creation of `/kdebuginfo/edpd`, the vendor SELinux rules above, and the rear
   touch input configuration `ft5x06_ts.idc` with `device.internal = 0` and
   `touch.wake = 0` (otherwise holding the phone by the E-ink side wakes it).

If the device overlays `config_deviceKeyHandlerLibs` / `...Classes` itself,
merge the entries; the two arrays must stay index-aligned.

## 13. Verifying on a device

```sh
adb shell dumpsys epd
```

prints: display type, previous and last screen-on type; "e-ink ready" and
whether it is scheduled; display-enabled flags; refresh mode (with the smooth
and clear values), **why** (mode reason) and the last trigger; top package;
the front package/activity/user/uid with home, helper and the followed host;
a keep-mode dialog on top; keyguard; client locks; the automatic switches;
locking cameras and video uids; slide flags and drag state; the fixed rules;
the foreground tracker, camera, playback and slide monitor state; wake-up
source, lock and wake-up display; theme,
keyguard and gesture values and whether `finishBoot` was called; tuning
values; per-app modes; whether E-ink only is applied and the brightness
observer is active; status screen state (sleeping / lcd / none, "forced",
minute alarm, number of draws, time of the last draw, LCD-since time, last
error); number of direction callbacks. The format is not a contract.

Status screen test hook (does not change the display type; the next real type
change ends it):

```sh
adb shell dumpsys epd status lcd     # draw "Using the LCD"
adb shell dumpsys epd status sleep   # draw "Sleeping"
adb shell dumpsys epd status stop    # stop drawing
```

Other useful checks:

```sh
adb shell getprop sys.sysctl.display_type
adb shell getprop | grep sys.a6l.epd.        # last bridge requests
adb shell settings get system current_display_type
adb shell service call epd 12 i32 4 i32 0    # setDisplayType(4, false)
adb shell service call epd 12 i32 0 i32 0    # back to the LCD
adb shell ls -l /kdebuginfo/edpd/bitmap.raw  # 2073600 bytes after a draw
adb logcat -s A6lEpd A6lEpdEinkOnly A6lEpdBridge A6lEpdStatus A6lEpdKey \
    A6lEpdStore A6lEpdForeground A6lEpdFingerUnlock A6lEpdCamera \
    A6lEpdPlayback A6lEpdSlide A6lEpdTile A6lEpdWake A6lEpdRepaint
```

Per-app checks (section 8), with the E-ink in use, each read from the
"refresh mode", "mode reason" and "front" lines of `dumpsys epd`:

- open an app set to clear: mode 3, `per-app entry 'pkg'`; go home, to
  recents, back from a notification, into split screen: the front line and
  the mode follow within about a second;
- a runtime permission dialog or the share sheet over that app: mode and
  front unchanged ("keep-mode dialog on top");
- the camera app: `camera lock (camera N open by pkg)`, released when it
  closes; a video playing in the front app: `video lock (...)`;
- lock the device and wake into the E-ink: `rule: E-ink lock screen
  (default mode)` (mode 8; mode 3 with the "Clear mode on the lock screen"
  switch) until unlock; `adb logcat -s A6lEpdWake` shows the wake steps;
- the Quick Settings tile flips the front app; `adb logcat -s A6lEpd` shows
  `addAppMode` / `removeAppMode` with the caller's uid;
- `adb shell am crash org.a6leink.epd` (or kill the process): after the
  restart the front, keyguard, camera and playback state are back.

`A6lEpdKey` logs come from system_server. Things to check after a change:
no boot loop (nothing touches `fb1` before "E-ink ready"); the E-ink shows
"Sleeping" after screen-off and "Using the LCD" after switching back; the
E-ink key wakes to the E-ink and switches both ways; the front touch and LCD
backlight stay off after waking to the E-ink.

## 14. Known limits

- **Presentation slots are not implemented.** `addViewOnExternal`,
  `addBitmapOnExternal`, `removeViewOnExternal`, `updateExteralPaper`,
  `addPreViewPage` and `updateShutdownWallPaper` are accepted and ignored.
  No shutdown picture yet; a "Power off" status picture through the same
  bitmap path is a planned low-priority item.
- **No dual-screen tasks**: bit 0 of every type is dropped;
  `getDualScreenTaskState()` is -1.
- **The E-ink is a mirror of display 0**, not an independent display; E-ink
  only darkens and disables the LCD and resizes display 0 to 1080 x 2160.
- **Refresh-mode values at the node**: the `EPD_MODE_*` number is written
  directly to `fb1/epd_display_mode`. 8 is steady, 1/2 flash and 3 is visibly
  clearer with stuttering scrolling (device test 2026-10-03); what 0 and 6 do
  at the node was not established. Mode 0 is written whenever the LCD
  is in use.
- **Per-app behaviour** (section 8), to verify on the device: whether the torch makes a camera unavailable (spec 05 open
  question 7; the torch app is usually not in front, so decision b mostly
  hides it); which video players the audio heuristic catches; how the slide
  approximation looks; a camera that stays open across a screen-off does not
  lock again until reopened.
- **Locks force the smooth mode (8)**, not 6 as on stock (the effect of 6 at
  the node is unknown).
- **Not implemented**: rear-touch gestures (`/sys/ctp1/ctp_func/gesture`,
  not wanted), the call restriction of `setDisplayType`,
  `com.hmct.action.reading.timeout`, E-ink key double and long press,
  `hmct.intent.action.epd_show_screenshot_note`, the E-ink front light,
  `high_text_contrast_enabled`, `sys.mirror.set`, the
  `persist.sys.hmct.debugepd` override.
- **Direction listeners** are kept but never called.
- **`getSystemService("epd")` may return a cached `null`** in a process that
  asked before the service registered; use the `ServiceManager` fallback.
- **`sys.sysctl.display_type`** is not readable by untrusted apps; the
  broadcast is delivered to registered receivers only (Q33, Q34).
- **Display manager view after blanking**: after the "Using the LCD" screen,
  the display manager still believes display 1 is on until the next
  screen-off; side effects were not studied beyond "the reconnect undoes it".
- **Foreground tracking** sees the focused task's top activity after task
  stack changes (and every 2 s while the E-ink is in use); an activity change
  inside a task that causes no task-stack callback is seen by the poll.
  Activities on other displays are not considered.
- **Android 16**: several calls the service uses are hidden APIs that moved or
  changed after Android 11 (for example the physical-display calls of
  `SurfaceControl`, task listing), and boot-jar and framework patch
  integration must be redone; see [patches.md](patches.md#4-porting-to-android-16).
