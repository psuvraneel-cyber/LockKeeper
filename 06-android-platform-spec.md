# LockKeeper — Android Platform & Compatibility Specification

## 1. Status

This document resolves Android platform assumptions that were previously open questions.

Primary target baseline:

- `minSdk = 26` (Android 8.0)
- `targetSdk = 36` (Android 16)
- `compileSdk = 36` or the current installed stable SDK compatible with target 36
- Architecture: Flutter UI + native Kotlin Android services.

The application is intended for direct sideloading. Google Play distribution is not a project requirement.

## 2. Compatibility Policy

Support is defined by behavior, not only by installation success.

The implementation must be tested on at least:

- Android 8/9 class device or emulator
- Android 10/11
- Android 12/13
- Android 14
- Android 15
- Android 16

At minimum, the test matrix must include one recent stock Android device/emulator and one OEM device with a non-stock Settings implementation if available.

Do not claim universal OEM compatibility.

## 3. Foreground Service

LockKeeper requires ongoing background observation for its protection model.

For Android 14+:

- Every foreground service must declare an appropriate `foregroundServiceType`.
- The implementation must select a valid type supported by the actual LockKeeper use case.
- Because app-lock observation does not naturally map to media, camera, microphone, location, or similar categories, `specialUse` is the candidate type and must be validated during implementation against the current Android requirements.
- If `specialUse` is used, declare `FOREGROUND_SERVICE_SPECIAL_USE` and the service-level subtype property describing the protection/monitoring use case.
- Do not use `systemExempted` unless the app demonstrably meets the platform's eligibility rules. Device Admin activation alone must not be treated as permission to use an inappropriate foreground-service exemption without verification.

For Android 12+:

- Background foreground-service start restrictions apply.
- Do not assume `startForegroundService()` is legal from any background state.

For Android 15+:

- The `SYSTEM_ALERT_WINDOW` exemption for background foreground-service starts is narrower and requires a visible overlay window in the applicable scenario.
- The implementation must not rely on a startup sequence that violates this requirement.

For Android 16:

- Re-test all service/lifecycle assumptions on API 36.
- Do not assume behavior from Android 12–15 remains unchanged.

References:
- Android foreground services: https://developer.android.com/develop/background-work/services/fgs
- Foreground-service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 14 foreground-service changes: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android 15 behavior changes: https://developer.android.com/about/versions/15/behavior-changes-15
- Android 16 behavior changes: https://developer.android.com/about/versions/16/behavior-changes-16

## 4. AccessibilityService

AccessibilityService lifecycle is controlled by Android.

Important requirements:

- The service is enabled by the user in Settings.
- Declare `BIND_ACCESSIBILITY_SERVICE`.
- Configure only the event/content capabilities actually required.
- Filter events aggressively to reduce unnecessary work.
- Treat Accessibility as the primary signal for foreground package changes.
- Do not assume event delivery is perfectly deterministic across every OEM.

The Accessibility API is sensitive and platform-governed. The service's intended purpose must remain clearly tied to the LockKeeper product's own interaction model.

References:
- https://developer.android.com/guide/topics/ui/accessibility/service
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService

## 5. Package Visibility / Installed App List

The application needs to enumerate launchable applications.

Android 11+ package-visibility rules apply.

Implementation requirements:

- Prefer targeted `<queries>` declarations where sufficient.
- Do not add `QUERY_ALL_PACKAGES` blindly.
- Enumerate launchable activities using the package manager and verify behavior against the actual target Android versions.
- The app list must display only apps that the user can reasonably launch.
- Exclude LockKeeper itself from the normal lockable-app list unless there is a deliberate future product reason to expose it.

References:
- https://developer.android.com/about/versions/11/privacy/package-visibility

## 6. Overlay

The lock and admin screens use a full-screen `TYPE_APPLICATION_OVERLAY` window.

Requirements:

- `SYSTEM_ALERT_WINDOW` must be explicitly granted by the user.
- Overlay lifecycle must be owned by native code.
- The overlay must not be permanently attached when no gate is active.
- The overlay must be removed on completion, cancellation, or service shutdown.
- Every overlay transition must handle duplicate events idempotently.
- Never create multiple stacked copies of the same gate.

Back behavior:
- Implement the strongest supported modern back-navigation interception path for the target API.
- Do not rely exclusively on `KEYCODE_BACK`, particularly on API 36 where predictive-back behavior must be considered.

## 7. Device Admin

Device Admin is used only to create uninstall/deactivation friction.

Requirements:

- Use a standard `DeviceAdminReceiver`.
- Do not claim Device Admin is a complete uninstall-prevention mechanism.
- Do not request unrelated admin policies.
- The admin-password gate remains an application-layer control.

## 8. Boot Recovery

A boot receiver may be used to re-establish protection.

Requirements:

- Handle Android boot/lifecycle restrictions correctly for the target API.
- Do not assume a boot broadcast may freely start an arbitrary foreground service on every Android version.
- If Android forbids the intended boot-time startup path under the chosen service type, implement the strongest supported recovery strategy and document it.
- Boot recovery must be tested on API 26, API 31+, API 34+, API 35, and API 36.

## 9. Battery Optimization

The application may guide the user to battery-optimization exemption.

This is a resilience aid, not a guarantee.

The UI must use accurate language:
"Battery optimization can reduce the chance that Android/OEM power management stops protection."

Never claim exemption guarantees process survival.

## 10. Timing and Clock Semantics

Cooldowns must use a robust time model.

Requirements:

- Persist an absolute wall-clock expiration timestamp for display and reboot continuity.
- Do not rely solely on in-memory timers.
- On every sensitive decision, compare persisted expiration against the current clock.
- Handle device reboot.
- Handle clock changes as an explicit threat/test case.
- Do not continuously decrement and persist a timer every second.

## 11. Battery / Performance

Primary detection:
Accessibility events.

Fallback:
UsageStats polling only when required and technically viable.

Requirements:

- No busy loops.
- No polling interval shorter than justified by measurements.
- DB access must be asynchronous.
- Event bursts must be debounced/coalesced.
- Avoid launching duplicate overlays.
- Prefer a state-machine model over ad-hoc timers/callback chains.

## 12. Permissions

Expected platform capabilities:

- `SYSTEM_ALERT_WINDOW`
- Usage Access via AppOps/settings flow
- `BIND_ACCESSIBILITY_SERVICE`
- Device Admin
- `RECEIVE_BOOT_COMPLETED`
- battery optimization exemption capability
- foreground-service permission/type requirements appropriate to the chosen service implementation

Do not request unrelated permissions.

## 13. Platform-Compatibility Rule

Every Android API with behavior that varies by API level must have either:

- a compatibility branch with tests, or
- an explicit documented minimum API constraint.

No silent reliance on a behavior observed on only one test phone is acceptable.
