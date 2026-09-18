# Wave 6A — Security Architecture & Platform Boundary Report

**Target Repository:** `C:\AppLocker`  
**Date:** 2026-09-18  
**Lead Security Reviewer:** Agent 4 (Security Architect)  
**Verification Standard:** Strict Evidence Gate, Zero Unproven Assumptions  

---

## 1. Executive Security Evaluation

This investigation was commissioned to evaluate whether LockKeeper can reliably distinguish:
> **Case A:** A genuinely new installation  
> *from*  
> **Case B:** A previously provisioned installation that suffered complete private data loss (`pm clear`) while Device Administrator remained active in the Android OS.

### Definitive Finding:
**No standard consumer Android application can distinguish Case A from Case B.**

### Why?
1. In Android, `pm clear` unlinks the app's entire private directory (`/data/user/0/<pkg>/`) and purges all keys from the Android Keystore.
2. Device Administrator status in `system_server` survives `pm clear`.
3. However, during normal first-run onboarding (at Step 5 of 9), the user is prompted to grant Device Administrator **before** creating a PIN or Admin Password.
4. Consequently, at Step 5 of onboarding, the platform state is:
   $$\{ \text{isAdminActive}=\text{true}, \text{hasPin}=\text{false}, \text{hasAdminPassword}=\text{false}, \text{securityProvisioned}=\text{false} \}$$
5. Following `pm clear` on a previously provisioned app, the platform state is:
   $$\{ \text{isAdminActive}=\text{true}, \text{hasPin}=\text{false}, \text{hasAdminPassword}=\text{false}, \text{securityProvisioned}=\text{false} \}$$
6. Because both states are physically and logically identical to any code executing on the device, **any attempt to infer "prior provisioning" solely from `isAdminActive == true` immediately triggers `RECOVERY_REQUIRED` during legitimate fresh onboarding at Step 5**, re-introducing the fatal Home-screen lockout incident.

---

## 2. Threat Surface & Defense-in-Depth Analysis

While complete private data loss via ADB resets the app to onboarding, we evaluated the resistance of the architecture against non-ADB attacks:

```
+-------------------------------------------------------------------------------+
|                             ATTACK VECTOR BREAKDOWN                           |
+-----------------------------+-----------------------+-------------------------+
| Attacker Profile            | Target Action         | Defense Outcome         |
+-----------------------------+-----------------------+-------------------------+
| Physical Borrower / Thief   | Clear data in Settings| BLOCKED: AdminOverlay   |
| (Without ADB / Root)        |                       | blocks Settings access  |
+-----------------------------+-----------------------+-------------------------+
| Physical Borrower / Thief   | Uninstall LockKeeper  | BLOCKED: Device Admin   |
|                             |                       | prevents uninstall      |
+-----------------------------+-----------------------+-------------------------+
| Physical Borrower / Thief   | Modify SharedPreferences| BLOCKED: Room DB is   |
|                             | or inject method call | authoritative source    |
+-----------------------------+-----------------------+-------------------------+
| Physical Borrower / Thief   | Reboot device         | BLOCKED: Room & Keystore|
|                             |                       | survive reboot intact   |
+-----------------------------+-----------------------+-------------------------+
| Forensic Attacker with ADB  | `adb shell pm clear`  | DATA WIPED: Reverts to  |
| / Physical USB debugging    |                       | fresh onboarding        |
+-----------------------------+-----------------------+-------------------------+
| Hardware Boot               | Android Safe Mode     | OS disables all 3rd-party|
|                             |                       | apps by kernel design   |
+-----------------------------+-----------------------+-------------------------+
```

### Defense-in-Depth Findings:
1. On a locked or borrowed phone in normal operation, **an attacker cannot clear app data through the UI** because `com.android.settings` is covered by `AdminOverlayView` requiring the Admin Password.
2. The only vector that can execute `pm clear` is **direct ADB debugging** or **root shell execution**.
3. Under Android's security model, ADB shell is an elevated management interface. Any app locker claiming to survive `pm clear` without an enterprise Device Owner profile is making false claims that contradict Android OS architecture.

---

## 3. Stage 9 Decision: Formal Selection

In accordance with Stage 9 of Wave 6A:
- **DECISION A:** Reliable platform-backed provisioning evidence exists. -> *False; disproven by Agents 1, 2, and 3.*
- **DECISION B:** Complete data-loss distinction is impossible for this consumer-app architecture and is therefore a documented platform boundary. -> **SELECTED (Supported 100% by platform evidence).**
- **DECISION C:** A safe UNKNOWN_PROVISIONING state is required. -> *Evaluated; behaves identically to setup mode because `locked_apps` is empty.*
- **DECISION D:** Current security requirements cannot be met without stronger device management privileges. -> *Applies only if enterprise EMM/Device Owner requirements are mandated.*

### Formal Selection:
```
================================================================================
DECISION B:
Complete data-loss distinction is impossible for this consumer-app architecture
and is therefore a documented platform boundary.
================================================================================
```

---

## 4. Architectural Rules Upheld

1. **Never Reintroduce Home-Screen Lockout:**
   The primary rule of the project is upheld: granting Device Administrator before PIN setup never triggers an unexpected lockout.
2. **Never Weaken Partial-Loss Fail-Closed Recovery:**
   Whenever the Room database survives, missing credentials or corrupted settings force `RECOVERY_REQUIRED`.
3. **No False Claims:**
   The documentation explicitly discloses that ADB `pm clear` wipes local locked app rules and returns the application to onboarding.
