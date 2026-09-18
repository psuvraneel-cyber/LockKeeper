# LockKeeper — Implementation Plan (Execution Version)

## 1. Objective

Implement a working LockKeeper Android application from the existing Product Brief, PRD, UI/UX Specification, Technical Design Document, and this supplemental specification set.

This plan supersedes the earlier implementation-plan draft where it contained unresolved questions.

The agent must implement in small, verifiable phases.

## 2. Phase 0 — Repository Reconnaissance

Before coding:

- inspect the existing repository;
- identify current Flutter version;
- identify Android Gradle Plugin/Kotlin/Gradle versions;
- identify package/application ID;
- identify existing native Android code;
- inspect generated files and existing platform channels;
- inspect dependency versions;
- locate current assets.

Produce a short implementation note before making major structural changes.

Do not regenerate an existing working Flutter project from scratch unless the current project is unusable.

## 3. Phase 1 — Baseline & Build Reproducibility

Goals:

- application builds in debug mode;
- release configuration exists;
- package/application ID is fixed;
- target API 36 is configured;
- min SDK 26 is configured;
- Kotlin/Gradle/Flutter versions are compatible;
- app icon assets are integrated.

Acceptance:

```text
flutter doctor / equivalent environment check passes
flutter pub get passes
debug APK builds
release-mode compilation succeeds
```

Do not begin complex native interception until the baseline build is reproducible.

## 4. Phase 2 — Native Domain & Persistence

Implement:

- Room database;
- repositories;
- credential-store abstraction;
- secure credential implementation;
- lock decision domain model;
- cooldown semantics;
- failure/lockout state.

Acceptance:

- unit tests pass;
- credentials never appear in logs;
- database contains no plaintext credentials;
- lock-state decisions can be tested without UI.

## 5. Phase 3 — Platform Channel Contract

Implement the minimal Flutter ↔ native API.

Required operations:

```text
requestPermission(type)
checkPermissionStatus(type)

setPin(pin)
verifyPin(pin)

setAdminPassword(password)
verifyAdminPassword(password)

getLockedApps()
setLockedApp(packageName, config)

startProtectionService()
getProtectionStatus()
```

Events:

```text
onPermissionRevoked(type)
onProtectionStateChanged(...)
```

Rules:

- API responses must be typed/validated;
- native security checks remain authoritative;
- Flutter must not implement duplicate security rules.

## 6. Phase 4 — AccessibilityService

Implement:

- service declaration;
- event filtering;
- foreground package identification;
- protected Settings detection framework;
- service lifecycle;
- event debouncing;
- native state-machine integration.

Do not hard-code only one Settings class name.

Use layered signals where available:

- package;
- class/activity;
- visible window/content hints when necessary;
- known destination semantics;
- OEM/device-specific fallbacks.

Acceptance:

- locked apps are detected;
- unrelated events do not produce gates;
- duplicate events do not stack overlays.

## 7. Phase 5 — Overlay Manager

Implement native:

- PIN overlay;
- strict-cooldown overlay;
- admin-password overlay.

Requirements:

- full screen;
- focusable input;
- exactly one active gate;
- deterministic attach/detach;
- lifecycle-safe cleanup;
- modern back handling;
- accessibility-conscious UI;
- no secret logging.

Acceptance:

- overlay appears without persistent Flutter Activity dependency;
- correct credentials behave as specified;
- wrong PIN count/lockout persists;
- strict cooldown cannot be bypassed through the PIN view.

## 8. Phase 6 — Flutter UI

Implement in the order specified by the UX document:

1. onboarding;
2. home/app list;
3. detail;
4. settings;
5. permission state/banner;
6. password/PIN setup flows.

The UI must read state from native repositories through the platform API.

## 9. Phase 7 — Device Admin & Protected Settings

Implement:

- DeviceAdminReceiver;
- activation flow;
- protected Settings detection;
- admin-password grace window;
- failure path.

The grace window must be finite and explicit.

Acceptance:

- admin password is required for protected actions;
- incorrect/dismissed credential results in the intended back-out behavior where supported;
- no indefinite unlocked Settings grace state remains after timeout.

## 10. Phase 8 — Foreground Service & Boot Recovery

Implement:

- foreground service;
- persistent notification;
- selected valid service type;
- API-specific startup paths;
- boot handling;
- lifecycle recovery.

Special attention:

- Android 12 background-start restrictions;
- Android 14 foreground-service type requirements;
- Android 15 overlay/start restrictions;
- Android 16 compatibility.

Acceptance:

- service starts through supported paths;
- expected exceptions are handled;
- no repeated restart loop;
- boot recovery is verified on supported test devices.

## 11. Phase 9 — UsageStats Fallback

Implement only after the Accessibility primary path is stable.

Requirements:

- bounded polling;
- only query when fallback is actually needed;
- avoid duplicate detection;
- stop or reduce polling when primary protection is active where appropriate.

Acceptance:

- fallback detects normal app switches when permitted;
- latency is measured;
- battery cost is measured qualitatively;
- disabling Accessibility does not silently create an unbounded busy loop.

## 12. Phase 10 — Hardening

Perform:

- null-safety review;
- concurrency review;
- lifecycle review;
- manifest exported-component review;
- permission review;
- logging review;
- secret-handling review;
- database migration review;
- duplicate overlay review;
- crash handling.

Then run the entire adversarial testing plan.

## 13. Phase 11 — Release Candidate

Produce:

- signed release APK;
- optional AAB;
- build metadata;
- changelog;
- test report;
- known limitations;
- installation instructions.

The release artifact must be reproducible from the repository.

## 14. Agent Stop Conditions

Do not continue to the next phase when:

- the previous phase has a failing acceptance test;
- a security invariant is violated;
- a platform API is being used outside its documented contract;
- implementation requires silently changing a product requirement.

Instead, record the issue and resolve it before proceeding.

## 15. Final Rule

Prefer the smallest architecture that correctly satisfies the requirements.

Do not add:
- frameworks,
- libraries,
- permissions,
- services,
- background loops,
- networking,
- telemetry,
- abstractions

unless they solve a documented requirement.
