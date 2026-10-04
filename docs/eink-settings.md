<!--
Copyright (C) 2026 The hk-hisense-a6l-eink authors, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# E-ink settings app (`A6lEinkSettings`)

> **概要（日本語）** `A6lEinkSettings`（パッケージ `org.a6leink.settings`）は、
> 背面 E-ink の表示を調整する設定アプリです。「設定 > ディスプレイ」に
> 「E-ink のアプリと画質調整」という項目が追加され、次の 2 画面と自動切り替えの
> スイッチがあります。
>
> - **アプリの表示モード**: ランチャーに表示されるアプリごとに「なめらか」
>   （E-ink サービスの既定のモード）か「くっきり」（明示的な書き換えモード）を選びます。
>   アプリをタップすると、モードと「1 画面スライド（試験運用、既定はオフ）」を
>   設定できます。「すべてなめらか」「すべてくっきり」ボタンもあります。アプリの
>   自動登録や同梱のアプリ一覧はありません。
> - **自動切り替え**: 「カメラ使用中はなめらかにする」「動画再生中はなめらかにする」
>   （どちらも既定でオン、E-ink の手前のアプリが対象）。
> - **E-ink の画質調整**: コントラスト、黒のしきい値、白のしきい値（0〜100）の
>   スライダーと、「今すぐ残像を消す」ボタンです。
>
> 手前のアプリの切り替えには、E-ink サービスのクイック設定タイル
> 「E-ink: くっきり/なめらか」も使えます。設定はすべて E-ink サービス（`epd`）が
> 保存します。このアプリ自体は状態を持ちません。「くっきり」で使うモード値
> （3）はサービスが持つ 1 つの定数で、実機のパネルで効果が確認済みです
> （2026-10-03）。カメラ・動画の自動切り替え、1 画面スライドの近似、E-ink 専用
> キーボードなし、端末全体で 1 つの設定、はユーザーの決定事項です（spec 05 第 8 節）。

The app lives in `epd/settings/` and implements the recommended scope of
clean-room spec 04 section 6, and the per-app UI of spec 05 section 6.5, on
top of the service in `epd/service/` (see
[epd-service.md](epd-service.md)) and the client library in `epd/framework/`
(see [epd-api.md](epd-api.md)). It does not reproduce the stock
"E-Ink Settings" app: the lock-screen, E-ink launcher, cover and reminder
settings of the stock app have no consumer on LineageOS 18.1.

## Identity

| Item | Value |
|---|---|
| Module | `A6lEinkSettings` (`epd/settings/Android.bp`), listed in `epd/epd.mk` `PRODUCT_PACKAGES` |
| Package | `org.a6leink.settings` |
| Install path | `/system_ext/priv-app/A6lEinkSettings/` |
| Signing | platform certificate, privileged, `platform_apis` |
| Libraries | `com.hmct.epd` (boot class path, not bundled) |
| Languages | English (default), Japanese (`values-ja`) |
| Launcher icon | none; reached from Settings only |

## Screens

### Entry: Settings > Display > "E-ink apps & tuning"

`EinkSettingsActivity` is injected into the Display page of the Settings app
with the `com.android.settings.action.IA_SETTINGS` intent filter and the
category `com.android.settings.category.ia.display`, next to the "E-ink"
page of the service (status screen and buttons). It shows:

- **App display mode** → `AppModeActivity`
- **E-ink tuning** → `TuningActivity`
- **Automatic switching** (spec 05 section 6.3), two switches stored by the
  service (options `auto_camera` and `auto_video`, both on by default):
  - "Smooth while the camera is in use": smooth mode while the app in front
    on the E-ink has a camera open;
  - "Smooth while a video plays": smooth mode while the app in front plays
    video-like audio (movie content, or media/game sound that is not speech
    or music).

If the `epd` service is not running, the entries and switches are disabled
and a note is shown.

### App display mode

Lists every package that has a launcher activity (`ACTION_MAIN` +
`CATEGORY_LAUNCHER`), one row per package, except packages that provide an
input method. Rows are in two groups, "Smooth mode (n apps)" and "Clear mode
(n apps)", each sorted by label with the locale's collation; a group header
is shown only when the group is not empty.

The switch on the right of a row is on for "Clear" and flips the mode.
Tapping the rest of the row opens the app's options:

- **Display mode on the E-ink**: "Smooth (default)" or "Clear" (or the custom
  value, kept unless Smooth is picked);
- **Slide one screen (experimental)**, off by default for every app: while a
  finger drags, the E-ink switches to smooth; shortly after the finger is
  lifted the app's mode comes back (useful for apps set to Clear). An
  approximation of the stock feature (which held the picture still during
  the drag) without framework changes (spec 05 section 8, decision c); see
  [epd-service.md](epd-service.md#87-slide-one-screen-experimental-off-for-every-app-by-default).
  A row with it on says "· Slide one screen (experimental)".

Changes are sent to the service immediately; there is no save step. The
service re-applies the mode at once if the app is in front. "All smooth" and "All clear" at the bottom change every listed app
(enabled only when at least one app would change). The list is reloaded each
time the screen is shown again.

| UI choice | Stored in the service | Effect on the E-ink |
|---|---|---|
| Smooth | no entry (`removeAppMode(pkg)`) | the service's default refresh mode for E-ink types, 8 (steady) |
| Clear | `addAppMode(pkg, clear)`, clear = 3 today | the clear mode while the app is in front |
| (any other stored value, set by another tool; an explicit 8 counts as Smooth) | kept unchanged | shown as "Clear (custom mode n)"; the switch makes it Smooth |

The value used for "Clear" is **not** in this app: it is the single constant
`EpdSettings.MODE_CLEAR` of the service, read through the service's options
provider (`get_config`), so the settings app, the Quick Settings tile and the
E-ink lock screen (when its clear option is on, on the service's "E-ink"
page) always agree. "Clear" = 3 is **confirmed on the panel**
(device test 2026-10-03: visibly clearer, scrolling stutters; spec 05
section 8). Should it ever be retuned (1..8, the range the service
accepts), change only that constant.

Decided by the user (spec 05 section 8): automatic smooth mode while a video
plays (switch, default on); the camera switch only while the app using the
camera is in front; "slide one screen" as the framework-free approximation
(a fast mode only while the finger drags); no separate E-ink keyboard; one
map for the whole device, not per user.

The same choice can be flipped for the app in front with the service's Quick
Settings tile "E-ink: clear/smooth"
([epd-service.md](epd-service.md#88-quick-settings-tile)).

Not done, on purpose (spec 04 section 6.1): no entry is written automatically
at first boot or when an app is installed, and no list of preset apps is
bundled. The service removes the entry and slide flag of an app that is
uninstalled for every user.

### E-ink tuning

| Control | Service call | Range / default |
|---|---|---|
| Contrast slider | `getEinkEffect(0)` / `adjustEinkEffect(0, v)` | 0..100, default 0 |
| Black threshold slider | `getEinkEffect(1)` / `adjustEinkEffect(1, v)` | 0..100, default 30 |
| White threshold slider | `getEinkEffect(2)` / `adjustEinkEffect(2, v)` | 0..100, default 20 |
| "Reset to defaults" | `adjustEinkEffect` with the three defaults | — |
| "Clear ghosting now" | `forceClear()` | — |

A slider value is sent once when the slider is released (every change redraws
the E-ink), or at once for key or accessibility changes. The service keeps the
values in `persist.sys.epd.contrast`, `persist.sys.epd.black` and
`persist.sys.epd.white` and writes them to the panel whenever the E-ink is
ready, so changes made while the LCD is in use take effect on the next switch
to the E-ink. "Clear ghosting now" is ignored by the service until the E-ink
is ready after boot, and is visible only while the E-ink shows something.

### Not included

- **High contrast text** (stock Global `hmct_epd_highlight`): left out. The
  service does not couple it to Secure `high_text_contrast_enabled` (spec 02
  section 2.6 lists it as stock-only, not done), so a switch would have no
  effect. It can be added once the service implements the coupling.
- E-ink screen-off timeout: no framework support on 18.1 (spec 04 section
  6.2).
- Lock screen, E-ink launcher, application covers, reminders and the other
  stock-only items (spec 04 section 6.3).
- The sleeping-screen clock and the button swap stay on the service's own
  "E-ink" page.
- No separate input method for the E-ink (spec 05 section 4.6; decided
  against) and no per-user maps (one device-wide map).

## Service calls used

Only these `IEpdManager` methods, through `com.hmct.epd.EpdManager`
(`context.getSystemService("epd")`, falling back to
`ServiceManager.getService("epd")`):

`getAllAppMode`, `addAppMode`, `removeAppMode`, `getEinkEffect`,
`adjustEinkEffect`, `forceClear`.

The service's own options go through its provider
`content://org.a6leink.epd.options` (`ContentResolver.call`):
`get_config`, `set_slide`, `set_option`
([epd-service.md](epd-service.md#89-options-provider-for-the-settings-app)).

The app reads and writes no `Settings` keys and no properties itself. Binder
calls of the per-app list run on a worker thread; the tuning page calls the
service on the main thread (each call is a property read or write).

## Permissions

| Item | Value |
|---|---|
| `android.permission.QUERY_ALL_PACKAGES` | to list all launchable apps and input methods (package visibility, target SDK 30) |
| `org.a6leink.epd.permission.EINK_OPTIONS` | signature permission of the service app, for its options provider; granted because both apps are platform-signed (no allowlist needed) |
| Injected activity | exported, guarded by `android.permission.WRITE_SECURE_SETTINGS`, as the service's pages (spec 02 section 7.7); only system apps such as Settings can open it |
| Inner activities | not exported |
| `epd` binder | no permission required by the service |

The app does not run as the system uid.

## How to verify

1. Build and flash, or push the APK to `/system_ext/priv-app/A6lEinkSettings/`
   and reboot.
2. `adb shell pm list packages org.a6leink.settings` lists the package.
3. Settings > Display shows "E-ink apps & tuning" (Japanese: "E-ink のアプリと画質調整").
4. App display mode: set an app to Clear, then
   `adb shell dumpsys epd` lists its package with mode 3 under "per-app
   modes"; set it back to Smooth and the entry is gone. Open the app on the
   E-ink and check that "refresh mode" in `dumpsys epd` follows (3 for Clear,
   8 for Smooth).
5. Tuning: move a slider and release; `adb shell getprop persist.sys.epd.contrast`
   (or `.black`, `.white`) and the "tuning" line of `dumpsys epd` show the
   new value; with the E-ink in use the picture changes. "Reset to defaults" restores 0 / 30 / 20.
6. "Clear ghosting now" with the E-ink in use produces one full refresh.
7. Per-app options: tap an app, turn on "Slide one screen (experimental)";
   `dumpsys epd` lists it under "slide one screen". With that app in front
   on the E-ink, a drag shows "dragging: true" and mode 8, and shortly after
   lifting the finger the app's mode again.
8. Automatic switching: turn a switch off; the "auto switches" line of
   `dumpsys epd` follows, and opening the camera app no longer gives
   "camera lock" as the mode reason.
9. Quick Settings tile: add "E-ink: clear/smooth" in the QS editor; with an
   app in front on the E-ink, tapping it flips the app between clear and
   smooth (and the app moves between the groups here).
10. With the service stopped, the pages show "The E-ink service is not
   running" and the controls are disabled.

## Known limits

- The list scrolls normally; there is no page-by-page scrolling for the
  E-ink yet.
- The tuning sliders show the service's values as read when the page opens;
  a value outside 0..100 (or a failed read) is shown as the default.
- Apps are listed for the user that opened Settings; entries in the service
  are not per user (the service keeps one map for all users).
- Slide one screen is experimental: it changes the refresh mode during a
  drag and redraws once after it; it cannot hold the picture still as stock
  did (that needs a framework change).
- Only exact-package entries are shown; activity-class or stock-style
  substring entries written by other tools are applied by the service but
  not listed.
