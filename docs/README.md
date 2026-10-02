<!--
Copyright (C) 2026 The a6l-eink-oss authors, licensed under CC BY 4.0
SPDX-License-Identifier: CC-BY-4.0
-->

# Documentation

> **概要（日本語）** a6l-eink-oss（Hisense A6L の E-ink 部分のクリーンルーム実装）の
> ドキュメント一覧です。クライアントライブラリ `com.hmct.epd`、E-ink サービス、
> frameworks/base パッチ、スリープ画面（ウィジェットと通知）、E-ink 設定アプリについて
> 説明しています。

| Document | Content |
|---|---|
| [epd-api.md](epd-api.md) | The `com.hmct.epd` client library: how apps obtain `EpdManager`, public constants, every public method, the `IEpdManager` and `IDirectionDetect` AIDL interfaces (descriptors, transaction codes), broadcasts, settings and properties |
| [epd-service.md](epd-service.md) | The E-ink service app `A6lEinkService`: process model, boot timing, display types and transitions, hardware per state (sysfs nodes, init property bridge), E-ink key and button roles, per-finger unlock screen, status screen (stage 1), persistence, settings, permissions, SELinux, device integration, verification, known limits |
| [patches.md](patches.md) | The frameworks/base patches: `getSystemService("epd")` registration and the keyguard per-finger unlock screen; how to apply them; what an Android 16 port has to redo |
| [sleep-screen.md](sleep-screen.md) | The E-ink sleep screen, stage 2: widgets (AppWidgetHost, off-screen rendering, sizes, black-and-white conversion) and notifications (listener, lock screen rules, "+N more", redraw rate) below the clock; layout, settings page and keys, permissions, verification, known limits |
| [eink-settings.md](eink-settings.md) | The E-ink settings app (to be written) |

See also the repository [README](../README.md) (layout, quick integration)
and [QUESTIONS.md](../QUESTIONS.md) (decisions on points the specs left
open).

All documents describe externally visible behaviour and this project's own
design. Names, values and codes shared with the stock firmware are kept
identical for compatibility with stock apps and are marked as such.

Licence: the documentation in `docs/` is licensed under the Creative Commons
Attribution 4.0 International licence (CC BY 4.0). The code of the
repository is licensed under the Apache License 2.0.
