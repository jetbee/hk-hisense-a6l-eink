<!--
Copyright (C) 2026 The a6l-eink-oss authors, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# E-ink settings app (`A6lEinkSettings`)

> **概要（日本語）** `A6lEinkSettings`（パッケージ `org.a6leink.settings`）は、
> 背面 E-ink の表示を調整する設定アプリです。「設定 > ディスプレイ」に
> 「E-ink のアプリと画質調整」という項目が追加され、次の 2 画面を開けます。
>
> - **アプリの表示モード**: ランチャーに表示されるアプリごとに「なめらか」
>   （E-ink サービスの既定のモード）か「くっきり」（明示的な書き換えモード）を選びます。
>   「すべてなめらか」「すべてくっきり」ボタンもあります。アプリの自動登録や
>   同梱のアプリ一覧はありません。
> - **E-ink の画質調整**: コントラスト、黒のしきい値、白のしきい値（0〜100）の
>   スライダーと、「今すぐ残像を消す」ボタンです。
>
> 設定はすべて E-ink サービス（`epd`）が保存します。このアプリ自体は状態を持ちません。
> 「くっきり」で使うモード値（現在 3）は実機での確認が必要です。

The app lives in `epd/settings/` and implements the recommended scope of
clean-room spec 04 section 6 on top of the service in `epd/service/` (see
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
page of the service (status screen and buttons). It shows two entries:

- **App display mode** → `AppModeActivity`
- **E-ink tuning** → `TuningActivity`

If the `epd` service is not running, both entries are disabled and a note is
shown.

### App display mode

Lists every package that has a launcher activity (`ACTION_MAIN` +
`CATEGORY_LAUNCHER`), one row per package, except packages that provide an
input method. Rows are in two groups, "Smooth mode (n apps)" and "Clear mode
(n apps)", each sorted by label with the locale's collation; a group header
is shown only when the group is not empty.

Tapping a row switches the app between the two modes; the switch on the right
is on for "Clear". The change is sent to the service immediately; there is no
save step. "All smooth" and "All clear" at the bottom change every listed app
(enabled only when at least one app would change). The list is reloaded each
time the screen is shown again.

| UI choice | Stored in the service | Effect on the E-ink |
|---|---|---|
| Smooth | no entry (`removeAppMode(pkg)`) | the service's default refresh mode for E-ink types, 8 (steady) |
| Clear | `addAppMode(pkg, 3)` | refresh mode 3 (`EPD_MODE_READING`) while the app is in front |
| (any other stored value, set by another tool) | kept unchanged | shown as "Clear (custom mode n)"; tapping it makes it Smooth |

The value used for "Clear" is the single constant `Epd.MODE_CLEAR` in
`epd/settings/src/org/a6leink/settings/Epd.java`. **It must be confirmed on
the device**: what mode 3 does at `fb1/epd_display_mode` on 18.1 has not been
measured (spec 04 open question 1). If another value (1..7, the range the
service accepts) gives a cleaner static picture, change only that constant.

Not done, on purpose (spec 04 section 6.1): no entry is written automatically
at first boot or when an app is installed, and no list of preset apps is
bundled. Stale entries of uninstalled apps are not cleaned up by this app.

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

## Service calls used

Only these `IEpdManager` methods, through `com.hmct.epd.EpdManager`
(`context.getSystemService("epd")`, falling back to
`ServiceManager.getService("epd")`):

`getAllAppMode`, `addAppMode`, `removeAppMode`, `getEinkEffect`,
`adjustEinkEffect`, `forceClear`.

The app reads and writes no `Settings` keys and no properties itself. Binder
calls of the per-app list run on a worker thread; the tuning page calls the
service on the main thread (each call is a property read or write).

## Permissions

| Item | Value |
|---|---|
| `android.permission.QUERY_ALL_PACKAGES` | to list all launchable apps and input methods (package visibility, target SDK 30) |
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
7. With the service stopped, the pages show "The E-ink service is not
   running" and the controls are disabled.

## Known limits

- The "Clear" mode value (3) is unconfirmed on the hardware (see above).
- The list scrolls normally; there is no page-by-page scrolling for the
  E-ink yet.
- The tuning sliders show the service's values as read when the page opens;
  a value outside 0..100 (or a failed read) is shown as the default.
- Apps are listed for the user that opened Settings; entries in the service
  are not per user (the service keeps one map for all users).
- Uninstalled apps keep their entry in the service until it is removed by
  another means.
