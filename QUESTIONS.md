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
8. **Boot jar wiring.** `Android.bp` sets `installable`, `platform_apis` and
   `permitted_packages: ["com.hmct.epd"]`; adding it to `PRODUCT_BOOT_JARS`
   (and any hiddenapi/boot-image configuration) is left to the device tree.
   Whether the stock jar was compiled with a specific `sdk_version` is
   unknown and assumed irrelevant.
9. **`@hide` on the AIDL interfaces.** Spec does not say; marked `{@hide}`
   in the AIDL comments since the library is not part of any public SDK.
   No effect on the binary.
