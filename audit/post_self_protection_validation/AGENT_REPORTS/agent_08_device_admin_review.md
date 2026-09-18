# Agent 08: Device Admin & Android Security Model Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 08 — Device Admin & Android Security Model Auditor  
**Target:** `LockKeeperDeviceAdminReceiver.kt`, Device Admin Manifest & Policy, DPM Integration  
**Status:** COMPLETE (Hostile Platform Security Analysis)

---

## 1. Executive Summary

This audit evaluated LockKeeper's integration with Android's Device Administration API (`android.app.admin.DevicePolicyManager`), the policy receiver implementation, and the conceptual boundary between ordinary Device Administrator privileges and Device Owner (enterprise) management.

**Critical Findings:**
1. **Device Admin Friction Only (No OS Uninstall Blocking):**
   Ordinary Device Administration on Android **cannot block uninstallation at the OS level**. Only a Device Owner (DO) / Profile Owner (PO) can set `DevicePolicyManager.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)`. LockKeeper's Device Admin integration provides only two platform properties:
   - Android prevents uninstallation while Device Admin is active until the admin is first deactivated.
   - Deactivating Device Admin displays a system warning prompt (`onDisableRequested`).
2. **Deactivation Warning Is Informational Only:**
   `LockKeeperDeviceAdminReceiver.kt:13-16`:
   ```kotlin
   override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
       return "LockKeeper Device Admin protects this device against unauthorized deactivation or uninstallation."
   }
   ```
   This callback returns a `CharSequence` that Android places inside its own standard confirmation dialog. **It cannot cancel or block deactivation.** If an attacker bypasses the Accessibility overlay, they simply tap "Deactivate", and Android permanently revokes admin status.
3. **Empty `onDisabled` Callback (Zero Security Remediation):**
   `LockKeeperDeviceAdminReceiver.kt:18-20`:
   ```kotlin
   override fun onDisabled(context: Context, intent: Intent) {
       super.onDisabled(context, intent)
   }
   ```
   When Device Admin is deactivated, `onDisabled()` is called by the OS. LockKeeper performs ZERO actions:
   - It does not invalidate sessions.
   - It does not notify the repository.
   - It does not emit an urgent notification.
   - It does not revoke credentials.
4. **Empty Policy Declaration:**
   `res/xml/device_admin_policies.xml`:
   ```xml
   <device-admin xmlns:android="http://schemas.android.com/apk/res/android">
       <uses-policies>
           <!-- Friction policy: activation forces user through deactivation screen before uninstall -->
       </uses-policies>
   </device-admin>
   ```
   No specific admin policies (e.g. `limit-password`, `watch-login`, `force-lock`) are requested. This confirms the receiver is used purely as an uninstallation friction hurdle.
5. **False Positive & Denial of Service on Device Admin Settings:**
   In `TamperDetectionEngine.kt:142`, any screen matching `isDeviceAdminScreen` where `hasDeactivateAction` is present triggers an Admin Password gate, falsely blocking deactivation of unrelated third-party admin apps.

---

## 2. Platform Reality: Ordinary Device Admin vs Device Owner

| Capability | Device Owner (DO) | Ordinary Device Admin (LockKeeper) | Code Assumption in LockKeeper |
|---|---|---|---|
| **Disallow Uninstall** | YES (`DISALLOW_UNINSTALL_APPS`) | **NO** (Cannot block OS uninstall) | Correctly recognized in docs; relies on UI overlay interception. |
| **Disallow Clear Data** | YES (`setApplicationHidden`) | **NO** | Relies on Accessibility overlay. |
| **Disallow Force Stop** | YES | **NO** | Relies on Accessibility overlay. |
| **Prevent Deactivation** | YES (Immutable) | **NO** (User can deactivate any time) | Relies on Accessibility overlay over `DeviceAdminAdd`. |
| **Persist Across Safe Mode** | YES | **NO** (Disabled in Safe Mode) | Documented limitation. |

---

## 3. Detailed Audit of Receiver & DPM Usage

### 3.1 DPM Query Correctness
In `LockKeeperAccessibilityService.kt:91-93`:
```kotlin
val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
val adminComponent = ComponentName(this, LockKeeperDeviceAdminReceiver::class.java)
val isAdminActive = dpm?.isAdminActive(adminComponent) == true
```
And in `PlatformChannelHandler.kt:253-256`:
```kotlin
val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
val adminComponent = ComponentName(context, LockKeeperDeviceAdminReceiver::class.java)
dpm.isAdminActive(adminComponent)
```
- Both queries query the live `DevicePolicyManager.isAdminActive(adminComponent)` from the OS framework.
- They do not rely on cached SQLite flags.
- **PASS**: Dynamic state query correctly tracks the real OS state.

### 3.2 Onboarding vs Deactivation Distinction Flaw
In `TamperDetectionEngine.kt:135`:
```kotlin
// Sub-route B: Device Admin Deactivation
// Only trigger if Device Admin is ALREADY active. During onboarding setup, activating admin must be permitted!
if (isDeviceAdminActive) {
    val isDeviceAdminScreen = lowerClass.contains("deviceadmin") ||
            nodeCollector.containsTextOrDesc("device admin") ||
            nodeCollector.containsTextOrDesc("administrador de dispositivos")

    val hasDeactivateAction = nodeCollector.containsAnyTextOrDesc(DEACTIVATE_ADMIN_KEYWORDS)

    if (isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)) {
        return TamperEvent(...)
    }
}
```

#### Flaw Analysis:
1. **The Invariant 2 Violation Hazard:**
   `isDeviceAdminActive` is checked *before* inspecting the screen.
   If admin is active, `isDeviceAdminScreen && (mentionsLockKeeper || hasDeactivateAction)` evaluates to true.
   - If the user opens `Settings -> Security -> Device Admin Apps`, `mentionsLockKeeper` is TRUE because LockKeeper is listed as an active administrator.
   - The engine flags this as `DISABLE_DEVICE_ADMIN`!
   - The user cannot view their admin apps list without entering the Admin Password!
2. **Third-Party Interference:**
   - If the user selects another admin app (e.g., Microsoft Intune or Google Find My Device) and taps "Deactivate", `hasDeactivateAction` is TRUE ("Deactivate this device admin app").
   - Because `(mentionsLockKeeper || hasDeactivateAction)` uses an OR condition, LockKeeper triggers anti-tamper and blocks deactivation of Microsoft Intune or Google Find My Device!
   - This is an unacceptable cross-app false positive.

---

## 4. Invariant Evaluation

| Invariant | Description | Result | Evidence |
|---|---|---|---|
| **INV-2** | Device Admin activity closed must not make Device Admin inactive | **PASS** | `isAdminActive` derives from DPM framework. |
| **INV-7** | Unrelated apps must never trigger LockKeeper's anti-tamper gate | **FAIL** | Deactivating other Device Admin apps triggers LockKeeper's overlay (`TamperDetectionEngine.kt:142`). |
| **INV-8** | Onboarding admin activation must be permitted | **PASS** | When `isDeviceAdminActive == false`, Sub-route B is skipped. |

---

## 5. Auditor Conclusion

While the DPM query correctly reads live framework state, the detection logic confuses *viewing* the Device Admin list with *deactivating* LockKeeper, and blocks deactivation of unrelated admin applications. Furthermore, `onDisabled()` is an empty stub with zero incident handling.
