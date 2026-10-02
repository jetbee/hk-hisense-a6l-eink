<!--
Copyright (C) 2026 The a6l-eink-oss authors, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# The E-ink sleep screen (stage 2: widgets and notifications)

> **概要（日本語）** 画面オフ中、背面 E-ink には E-ink サービス（`A6lEinkService`）が
> 描いた「スリープ画面」が表示されます。第 1 段階では上部 360 px に時計・日付・電池残量を
> 表示するだけでしたが、第 2 段階ではその下に **ウィジェット**（最大 4 個、白黒に変換、
> 反転・高コントラストを個別に設定可能）と **通知**（新しい順、収まらない分は「ほか N 件」）
> を表示します。通知はロック画面の設定に従います（ロック画面に通知を表示しない設定なら
> 表示せず、機密性の高い内容を非表示にする設定ならアプリ名と「内容を非表示」のみ）。
> 通知の変化による再描画は最短 10 秒間隔です。設定は「設定 > ディスプレイ > E-ink」で
> 行います。ウィジェットも通知もない場合は第 1 段階と同じ表示になります。
> 本書では表示内容、レイアウト、設定キー、権限、実機での確認方法、既知の制限を説明します。

The sleep screen is the "sleeping" state of the E-ink status screen
described in [epd-service.md](epd-service.md#9-the-status-screen): what the
rear panel shows while the phone's screen is off. Stage 1 draws a header
with the clock, the date and the battery level. Stage 2, described here,
adds the user's **widgets** and **notifications** below it.

Sources: clean-room spec 02 (`02-epd-service.md`, section 6) and spec 03
(`03-sleep-screen-stage2.md`), the Android documentation and AOSP sources
(AppWidgetHost, NotificationListenerService, and the lock screen notification
rules of SystemUI for the meaning of the public settings). Decisions on
points the specs leave open are numbered in [`QUESTIONS.md`](../QUESTIONS.md)
(Q38 onwards).

Code, all in `epd/service/src/org/a6leink/epd/`:

| File | Role |
|---|---|
| `StatusScreen.java` | when to draw; minute alarm; notification redraws; bitmap file and commit |
| `StatusRenderer.java` | the 360 px header (stage 1, unchanged) |
| `SleepSections.java` | layout of the widget and notification sections, notification items, black-and-white conversion |
| `SleepWidgetHost.java` | the AppWidgetHost and the off-screen rendering of widgets |
| `SleepWidgetConfig.java` | the stored list of widgets and their options |
| `SleepNotificationListener.java` | the notification listener service |
| `SleepNotifications.java` | which notifications are shown (lock screen rules) |
| `StatusScreenSettingsActivity.java` | the "E-ink" settings page |

## 1. When it is shown

Only in the sleeping state (display type -1, screen off), after "E-ink
ready". The "Using the LCD" screen (type 0) stays text-only, and nothing is
drawn while the E-ink is in use (types 2 and 4). Bitmap file, format, commit
and the 500 ms wait after the screen goes off are those of stage 1
([epd-service.md section 9.3](epd-service.md#93-bitmap-file-and-commit)).

With no widgets chosen and no notification to show, the picture is exactly
the stage 1 picture.

## 2. Layout

Canvas 720 x 1440 px, white, top to bottom:

```
 0  +-------------------------------------------+
    | clock square |  Sleeping                  |  header, 360 px (stage 1)
    |   360x360    |  Friday, October 2         |
    |              |  Battery 80%               |
360 +-------------------------------------------+
    |              16 px gap                    |
    |  ---------------------------------------  |  rule, 2 px, x 24..696
    |              16 px gap                    |
    |  [ widget 1, 672 px wide            ]     |  widgets section
    |              16 px gap                    |
    |  [ widget 2                         ]     |
    |              16 px gap                    |
    |  ---------------------------------------  |  rule
    |              16 px gap                    |
    |  # App - 10:42                            |  notifications section
    |    Title (bold)                           |
    |    Text, one or two lines...              |
    |               8 px gap                    |
    |  # App                                    |
    |    Contents hidden                        |
    |  +3 more                                  |
1424 - - - - - - - - - - - - - - - - - - - - -   bottom margin 16 px
1440 +-------------------------------------------+
```

- A 2 px black rule, with 16 px above and below, precedes each section that
  is present. A section with nothing to show takes no space and draws no
  rule, so with no widgets the notifications move up, and with no
  notifications nothing is drawn below the widgets.
- Order: widgets then notifications (default), or notifications then
  widgets (`a6l_epd_sleep_sections_order` = 1). The first section gets the
  space it needs; the second gets what is left.
- Side margins are 24 px; the content ends 16 px above the bottom edge.
- Text drawn by the service is pure black on white and anti-aliased, in the
  device language (English and Japanese strings).

## 3. Widgets

### 3.1 Host

The service is an `AppWidgetHost` with its own host id (`0x0a6e`). It runs
in the service process, as the platform-signed system app (uid `system`),
which passes the `BIND_APPWIDGET` check, so widgets are bound with
`bindAppWidgetIdIfAllowed` without a prompt. The host starts listening when
the service starts (or, during a direct boot, when the user is unlocked:
AppWidgetService refuses hosts of a locked user) and never stops while the
service runs. Widget providers update on their own schedule; the service
never requests updates. It reports the size a widget gets on the E-ink
(`updateAppWidgetOptions`) once, when the widget is added or its height is
changed.

### 3.2 Choosing widgets

On the "E-ink" settings page (section 6), under **Widgets**:

- **Add widget** lists the installed providers
  (`AppWidgetManager.getInstalledProvidersForProfile`, sorted by name). The
  chosen one is bound to a new id; if the provider has a configuration
  activity, it is started and the widget is added only if it returns OK.
- Each chosen widget has **move up**, **move down** and **remove**
  buttons; tapping it opens its display options: height, invert, high
  contrast.
- Up to 4 widgets. Removing a widget deletes its id. Ids of this host that
  are in no list (e.g. left by an interrupted add) are deleted when the page
  opens.

### 3.3 Sizes

Every widget is 672 px wide (720 px minus 24 px margins). Height:

| Option (`size`) | Height |
|---|---|
| 0, widget's own (default) | the provider's `minHeight`, scaled to the panel (at least 48 px; 160 px if the provider gives none) |
| 1, small | 160 px |
| 2, medium | 280 px |
| 3, large | 400 px |

Widgets are stacked in the chosen order with 16 px between them. If a
widget's height does not fit in the space left, it gets the space left as
long as that is at least its minimum (the provider's `minResizeHeight`, or
`minHeight`, scaled); otherwise it is skipped (never cut), and the next
widgets are still tried.

**Scale.** Widgets are laid out at the phone's density and the result is
scaled down to an assumed E-ink density of 280 dpi (the 720 x 1440 panel is
about 276 ppi), so a widget keeps about the size it has on the LCD. A
672 px wide slot is 384 dp for the widget.

### 3.4 Rendering

No window is used. For each redraw the service, on its main thread:

1. creates the widget's host view on first use (then keeps it; the host
   keeps it up to date);
2. sets clock views (`TextClock`, `AnalogClock`) to the current time, because
   they update themselves only while attached to a window;
3. measures and lays the view out at the slot size;
4. **collection widgets** (lists, grids, stacks, e.g. calendars): their rows
   come asynchronously from the provider's `RemoteViewsService`. While an
   adapter is not connected or not ready, or the rows keep changing the
   layout, it re-lays the view out every 100 ms, until two polls in a row
   are quiet or **1 s** has passed; then the widget is drawn as it is;
5. draws the view on a canvas filled with white (black if "invert" is on, so
   that white-on-transparent widgets stay readable) and scales it to the
   panel size.

A widget that throws while being laid out or drawn is skipped for that
redraw and logged (`A6lEpdWidgets`); the sections are laid out again
without it. If the main thread does not answer within 2 s, the screen is
drawn without widgets. The clock is always drawn.

### 3.5 Black and white

After drawing, the whole widget area is converted to grey (luminance
0.299 R + 0.587 G + 0.114 B), then the widget's options apply:

- **Invert** (default off): grey levels are reversed, for widgets with light
  text on a dark or transparent background.
- **Contrast** normal (default): greys are kept. **High**: greys are
  stretched around 50 %: luminance up to 100 becomes black, from 156 white,
  linear in between.

### 3.6 Refresh

Widgets are repainted with the screen: every minute when the clock is on,
and on a notification redraw. The picture shows the latest views the host
received. With the clock off, the screen is drawn once per screen-off (plus
notification redraws).

## 4. Notifications

### 4.1 Listener

`SleepNotificationListener` is declared in the manifest
(`BIND_NOTIFICATION_LISTENER_SERVICE`). At start the service grants its own
listener access (`NotificationManager.setNotificationListenerAccessGranted`,
allowed for uid `system`), so the user does not have to; it is visible in
Settings > Apps & notifications > Special app access > Notification access
as "E-ink sleep screen".

### 4.2 Which notifications are shown

The E-ink can be read by anyone while the phone sleeps, so the choice
follows the lock screen settings of the current user:

| Condition | Result |
|---|---|
| `a6l_epd_sleep_notifications` = 0, or `a6l_epd_sleep_notifications_max` = 0 | section hidden |
| Settings.Secure `lock_screen_show_notifications` = 0 (default 1), or a device admin disables secure notifications | none shown |
| Notification visibility `SECRET`, or the channel's / app's lock screen visibility set to secret | not shown |
| Ongoing, foreground service, group summary | not shown |
| Importance below default (silent and minimized), app suspended, hidden from the list by Do Not Disturb | not shown |
| The service's own notifications, other users, work profiles | not shown (notifications addressed to all users are kept) |
| Channel or app lock screen visibility set to private by the user | redacted |
| Notification visibility `PRIVATE` (the Android default) and Settings.Secure `lock_screen_allow_private_notifications` = 0 (default 0) or a device admin disables unredacted notifications | redacted |
| Otherwise | shown in full |

"Redacted" shows the notification's `publicVersion` if it has one,
otherwise only the app icon and app name with the line "Contents hidden"
("内容を非表示"). This is the rule the lock screen applies (Q38): with
"Show all notification content" on, private notifications are shown in
full; with "Hide sensitive content", only public ones are.

### 4.3 Items

Newest first by post time. Each item:

- the notification's small icon, 32 px, black where opaque (the app's
  launcher icon, thresholded at 50 % luminance, if there is none); the app
  name and the time (the notification's time if it shows one, else the post
  time; the date instead if not today), 22 px;
- the title, bold, one line, ellipsised;
- the text (big text if present), one or two lines, ellipsised.

An item is 96 to 122 px high; 8 px between items. As many items as fit are
shown, at most `a6l_epd_sleep_notifications_max` if that is 1 to 6; if some
are not shown, the last line is "+N more" ("ほか N 件").

### 4.4 Redraw on change

When a notification is posted, updated or removed while the screen is off,
the service compares the list of notifications that would be shown with
the list of the last draw. If it changed, it redraws at once, but at most
once every 10 s after the previous draw (of any kind); changes within that
window are coalesced into one redraw at its end (an exact
`ELAPSED_REALTIME_WAKEUP` alarm). The minute redraw continues as before.
While the screen is on, nothing is drawn.

## 5. Battery and robustness

- Each redraw holds a partial wake lock of 1.5 s as in stage 1; with
  widgets, the wake lock also covers the collection wait (at most 1 s) and
  is held for 1.5 s again after the commit.
- No widget update is requested and no polling happens between redraws:
  widget providers and notifications wake the service only when they change.
- Problems below the header (a widget, the notification service, the
  settings) never stop the header from being drawn.
- If the bitmap file cannot be written, nothing is committed and nothing
  else changes; the error is logged once per boot (tag `A6lEpdStatus`) and
  the last error is shown in `dumpsys epd`.

## 6. Settings page

**Settings > Display > E-ink** (`StatusScreenSettingsActivity`, guarded by
`WRITE_SECURE_SETTINGS`): "Status screen" (clock, clock style), then:

- **Widgets**: the list with move up / move down / remove, the options
  dialog of each widget, and "Add widget".
- **Notifications**: "Notifications on the sleeping screen" (switch),
  "Maximum shown" (As many as fit, None, 1 to 6), "Order below the clock"
  (widgets then notifications, or notifications then widgets).
- **Buttons** (unchanged).

Changes take effect at the next draw. Widgets can be chosen only on the
system user (owner), where the service and its widget host run; on other
users the page says so.

## 7. Settings keys

Settings.System, current user:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `a6l_epd_sleep_sections_order` | int | 0 | 0 = widgets then notifications, 1 = notifications then widgets |
| `a6l_epd_sleep_widgets` | string | empty | the chosen widgets in order, JSON (below) |
| `a6l_epd_sleep_notifications` | int | 1 | 1 = show notifications, 0 = none |
| `a6l_epd_sleep_notifications_max` | int | -1 | -1 = as many as fit, else 0..6 (0 hides the section) |

The stage 1 keys stay: `a6l_epd_sleep_clock`, `a6l_epd_sleep_clock_style`
(and `a6l_epd_swap_keys_on_eink` on the same page).

`a6l_epd_sleep_widgets` is a JSON array, at most 4 entries:

```json
[{"id":12,"size":1,"invert":false,"contrast":0}]
```

| Field | Meaning |
|---|---|
| `id` | app widget id, allocated by the service's host (id `0x0a6e`) |
| `size` | 0 widget's own height, 1 small (160 px), 2 medium (280 px), 3 large (400 px) |
| `invert` | true = reverse grey levels |
| `contrast` | 0 normal, 1 high |

Entries that are malformed, repeated or past the 4th are ignored. Ids must
be bound to this host; writing ids by hand (`settings put system ...`) only
reorders or changes options of widgets added on the settings page.

## 8. Permissions and process

| Item | Value |
|---|---|
| Widget binding | uid `system` passes the `BIND_APPWIDGET` check; the permission is not declared, so no privileged-permission allowlist is needed |
| Notification listener | `SleepNotificationListener`, exported, guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`; access granted by the service itself for the system user |
| Users | the service runs in the system user; its listener and widget host belong to that user. Notifications are filtered to the current user, so when another user is in the foreground only notifications for all users can appear |
| Threads | layout, notification reading and drawing on the service's handler thread; widget host views on the main thread |

## 9. Verifying on a device

```sh
adb shell dumpsys epd                 # status screen, sleep screen and widget host lines
adb logcat -s A6lEpdStatus A6lEpdWidgets A6lEpdNotifications
adb shell dumpsys epd status sleep    # draw the sleeping screen now (test hook)
adb shell dumpsys epd status stop
adb shell settings get secure enabled_notification_listeners   # contains org.a6leink.epd/.SleepNotificationListener
```

`dumpsys epd` adds: notification redraws, whether one is pending, the number
of notifications counted at the last draw, whether the listener is
connected, the widget host's id, whether it is listening (or waiting for
unlock), the last render (widget ids and sizes, "skipped", "failed",
"collection wait ended") and the last widget error.

Checks (spec 03 section 7):

1. No widgets, no notifications, screen off: the picture is identical to
   stage 1.
2. Add a clock or weather widget and a calendar (list) widget on the
   settings page; turn the screen off: both appear and are readable; try
   invert and high contrast. Note in the device log which collection
   widgets load within the 1 s wait.
3. Post a notification while sleeping, e.g.
   `adb shell cmd notification post -S bigtext -t "Test" test "Hello from adb"`:
   the E-ink updates within about 10 s. With "Hide sensitive content" on
   the lock screen, only the app name and "Contents hidden" appear; with
   "Don't show notifications", none.
4. Post many notifications (different tags): the "+N more" line appears.
5. Compare the overnight battery drain with stage 1.

## 10. Known limits

- **Off-screen views.** Widget views are never attached to a window. Views
  that need a window to update (other than the clocks handled above), such
  as chronometers or animations, may show stale content; clicks do nothing.
- **Collection widgets** get at most 1 s per redraw to load; slow providers
  may appear empty or with loading rows on the first redraw after the
  screen goes off and fill in on later ones.
- **Hardware bitmaps** in a widget cannot be drawn on the software canvas;
  such a widget is skipped and logged.
- **Density.** The 280 dpi panel density is an assumption; widgets may look
  slightly larger or smaller than on the LCD.
- **Users.** Widgets and the notification listener work for the system user
  (owner) only.
- **The "Using the LCD" screen** shows neither widgets nor notifications (by
  design).
