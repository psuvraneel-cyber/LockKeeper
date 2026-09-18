# Runtime Validation Required Matrix

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## 1. Executive Summary

In accordance with strict adversarial validation rules, this document explicitly delineates properties that have been **STATICALLY PROVEN** from those that **REQUIRE RUNTIME PHYSICAL DEVICE VALIDATION**.

Static analysis can prove code logic, AST branch failures, and algorithmic bounds, but cannot simulate proprietary OEM window compositors, closed-source vendor settings activities, or OEM battery management daemons.

---

## 2. Statically Proven vs Runtime Required Matrix

| Architectural Feature | Statically Proven | Runtime Validation Required | Notes / Physical Target |
|---|---|---|---|
| **SQL Zero-Row Update Fail-Open** | **YES** | NO | Proven by SQLite query semantics on empty tables. |
| **Device Admin List False Positive** | **YES** | NO | Proven by Boolean evaluation of `mentionsLockKeeper \|\| hasDeactivateAction`. |
| **Clock-Warp Lockout Bypass** | **YES** | NO | Proven by `System.currentTimeMillis()` dependency. |
| **Dual Grace Window Desync** | **YES** | NO | Proven by independent timer implementations. |
| **Missing `RECOVERY_REQUIRED` State**| **YES** | NO | Proven by total absence in codebase grep. |
| **R8/ProGuard Obfuscation Disabled**| **YES** | NO | Proven by `isMinifyEnabled = false` in build config. |
| **A11y Event Dropping (Content)** | **YES** | NO | Proven by `eventTypes = typeWindowStateChanged` only. |
| **MIUI 14 / HyperOS Node Depths** | NO | **YES** | Need to measure actual node depth of MIUI App Info card views. |
| **Samsung OneUI 6 Clear Data Dialog**| NO | **YES** | Need to verify whether confirmation dialog includes app label text. |
| **IME Soft Keyboard Overlay Dismissal**| Highly Likely | **YES** | Validate whether IME animation dispatches window event that kills overlay. |
| **MIUI Background Pop-up Restriction**| Probable | **YES** | Verify whether `TYPE_APPLICATION_OVERLAY` works without user toggling MIUI popup perm. |
| **Safe Mode Reboot Persistence** | Architecture | **YES** | Verify behavior upon booting into Safe Mode and subsequent reboot. |
| **OEM Maintenance Suites (Samsung Lool)**| Architecture | **YES** | Verify whether Samsung Device Care deep clean can kill service without challenge. |

---

## 3. Physical Device Testing Protocol

When the project proceeds to physical hardware testing, the following test matrix must be executed on target devices:

### Test Case 01: Xiaomi MIUI 14 / HyperOS App Info Interception
- **Target Device:** Xiaomi Redmi Note / Poco (Android 12 / 13).
- **Objective:** Verify whether `TamperDetectionEngine` intercepts uninstallation without black-screen regressions.
- **Steps:**
  1. Complete onboarding.
  2. Open Settings -> Apps -> Manage Apps -> LockKeeper.
  3. Observe whether `AdminOverlayView` appears.
  4. Measure latency from screen launch to overlay attach.
  5. Tap password box to open keyboard; observe whether overlay survives keyboard launch.
- **Success Criteria:** Overlay attaches within 100ms; keyboard appears without dismissing overlay; typing password unlocks with 30s grace.

### Test Case 02: Samsung Galaxy One UI 6 Drag-to-Uninstall
- **Target Device:** Samsung Galaxy S22 / S23 / A54 (Android 13 / 14).
- **Objective:** Verify whether launcher drag-to-uninstall triggers Package Installer tamper detection.
- **Steps:**
  1. Drag LockKeeper icon from launcher to "Uninstall" bin.
  2. System Package Installer dialog appears.
- **Success Criteria:** LockKeeper detects the uninstaller dialog and renders the Admin Password overlay before the user can tap "OK".

### Test Case 03: Android 13+ Notification Shade Task Manager Kill
- **Target Device:** Google Pixel (Android 14 / 15).
- **Objective:** Verify whether the FGS Task Manager can stop LockKeeper Foreground Service.
- **Steps:**
  1. Pull down notification shade twice to open quick settings.
  2. Tap active apps button ("1 app active").
  3. Observe LockKeeper entry and "Stop" button.
- **Success Criteria:** Document whether tapping "Stop" kills the service and whether any UI gate can intercept it.

### Test Case 04: Storage Clear Data Dialog Hierarchy Dump
- **Target Device:** Google Pixel, Samsung Galaxy, Xiaomi.
- **Objective:** Dump the accessibility node hierarchy of the "Clear All Data?" confirmation dialog using `uiautomator`.
- **Success Criteria:** Confirm whether node depth is <= 6 and whether the exact text "LockKeeper" appears in the node tree.
