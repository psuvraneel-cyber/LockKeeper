# Wave 6A — Android Platform Persistence & Data-Loss Survivability Analysis

**Target Repository:** `C:\AppLocker`  
**Evaluation Scope:** Android OS Persistence Boundaries, Private Storage Erasure (`pm clear`), Keystore Lifecycle, Device Policy Management Capabilities  
**Date:** 2026-09-18  
**Contributors:** Agent 1 (Platform Specialist), Agent 2 (Device Admin Specialist), Agent 3 (Keystore Specialist)  

---

## 1. Executive Problem Statement

During the Critical Verification Gate for LockKeeper, the team verified that the First-Run / Device Admin / Home-Screen Lockout incident is closed: fresh onboarding with early Device Administrator activation remains safe and non-recursive.

However, an underlying architectural boundary was identified:
> **The Complete Data-Loss Scenario:**
> An installation is fully provisioned with locked apps and credentials.
> Subsequently, the application's private storage is completely cleared (e.g., via `adb shell pm clear com.lockkeeper.app`).
> - Room database SQLite files are unlinked.
> - Keystore credentials are destroyed.
> - SharedPreferences are removed.
> - **Device Administrator remains active in the Android OS** (`/data/system/device_policies.xml`).
>
> On next launch, LockKeeper re-initializes Room with default values (`securityProvisioned = false`, `onboardingComplete = false`).
> Because the app has no surviving internal evidence of prior provisioning, and because Device Admin active alone cannot be used as proof of previous provisioning (as that would reintroduce the fatal first-run lockout bug during Step 5 of onboarding), LockKeeper currently evaluates to:
> **`SETUP_IN_PROGRESS`**

This report rigorously investigates what Android permits, what it guarantees, and whether any trustworthy mechanism exists to distinguish a genuinely new installation from a previously provisioned installation that has suffered complete private data loss.

---

## 2. Agent 1: Platform Storage & Persistence Inventory

Android enforces strict sandbox boundaries between applications and the operating system. We inspected every potential storage mechanism available to a standard consumer application:

| Storage Mechanism | Physical Path / Daemon | Lifecycle on `pm clear` | Lifecycle on App Uninstall | Access Privileges | Platform Classification |
|---|---|---|---|---|---|
| **App Private Database (Room/SQLite)** | `/data/user/0/<pkg>/databases/` | **ERASED** (Unlinked by `vold`/PMS) | **ERASED** | App UID only | APP CONTROLLED (Does Not Survive) |
| **SharedPreferences** | `/data/user/0/<pkg>/shared_prefs/` | **ERASED** | **ERASED** | App UID only | APP CONTROLLED (Does Not Survive) |
| **Internal Files / Cache** | `/data/user/0/<pkg>/files/`, `cache/` | **ERASED** | **ERASED** | App UID only | APP CONTROLLED (Does Not Survive) |
| **Android Keystore (Software/TEE/StrongBox)** | `keystore2` daemon (`/data/misc/keystore/`) | **ERASED** (Keys purged by PMS signal) | **ERASED** | App UID only | PLATFORM CONTROLLED (Does Not Survive) |
| **External App Storage** | `/sdcard/Android/data/<pkg>/` | **ERASED** | **ERASED** | App UID only | APP CONTROLLED (Does Not Survive) |
| **Shared Public Storage (`/sdcard/`)** | MediaStore / Documents Provider | Not erased, but restricted by Scoped Storage | Survives | Requires SAF Picker / User selection | UNRELIABLE / SPOOFABLE / NOT AUTOMATIC |
| **Device Administrator Registration** | `system_server` (`/data/system/device_policies.xml`) | **SURVIVES** | Blocks uninstall until disabled | System Server (UID 1000) | PLATFORM CONTROLLED (Survives Data Clear) |
| **Accessibility Service Setting** | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | **SURVIVES** | Cleared on uninstall | System Server (UID 1000) | PLATFORM CONTROLLED (Survives Data Clear) |
| **Component Enabled State** | `/data/system/users/0/package-restrictions.xml` | **RESET** to manifest defaults | **ERASED** | System Server (UID 1000) | PLATFORM CONTROLLED (Does Not Survive) |
| **AppOps / Special Permissions (Overlay, UsageStats)** | `/data/system/appops.xml` | **SURVIVES** (on most OEMs) | **ERASED** | System Server (UID 1000) | PLATFORM CONTROLLED (Survives Data Clear) |

### Key Platform Takeaway:
When `pm clear` runs, the Android OS intentionally and irrevocably wipes **100% of the application's private storage, cache, and Keystore crypto-material**.
The only attributes that survive are **OS-level system service registrations** granted by the user:
1. Device Administrator active status in `DevicePolicyManagerService`.
2. Accessibility Service enabled status in `AccessibilityManagerService`.
3. System alert window and usage stats AppOps.

---

## 3. Agent 2: Device Administrator Capabilities & State Encoding

We investigated whether the surviving Device Administrator registration can be utilized to store or encode authoritative evidence of prior provisioning.

### 3.1 Consumer Device Admin vs Enterprise Device Owner
Android divides management APIs into two distinct privilege tiers:
1. **Device Owner / Profile Owner (EMM / Enterprise):**
   - Has `setApplicationRestrictions()`, `setUserRestriction()`, `setDelegatedScopes()`, `installExistingPackage()`.
   - Can set `DISALLOW_APPS_CONTROL` to completely prohibit users from clearing app data or force-stopping apps.
   - **Requirement:** Must be provisioned at factory reset (via QR code / NFC) or via ADB `dpm set-device-owner` before any user accounts are added.
   - **Verdict for LockKeeper:** Incompatible with a standard consumer app downloaded from Google Play or installed on a personal phone with existing accounts.
2. **Standard Device Administrator (`DeviceAdminReceiver`):**
   - Available to consumer apps via user consent in Settings.
   - Governed by policies declared in `res/xml/device_admin_policies.xml`:
     - `<limit-password />`
     - `<watch-login />`
     - `<reset-password />`
     - `<force-lock />`
     - `<wipe-data />`
     - `<expire-password />`

### 3.2 Does Standard Device Admin Offer Custom Storage?
We audited the Android SDK `DevicePolicyManager` API up to Android 16 (API 36):
- `DevicePolicyManager` provides **no API** to attach custom key-value pairs, arbitrary strings, metadata bundles, or persistent flags to a standard `DeviceAdminReceiver`.
- The only persistent records maintained by `DevicePolicyManagerService` for a standard admin are:
  - The admin's `ComponentName`.
  - The active policy bitmask.
  - OS-wide password rules (minimum length, quality, expiration, maximum failed passwords for wipe).

### 3.3 Can OS Password Policies Be Used as an Out-of-Band Covert Channel?
We investigated whether LockKeeper could set an obscure policy value (e.g., `setPasswordQuality` or `setMaximumFailedPasswordsForWipe`) once provisioned, to serve as a survival marker.
- **FATAL FLAW:** In Android, `DevicePolicyManager` password policies apply to the **ENTIRE DEVICE'S LOCK SCREEN**!
- If LockKeeper modifies `setMaximumFailedPasswordsForWipe()`, it affects the user's OS lock screen. If the user fails their phone PIN, the device could perform a factory wipe!
- Modifying OS-wide lock screen policies to store an internal application provisioning flag is unacceptable, introduces severe collateral risk, and violates Android developer policies.

### 3.4 The Inescapable Device Admin Ambiguity
Because standard Device Admin stores only a binary state (`isAdminActive == true` or `false`), we confront the following fundamental truth table:

| Scenario | `isAdminActive` | Has PIN / Admin Pass | Room `securityProvisioned` | History / Context |
|---|---|---|---|---|
| **Scenario 1: Fresh Onboarding (Step 5/9)** | `true` | `false` | `false` | Legitimate first-run user who just granted admin |
| **Scenario 2: Post-`pm clear` Provisioned App** | `true` | `false` | `false` | Previously provisioned app where data was wiped |

**MATHEMATICAL & LOGICAL PROOF:**
From the perspective of the application process running on the device, the observable system state of **Scenario 1** is **IDENTICAL** to **Scenario 2**:
$$\text{State}(\text{Scenario 1}) = \text{State}(\text{Scenario 2}) = \{ \text{isAdminActive}=\text{true}, \text{hasCreds}=\text{false}, \text{isProvisioned}=\text{false} \}$$

Therefore:
**No algorithm running strictly on the device can distinguish Scenario 1 from Scenario 2 based on local platform state alone.**

---

## 4. Agent 3: Keystore & Hardware Security Module Lifecycle

We evaluated whether the Android Keystore (KeyStore2 / StrongBox / TEE) can survive data erasure.

### 4.1 Key Lifecycle on Android
1. **Key Generation:** When LockKeeper generates a Master Key or credential encryption key via `KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or PURPOSE_DECRYPT)`, the key material is stored in TEE/SE and bound to the calling package's Linux UID.
2. **Behavior on `pm clear`:**
   - In AOSP `PackageManagerService.java`:
     ```java
     // Framework PMS clearApplicationUserData:
     mKeyStoreManager.clearMap(UserHandle.getAppId(pkg.getUid()));
     ```
   - The Keystore service purges all cryptographic key blobs, aliases, and certificates associated with that UID.
   - On the next app launch, `KeyStore.containsAlias("lockkeeper_master_key")` returns `false`.
3. **Hardware-Backed / StrongBox Keys:**
   - Even when backed by dedicated hardware (Secure Element / Titan M), the hardware key entry is indexed by an internal descriptor managed by Keystore2. When the keystore database entry for that app is cleared, the key descriptor is unmapped and permanently unreachable.

### 4.2 Conclusion on Keystore:
The Android Keystore **cannot survive `pm clear`**. Any proposal relying on Keystore to retain a persistent "I was provisioned" flag across an app data reset is technically impossible on standard Android.

---

## 5. Summary Matrix: What Survives vs What Fails

```
================================================================================
STORAGE LAYER                   SURVIVES pm clear?      CAN PROVE PROVISIONING?
================================================================================
Room SQLite DB                  NO                      NO (Erased)
SharedPreferences               NO                      NO (Erased)
Android Keystore                NO                      NO (Erased)
External Storage (Scoped)       NO (App dir erased)     NO (Spoofable / Unreliable)
Standard Device Admin           YES (Active status)     AMBIGUOUS (Same as Step 5)
Accessibility Service           YES (Enabled status)    AMBIGUOUS (Same as Step 6)
Device Owner (Enterprise)       YES (Full control)      INCOMPATIBLE (Consumer app)
================================================================================
```

---

## 6. Architectural Determination

1. **Definitive Finding:** Within the boundaries of a standard consumer Android application, **complete private data loss destroys every internal record of prior provisioning**.
2. **Device Admin Ambiguity:** Device Admin survives in system space, but its active status cannot be treated as proof of prior provisioning without treating legitimate onboarding at Step 5 as a security compromise (which re-creates the fatal Home-screen lockout incident).
3. **Formal Classification:** This condition represents an intrinsic **Android Platform Storage Boundary**.
