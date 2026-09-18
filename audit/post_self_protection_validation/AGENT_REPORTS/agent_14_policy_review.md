# Agent 14: Google Play Policy & Distribution Auditor Report

**Date:** 2026-09-17  
**Auditor:** Agent 14 — Google Play Policy & Distribution Compliance Auditor  
**Target:** Accessibility API Policies, Device Admin Declarations, Store Distribution Risks  
**Status:** COMPLETE (Distribution & Compliance Policy Audit)

---

## 1. Executive Summary

This audit evaluated LockKeeper's self-protection mechanisms against the Google Play Developer Program Policies, specifically focusing on the **Accessibility API Policy**, **Device Administration Policy**, **Deceptive Behavior Policy**, and **Malware/Abuse Prevention**.

**Critical Policy Findings:**
1. **Definite Rejection Risk on Google Play for Consumer App Locker:**
   Google Play's Accessibility Policy explicitly mandates:
   > *"Accessibility APIs may NOT be used to prevent users from uninstalling or disabling any application, or to stop users from changing any device settings, unless authorized by a parent or guardian via parental control software or by enterprise management software."*
   Using an `AccessibilityService` to intercept Settings App Info, Clear Data, Force Stop, and Device Admin deactivation screens directly violates this policy. If submitted as a standard consumer "App Locker", LockKeeper will be rejected or suspended.
2. **Acceptable Distribution Channels:**
   - **Direct / Sideload Distribution (F-Droid, GitHub, APK Distribution):** 100% compliant. Android platform permissions fully support this use case outside Google Play.
   - **Parental Control Declaration on Google Play:** Permissible ONLY IF LockKeeper is declared under the "Parental Controls" category, includes Google Play's required parental disclosure and on-screen consent, and complies with Families Policy requirements.
   - **Enterprise Management / Device Owner:** Fully compliant if configured via EMM/MDM.
3. **Deceptive Behavior Review (Mitigated):**
   `AdminOverlayView` includes prominent branding (`🛡️ LOCKKEEPER ANTI-TAMPER`) and clear copy explaining that LockKeeper is requesting the password. It does not mimic system dialogs or impersonate Android framework alerts.
4. **Google Play Store Exemption (Intentional Compliance):**
   `TamperDetectionEngine.kt` deliberately omits `com.android.vending` (Google Play Store). Users can always uninstall LockKeeper via Google Play. This directly complies with Google Play anti-malware restrictions.

---

## 2. Policy-by-Policy Compliance Breakdown

### 2.1 Accessibility API Policy (Google Play Policy Section: Is Accessibility API Permitted?)

| Clause | Requirement | LockKeeper Implementation | Compliance Status |
|---|---|---|---|
| **Clause 1** | Must provide functionality for users with disabilities or explicitly qualify under designated exemptions. | General app locking and anti-tamper. | **NON-COMPLIANT** (for general consumer store release). |
| **Clause 2** | Cannot change user settings without permission. | Intercepts navigation in Settings via `GLOBAL_ACTION_BACK`. | **NON-COMPLIANT** (Violates Settings navigation restriction). |
| **Clause 3** | Cannot prevent users from uninstalling or disabling apps. | Intercepts App Info, Package Installer, and Device Admin deactivation. | **NON-COMPLIANT** (Direct violation unless classified as Parental Control). |
| **Clause 4** | Prominent in-app disclosure and user consent. | Disclosed during onboarding screen (Step 5). | **COMPLIANT** with disclosure format. |

---

### 2.2 Device Administration Policy
- **Usage:** LockKeeper declares `android.app.device_admin` in manifest and uses `DevicePolicyManager.isAdminActive()`.
- **Policy Standard:** Google Play requires apps requesting Device Admin to clearly declare what admin policies they enforce and why.
- **LockKeeper Declaration:** `res/xml/device_admin_policies.xml` leaves `<uses-policies>` empty.
- **Risk:** Google Play review automated bots flag Device Admin apps with empty `<uses-policies>` as suspicious or non-functional unless explicit enterprise or wipe/lock policies are declared.

---

### 2.3 Store Positioning & Classification Requirements

To ensure legal, sustainable distribution, the product team must explicitly adopt one of the following two positioning models:

```
                            [LockKeeper Architecture]
                                        |
                 +----------------------+----------------------+
                 |                                             |
                 v                                             v
     [Google Play Store Track]                     [Direct Distribution Track]
                 |                                             |
     Requires Official Re-Classification:          Full Capabilities Unrestricted:
     - "Parental Control / Focus Guardian"         - Direct APK / GitHub / F-Droid
     - Families Policy Declaration                 - Enterprise / Sideload
     - Google Play Uninstallation Allowed          - Anti-tamper fully active
```

---

## 3. Invariant Evaluation

| Invariant | Description | Result | Evidence |
|---|---|---|---|
| **INV-POL-1** | App must not impersonate Android system dialogs | **PASS** | `AdminOverlayView` clearly displays LockKeeper branding. |
| **INV-POL-2** | Google Play Store UI must never be intercepted | **PASS** | `com.android.vending` is explicitly omitted from `TamperDetectionEngine`. |
| **INV-POL-3** | Consumer Google Play release must not claim anti-tamper compliance | **FAIL** | Code uses A11y to intercept uninstall without Parental Control declaration. |

---

## 4. Auditor Conclusion

The Self-Protection implementation is technically valid for direct/sideload distribution, but represents a catastrophic policy rejection risk if uploaded to Google Play as a standard consumer App Locker. Store distribution requires either pivoting to an explicit Parental Controls application declaration or removing uninstallation interception for the Play Store build variant.
