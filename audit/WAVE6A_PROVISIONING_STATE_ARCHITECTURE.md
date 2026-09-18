# Wave 6A — Provisioning State Architecture & Evaluation of Alternative Designs

**Target Repository:** `C:\AppLocker`  
**Scope:** Architectural Options Analysis, Three-Way State Modeling, Invariant Preservation, Boundary Conditions  
**Date:** 2026-09-18  
**Contributors:** Agent 4 (Security Architect), Agent 6 (Distribution Reviewer)  

---

## 1. Context & Architectural Requirements

Wave 6A investigates the handling of an installation where:
- **Device Administrator is ACTIVE** in Android OS (`/data/system/device_policies.xml`).
- **All private application data is GONE** (`databases/`, `shared_prefs/`, Keystore keys wiped).
- The application runtime must evaluate its posture upon restart.

### The Mandatory Security Invariants (Stage 4)
The chosen architecture **MUST NOT** produce either of these fatal failure modes:

> **BAD STATE 1 (Silent Protected-App Bypass):**
> A previously provisioned installation suffers local data loss, silently pretends to be an ordinary fresh install, and disables security enforcement while misleading the user or administrator that protection remains active.

> **BAD STATE 2 (Device-Wide Lockout / Home-Screen Recursion):**
> A legitimate first-run user grants Device Administrator at Step 5/9, and the application immediately infers a security breach (missing credentials), triggers `RECOVERY_REQUIRED`, dispatches `GLOBAL_ACTION_HOME`, and traps the user in an infinite launcher lockout loop.

---

## 2. Stage 3: Evaluation of Architectural Options

We evaluated three structural designs against platform realities:

### OPTION A: Existing Room-Latch Model Only
- **Mechanism:** Room database `app_settings` holds `securityProvisioned: Boolean` (latched to `true` at Step 9/9).
  - If Room is intact and credentials are lost -> `RECOVERY_REQUIRED`.
  - If Room is completely erased -> Defaults insert `securityProvisioned = false` -> `SETUP_IN_PROGRESS`.
- **Pros:** Completely eliminates BAD STATE 2 (zero risk of onboarding lockout). 100% reliable for in-app attacks and partial corruptions.
- **Cons:** After `pm clear` from ADB, reverts to `SETUP_IN_PROGRESS`.
- **Persistence:** Bound to private application sandbox.
- **Spoofability:** Cannot be spoofed from user-space UI, but wiped by ADB shell.

### OPTION B: Platform-Backed External Persistence
- **Mechanism:** Storing a provisioning marker outside the private sandbox (e.g., shared external storage, contacts, media metadata, or DevicePolicyManager covert channels).
- **Analysis:**
  - *External Storage:* Scoped storage requires user permission; external files are unauthenticated and easily spoofed or deleted by any other app on the device.
  - *Device Admin Policies:* As proven in `WAVE6A_PLATFORM_PERSISTENCE_ANALYSIS.md`, consumer `DeviceAdminReceiver` has NO custom data API, and modifying lock-screen password policies endangers the entire phone lock screen.
  - *Enterprise Device Owner:* Incompatible with standard consumer Google Play distribution.
- **Verdict:** **REJECTED.** No secure, non-spoofable, consumer-compatible external platform storage exists on Android.

### OPTION C: Three-Way State Model with `PROVISIONING_STATE_UNKNOWN`
- **Mechanism:** Introduce an explicit intermediate state when ambiguous indicators are detected:
  - `isAdminActive == true` AND `securityProvisioned == false` AND `hasCreds == false`.
- **Semantics:**
  1. `SETUP_IN_PROGRESS`: Fresh install, Device Admin **not yet granted** (`isAdminActive == false`).
  2. `PROVISIONING_STATE_UNKNOWN`: Device Admin **is active**, but no local credentials or provisioning records exist in Room.
  3. `SECURITY_PROVISIONED`: Confirmed fully provisioned with credentials and latched `securityProvisioned == true`.
  4. `RECOVERY_REQUIRED`: Confirmed previously provisioned, but credentials missing or state corrupted.
- **Pros:** Makes the ambiguity explicit in logs, UI, and diagnostics. Does not claim "confirmed fresh" when Device Admin is active.
- **Cons:** Requires defining exact runtime enforcement behavior for `PROVISIONING_STATE_UNKNOWN`.

---

## 3. Stage 5 & 6: Detailed Analysis of the Three-Way State Model

### 3.1 Can `PROVISIONING_STATE_UNKNOWN` Block Protected Apps?
Suppose the runtime enters `PROVISIONING_STATE_UNKNOWN` after `pm clear`.
- What does LockKeeper block?
- In `ProtectionRepository`, locked apps are stored in Room: `locked_apps` table.
- When `pm clear` ran, the `locked_apps` table was **completely deleted**.
- LockKeeper has **zero knowledge of what apps were previously locked**!
- If LockKeeper attempts to "fail closed" by blocking *everything*, it would block the Phone, Settings, and Launcher, recreating **BAD STATE 2**.
- If LockKeeper does not block apps because its database is empty, then `LockDecisionEngine` naturally allows normal apps.

### 3.2 What MUST `PROVISIONING_STATE_UNKNOWN` Do?
If the system detects `isAdminActive == true` while `securityProvisioned == false` (whether due to incomplete onboarding or post-`pm clear` restart):
1. **Never Dispatch `GLOBAL_ACTION_HOME` on Launcher:**
   `isLauncherOrSystemUiPackage()` must continue to unconditionally protect the launcher and System UI.
2. **Never Falsely Claim "Active Protection":**
   The UI, notification bar, and system status must explicitly state: **"Setup Incomplete / Provisioning Required"**, never claiming that any apps are locked.
3. **Streamline Onboarding:**
   When LockKeeper is opened, it recognizes that Device Admin is already active (Step 5 is already green), skips redundant permission requests, and prompts the user directly to complete credential creation.
4. **Log State Honestly:**
   Diagnostics and security status return `SETUP_IN_PROGRESS` or `PROVISIONING_STATE_UNKNOWN`, acknowledging that local credentials do not exist.

---

## 4. Invariant Comparison Matrix

| Invariant | Option A (Current) | Option B (External Latch) | Option C (Explicit Unknown Model) |
|---|---|---|---|
| **Prevents Bad State 1 (No silent bypass without acknowledgement)** | Partial (Reverts to Setup) | Poor (Spoofable) | **High** (Explicitly reports unprovisioned/unknown state) |
| **Prevents Bad State 2 (No Home lockout)** | **100% Safe** | Unpredictable | **100% Safe** |
| **Compatible with Consumer Distribution** | **Yes** | No | **Yes** |
| **Survives Process Restart & Reboot** | **Yes** | Yes | **Yes** |
| **Survives `pm clear` via ADB** | Reverts to Setup | Fails / Corrupted | Reverts to Setup with explicit state |

---

## 5. Architectural Recommendation

The safest, most defensible design for consumer Android is **Option C conceptually mapped onto the robust foundation of Option A**:
1. Within the application, recognize that `isAdminActive && !securityProvisioned` is the transitional state shared by **Step 5 onboarding** and **post-`pm clear` restart**.
2. Preserve general device usability (zero Home recursion).
3. Do not claim protection when local state is absent.
4. Require the user to complete full credential setup before any application locking can engage.
5. Formally document the ADB `pm clear` boundary as an inherent Android platform limitation.
