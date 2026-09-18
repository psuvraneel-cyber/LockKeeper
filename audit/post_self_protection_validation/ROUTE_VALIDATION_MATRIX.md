# Route Validation Matrix

**Project:** LockKeeper (C:\AppLocker)  
**Phase:** Post-Self-Protection Adversarial Validation  
**Date:** 2026-09-17  
**Validator:** Agent 15 (Lead Security Validation Engineer)

---

## 1. Route Status Matrix

| ID | Route | Detection | Password Gate | Confidence | False Positive Risk | Bypass Risk | Runtime Required | Notes |
|---|---|---|---|---|---|---|---|---|
| **R-01** | Launcher Context Menu -> Uninstall | PARTIAL | PARTIAL | MEDIUM | LOW | HIGH | YES | Dependent on whether launcher uses system uninstaller or internal dialog. |
| **R-02** | Launcher Drag -> Top Bar Uninstall | PARTIAL | PARTIAL | MEDIUM | LOW | HIGH | YES | System PackageInstaller dialog caught if app name is in text. Fails if generic prompt. |
| **R-03** | Launcher Context Menu -> App Info | PASS | PASS | HIGH | LOW | LOW | NO | Re-routes directly into Settings App Info (Route R-04). |
| **R-04** | Settings -> Apps -> App Info -> Uninstall | PARTIAL | PARTIAL | HIGH | LOW | HIGH | YES | Fails if node depth > 6 or node count > 45; vulnerable to soft-keyboard dismissal. |
| **R-05** | Settings -> Storage -> Clear Data | PARTIAL | PARTIAL | MEDIUM | LOW | HIGH | YES | Fails if dialog omits app name or uses non-standard class (ManageSpaceActivity). |
| **R-06** | Settings -> Force Stop | PARTIAL | PARTIAL | HIGH | LOW | HIGH | YES | Bypassed if App Info buttons are pruned by 45-node limit. Once stopped, all protection dies. |
| **R-07** | Settings -> Disable App | PARTIAL | PARTIAL | HIGH | LOW | HIGH | YES | Same as R-04. |
| **R-08** | Settings -> Accessibility -> Toggle Off | PARTIAL | PARTIAL | MEDIUM | LOW | HIGH | YES | Bypassed on German, Italian, Russian, Chinese locales or custom OEM detail fragments. |
| **R-09** | Settings -> Device Admin -> Deactivate | FAIL | PARTIAL | HIGH | CRITICAL | HIGH | YES | Severe false positive on browsing admin list; blocks deactivation of unrelated apps. |
| **R-10** | Package Installer System Dialog | PARTIAL | PARTIAL | HIGH | LOW | HIGH | YES | Requires mentionsLockKeeper; fails if dialog shows only icon and generic message. |
| **R-11** | OEM Package Installer (HyperOS/ColorOS) | PARTIAL | PARTIAL | LOW | LOW | HIGH | YES | Only 7 package names whitelisted; missing Vivo, Transsion, Honor variants. |
| **R-12** | OEM Security Center (Xiaomi/Samsung) | PARTIAL | PARTIAL | LOW | LOW | HIGH | YES | Only 3 packages checked; deep cleaners and task killers evade inspection. |
| **R-13** | Google Play Store App Management | FAIL | FAIL | HIGH | NOT APPLICABLE | 100% BYPASS | NO | Deliberately omitted from engine; Google Play uninstall is completely unrestricted. |
| **R-14** | Alternate Launchers (Nova, Niagara, etc.)| FAIL | FAIL | HIGH | LOW | 100% BYPASS | NO | Launcher packages rejected by package filter; internal uninstalls fully bypass. |
| **R-15** | Alternate Settings Activities / SpaActivity| PARTIAL| PARTIAL | MEDIUM | LOW | HIGH | YES | Deep layouts in Android 13/14 SpaActivity exceed depth 6 and node limit 45. |
| **R-16** | Alternate Fragments / Tabbed Navigation | FAIL | FAIL | HIGH | LOW | HIGH | YES | Service drops TYPE_WINDOW_CONTENT_CHANGED; tab switching is invisible. |
| **R-17** | Intent-Driven Package Management | PARTIAL | PARTIAL | LOW | LOW | HIGH | YES | Dependent on whether intent target is within whitelisted packages. |
| **R-18** | Notification FGS Task Manager | FAIL | FAIL | HIGH | LOW | 100% BYPASS | YES | FGS manager lives in com.android.systemui, which is not inspected. |
| **R-19** | Package Management Dialogs (System Alert)| PARTIAL| PARTIAL | MEDIUM | LOW | HIGH | YES | Vulnerable to TYPE_APPLICATION_OVERLAY z-order layering. |
| **R-20** | Settings Search Inline Actions | FAIL | FAIL | HIGH | LOW | 100% BYPASS | YES | Handled by com.google.android.settings.intelligence, which is not whitelisted. |

---

## 2. Summary Statistics

- **Total Routes Evaluated:** 20
- **Fully Covered & Secure (PASS):** 1 (Route 03 - App Info Redirect)
- **Partially Covered with Bypass Risks (PARTIAL):** 13
- **Completely Unsupported or Failing (FAIL):** 6 (Routes 09, 13, 14, 16, 18, 20)
- **Routes Requiring Runtime Device Validation:** 15
