# WAVE 6: UPGRADE, REINSTALL & PERSISTENCE VALIDATION
## IN-PLACE APPLICATION UPGRADE, STORAGE CLEARS, AND STATE RESILIENCE AUDIT

---

### 1. Executive Summary

This validation assesses LockKeeper's state resilience during in-place application updates ($V_N \rightarrow V_{N+1}$), uninstallation cycles, system cache purges, aggressive user-initiated storage clears, and interrupted installations.

The critical security requirement is:
> *"No application upgrade or data disruption may silently disable protection, downgrade security checks, bypass lockouts, or drop into an unmanaged, unauthenticated state."*

---

### 2. Version Upgrade Scenarios ($V_N \rightarrow V_{N+1}$)

Simulated and verified across schema revisions (Room v1 through v4):

| Upgrade Scenario | Existing State Before Upgrade | Upgrade Execution | Observed State After Upgrade | Verification & Invariant | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **U01: Full Protected State** | PIN configured, Admin pass set, 5 apps locked, self-lock on | APK updated in-place via package installer | All 5 apps remain locked; PIN & Admin pass verify cleanly; no onboarding trigger | **INV-113 / INV-501** preserved. Zero credential loss. | **PASS** |
| **U02: Active PIN Lockout** | System in 60-second PIN lockout (5 failed attempts) | App upgraded during active countdown | Lockout timestamp read from SQLite; remaining duration enforced; keypad locked | **INV-303** preserved. Upgrades cannot bypass lockout. | **PASS** |
| **U03: Active Admin Lockout** | System in 300-second Admin lockout (5 failed attempts) | App upgraded during active lockout | Monotonic lockout duration enforced from DB; admin settings remain gated | **INV-114** preserved. | **PASS** |
| **U04: Incomplete Onboarding** | Step 4 reached (Accessibility enabled, but onboarding incomplete) | App upgraded mid-onboarding | App launches into `OnboardingScreen` (not Home); does not claim protected status | **INV-314** preserved. No premature protection claims. | **PASS** |
| **U05: In Recovery Mode** | Device Admin active, but local credentials missing (`RECOVERY_REQUIRED`) | App upgraded while in recovery | App opens `HomeScreen` with Red Recovery Banner; `resolveRecovery` remains available | **INV-110** preserved. Fail-closed recovery preserved. | **PASS** |
| **U06: Degraded State** | Accessibility service disabled in Settings | App upgraded while in degraded state | `HomeScreen` displays yellow Degraded Banner; does not falsely claim "Protected" | **INV-112** preserved. Truth in UI maintained. | **PASS** |

---

### 3. Reinstall & Storage Disruption Matrix (Vectors A – I)

| Vector | Operation / Attack Vector | Native Platform Mechanics | Resulting Security State | Recovery / User Experience | Safety Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Vector A** | **Fresh Install** | Clean SQLite DB, clean Keystore, unactivated Device Admin | `INITIALIZING` (`onboardingComplete = false`, `isProtected = false`) | Routes user to `OnboardingScreen` (Step 1/9). Zero false security claims. | **SAFE** |
| **Vector B** | **Uninstall $\rightarrow$ Reinstall** | Complete app wipe; Device Admin must have been removed prior | Clean fresh install state (`INITIALIZING`) | Full onboarding required. No lingering ghost locks. | **SAFE** |
| **Vector C** | **In-Place Upgrade** | Package replaced; SQLite DB preserved; Keystore keys retained | Unchanged from pre-upgrade state (`PROTECTED`) | Seamless continuation of protection without re-onboarding. | **SAFE** |
| **Vector D** | **Clear Cache** | Android deletes temporary cached files | Unaffected (`PROTECTED`); Room DB and Keystore untouched | Zero operational disruption. | **SAFE** |
| **Vector E** | **Clear Data (Admin Inactive)** | Local DB and SharedPreferences deleted | `INITIALIZING` (`onboardingComplete = false`) | Clean fresh onboarding. No orphaned system policies. | **SAFE** |
| **Vector F** | **Clear Data (Device Admin Active)** | Local DB & Keystore wiped, but Device Policy Manager retains Admin | **`RECOVERY_REQUIRED`** | **FAIL-CLOSED:** All protected app launches eject to Home (`GLOBAL_ACTION_HOME`). `HomeScreen` displays Red Recovery Banner. User restores credentials via `resolveRecovery()`. | **FAIL-CLOSED (SECURE)** |
| **Vector G** | **Reboot During Setup** | Device restarted while user is on Onboarding Step 3 | State evaluated on boot; if Admin active without creds $\rightarrow$ `RECOVERY_REQUIRED`; else $\rightarrow$ `INITIALIZING` | Safe deterministic recovery. | **SAFE** |
| **Vector H** | **Reinstall with Residual Admin** | Edge case: Device Admin somehow preserved across unusual package ops | Intercepted on cold start; flags `RECOVERY_REQUIRED` immediately | Ejects to Home until user restores credentials. | **FAIL-CLOSED (SECURE)** |
| **Vector I** | **Interrupted Package Update** | OS rolls back package replace on signature failure or power loss | Previous working APK and Room database retained intact by Android Package Manager | Normal operation under previous binary. | **SAFE** |

---

### 4. Database Migration Verification

The database migration harness in `DatabaseMigrationTest.kt` verifies schema versions:
- `MIGRATION_1_2`: Added cooldown and strict lock columns.
- `MIGRATION_2_3`: Added self-lock and timeout configuration.
- `MIGRATION_3_4`: Added `failedAdminAttempts`, `adminLockoutUntil`, and `recoveryRequired` columns.
- Upgrade queries preserve all existing user rows without throwing `IllegalStateException: A migration from X to Y was required but not found`.

---

### 5. Upgrade Validation Verdict: PASS

Upgrades, re-installations, and storage clearing resolve deterministically and safely across all permutations.
