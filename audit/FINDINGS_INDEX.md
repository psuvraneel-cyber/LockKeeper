# LockKeeper Master Findings Index

**Audit Date**: September 17, 2026  
**Auditor**: Lead Security & Software Quality Auditor  
**Total Validated Findings**: 32  

---

## Master Findings Table

| ID | Severity | Confidence | Category | Title | Affected Files | Status |
|---|---|---|---|---|---|---|
| **SEC-01** | CRITICAL | CONFIRMED | Anti-Tamper / Bypass | Hardcoded English String Matching in Accessibility Allows 100% Anti-Tamper Bypass | `LockKeeperAccessibilityService.kt` | CONFIRMED |
| **SEC-02** | CRITICAL | CONFIRMED | Architecture / Privileges | Device Admin Architectural Fallacy: Ordinary Admin Does Not Provide Device Owner Protection | `LockKeeperDeviceAdminReceiver.kt`, `accessibility_service_config.xml` | CONFIRMED |
| **SEC-03** | CRITICAL | CONFIRMED | Anti-Tamper / Scope | Anti-Tamper Scope Limited to `com.android.settings`, Ignoring Launchers & OEM Centers | `LockKeeperAccessibilityService.kt`, `AndroidManifest.xml` | CONFIRMED |
| **SEC-04** | CRITICAL | CONFIRMED | Authentication / DoS | SelfLockGateScreen Hardcoded Auto-Submit at 4 Digits Permanently Locks Out 5–8 Digit PIN Users | `self_lock_gate_screen.dart`, `onboarding_screen.dart`, `settings_screen.dart` | CONFIRMED |
| **SEC-05** | CRITICAL | CONFIRMED | Anti-Tamper / Bypass | Unrestricted Clear-Data in Settings Wipes Keystore-Backed Storage & Protection | `LockKeeperAccessibilityService.kt`, `AndroidManifest.xml` | CONFIRMED |
| **SEC-06** | HIGH | CONFIRMED | Lifecycle / Auth | Flutter Root State Desynchronization Disables Self-Lock Gate on Initial Launch | `main.dart`, `onboarding_screen.dart` | CONFIRMED |
| **SEC-07** | HIGH | CONFIRMED | Authentication / Brute-Force | Unthrottled `verifyPin` and `verifyAdminPassword` Platform IPC Enables Unlimited Brute-Force | `PlatformChannelHandler.kt`, `settings_screen.dart` | CONFIRMED |
| **SEC-08** | HIGH | CONFIRMED | Authentication / Brute-Force | Absence of Attempt Throttling or Lockout on Admin Password | `AdminOverlayView.kt`, `OverlayManager.kt`, `PlatformChannelHandler.kt` | CONFIRMED |
| **SEC-09** | HIGH | CONFIRMED | Window Security | Missing `FLAG_SECURE` on Native Overlay Windows Allows Credential Sniffing | `OverlayManager.kt`, `PinOverlayView.kt`, `AdminOverlayView.kt` | CONFIRMED |
| **SEC-10** | HIGH | CONFIRMED | Concurrency / Race Condition | TOCTOU Lost-Update Race in Failed PIN Lockout Handler | `ProtectionRepository.kt`, `AppSettingsDao.kt` | CONFIRMED |
| **SEC-11** | HIGH | CONFIRMED | Lifecycle / Service | BroadcastReceiver Coroutine Execution in BootReceiver Drops Startup Without `goAsync()` | `BootReceiver.kt` | HIGH CONFIDENCE |
| **SEC-12** | HIGH | CONFIRMED | Service Fallback | Foreground Service Fallback Explicitly Ignores Settings, Leaving Zero Tamper Protection | `LockKeeperForegroundService.kt` | CONFIRMED |
| **SEC-13** | HIGH | CONFIRMED | Session Management | Unbounded In-Memory App Session Lifetimes in LockDecisionEngine | `LockDecisionEngine.kt` | CONFIRMED |
| **SEC-14** | HIGH | CONFIRMED | Performance / ANR | Synchronous PBKDF2 (65,536 Iterations) Computation on Main UI Thread | `OverlayManager.kt`, `AdminOverlayView.kt`, `CredentialStore.kt` | CONFIRMED |
| **SEC-15** | HIGH | CONFIRMED | Reliability / Battery | 400ms Continuous UsageStats Polling Loop in Fallback Mode Drains Battery | `LockKeeperForegroundService.kt` | CONFIRMED |
| **SEC-16** | HIGH | CONFIRMED | Build / Release | Release Build Insecurely Falls Back to Android Debug Keystore Signing | `android/app/build.gradle.kts` | CONFIRMED |
| **SEC-17** | HIGH | CONFIRMED | Data Protection / Backup | Android Cloud Auto-Backup of Hardware Keystore Blobs Causes Permanent Lockout | `AndroidManifest.xml`, `CredentialStore.kt` | CONFIRMED |
| **SEC-18** | HIGH | CONFIRMED | Database Reliability | Destructive Migration Fallback Enabled with Disabled Schema Exports | `AppDatabase.kt`, `build.gradle.kts` | CONFIRMED |
| **SEC-19** | HIGH | CONFIRMED | Error Handling / Fail-Open | Fail-Open Exception Handling on Self-Lock Gate Check Automatically Unlocks UI | `main.dart`, `platform_bridge.dart` | CONFIRMED |
| **SEC-20** | MEDIUM | CONFIRMED | Privacy / Information Leak | Production Debug Logging of Navigation Classes and Settings Events via `Log.d` | `LockKeeperAccessibilityService.kt` | CONFIRMED |
| **SEC-21** | MEDIUM | CONFIRMED | Information Disclosure | Target App Visual Leak During Overlay Back/Cancel Navigation | `OverlayManager.kt` | CONFIRMED |
| **SEC-22** | MEDIUM | CONFIRMED | Information Disclosure | Recents Overview Task Snapshot Leaks Unprotected App Frames | `LockKeeperAccessibilityService.kt`, `OverlayManager.kt` | CONFIRMED |
| **SEC-23** | MEDIUM | CONFIRMED | Code Quality | Systemic Silent Exception Swallowing Across 24 Platform Bridge Calls | `platform_bridge.dart` | CONFIRMED |
| **SEC-24** | MEDIUM | CONFIRMED | Accessibility Verification | Substring Matching & Stale Connected Status in Accessibility State Check | `PlatformChannelHandler.kt` | CONFIRMED |
| **SEC-25** | MEDIUM | CONFIRMED | UI/UX Consistency | Static 4-Dot Display in SelfLockGateScreen Misrepresents Dynamic 4–8 Digit PINs | `self_lock_gate_screen.dart` | CONFIRMED |
| **SEC-26** | MEDIUM | CONFIRMED | Usability / Accessibility | Lack of WCAG Semantic Annotations on Numeric Keypad Controls | `self_lock_gate_screen.dart`, `PinOverlayView.kt` | CONFIRMED |
| **SEC-27** | MEDIUM | CONFIRMED | Code Obfuscation | R8 Minification and Code Obfuscation Disabled in Release Build | `android/app/build.gradle.kts` | CONFIRMED |
| **SEC-28** | MEDIUM | CONFIRMED | Platform Compatibility | TargetSdk & CompileSdk Set to Unfinalized Android 16 Preview (API 36) | `android/app/build.gradle.kts` | CONFIRMED |
| **SEC-29** | LOW | CONFIRMED | Time Manipulation | System Wall-Clock Manipulation Can Indefinitely Extend Admin Grace Window | `LockDecisionEngine.kt` | CONFIRMED |
| **SEC-30** | LOW | CONFIRMED | UI/UX Integrity | Hardcoded "Active" Summary Indicators in Onboarding Setup Wizard | `onboarding_screen.dart` | CONFIRMED |
| **SEC-31** | LOW | CONFIRMED | Memory Hygiene | Plaintext Credentials Stored in Immutable Heap String Objects | `self_lock_gate_screen.dart`, `PlatformChannelHandler.kt` | CONFIRMED |
| **SEC-32** | LOW | CONFIRMED | Dead Code | Unused `schemaVersion` Column in AppSettingsEntity | `AppSettingsEntity.kt` | CONFIRMED |

---

## Severity Breakdown

- **CRITICAL**: 5
- **HIGH**: 14
- **MEDIUM**: 9
- **LOW**: 4
- **Total Validated Findings**: 32
- **Status Tally**: 31 Confirmed (Source-proven), 1 High Confidence (Runtime lifecycle dependent).
