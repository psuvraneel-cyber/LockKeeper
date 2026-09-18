# Product Requirements Document (PRD) — LockKeeper

## 1. Overview
LockKeeper locks selected Android apps behind a PIN and enforces a cooldown period before they can be reopened. The locker app itself is protected by device-admin + accessibility-based interception so that uninstalling, disabling, or force-stopping it requires a separate admin password. Built for personal use on a single already-set-up device (no Device Owner, no factory reset).

## 2. User (single persona)
**The Owner** — the developer, using the app on their own device to restrict their own access to distracting apps. Has full technical capability to bypass the system via ADB/Safe Mode if truly determined, but wants a real day-to-day barrier against casual/impulsive circumvention.

## 3. Functional Requirements

### FR1 — App Selection & Locking
- User can view a list of installed (launchable) apps.
- User can toggle any app as "locked."
- Locked apps require PIN entry before opening.

### FR2 — Timer-Based Lock / Cooldown
- For each locked app, user sets a cooldown duration (e.g., 15 min, 1 hr, custom).
- After a correct PIN unlock, the app remains accessible for a defined "open session," then re-locks.
- Once re-locked, the app cannot be reopened until the cooldown expires, **even with the correct PIN** (this is the core "hard to defeat" behavior — optional per-app toggle: "strict lock" vs. "PIN always unlocks immediately").

### FR3 — PIN Setup & Entry
- User sets a numeric PIN (4–8 digits) on first run.
- PIN required to open any locked app whose cooldown has expired.
- Lockout after 5 consecutive wrong attempts for 60 seconds (basic brute-force friction).

### FR4 — Admin Password (separate from PIN)
- A second, distinct credential set up during onboarding.
- Required specifically for: uninstalling LockKeeper, disabling its Accessibility Service, deactivating Device Admin, force-stopping it, or changing the PIN/cooldown settings themselves.
- Deliberately separate from the daily-use PIN so the PIN can be shared/used casually without exposing the "master override."

### FR5 — Anti-Uninstall Friction (Device Admin)
- App requests Device Admin activation on first run.
- While active, uninstalling requires first deactivating Device Admin via system Settings.

### FR6 — Settings-Screen Interception
- Accessibility Service detects navigation into:
  - App Info / Uninstall screen for LockKeeper
  - Device Admin apps screen
  - Accessibility settings screen (specifically the LockKeeper toggle)
- On detection, an overlay demands the admin password before allowing further interaction; incorrect/no entry backs the user out of the screen.

### FR7 — Boot Persistence
- A `BOOT_COMPLETED` receiver restarts the foreground detection service after every reboot.

### FR8 — Kill Resistance
- App requests exemption from battery optimization during onboarding.
- Foreground service uses a persistent low-priority notification.
- Service attempts restart on `onTaskRemoved`.

### FR9 — Onboarding Wizard
- Step-by-step permission grant flow: Overlay (SYSTEM_ALERT_WINDOW), Usage Access, Accessibility Service, Device Admin, Battery Optimization exemption, PIN setup, Admin password setup.

## 4. Non-Functional Requirements
- **Latency:** Foreground-app change to lock-overlay display should be under ~500ms to avoid a "flash" of the locked app's content.
- **Security:** PIN and admin password stored only as salted hashes (e.g., PBKDF2) inside `EncryptedSharedPreferences`. Never stored or logged in plaintext.
- **Reliability:** Detection loop must not miss app switches when a device is under normal memory pressure.
- **Battery:** Foreground polling (if used as Usage Stats fallback) should be interval-based (e.g., 300–500ms), not busy-looping.

## 5. Assumptions & Dependencies
- Target device runs Android 8.0+ (for foreground service notification requirements) — adjust minSdk based on the actual device.
- Developer is comfortable granting all requested permissions manually, since this is sideloaded and not Play-reviewed.
- No Play Store distribution; installed via direct APK / sideload.

## 6. Out of Scope / Known Limitations
- Safe Mode boot bypasses all third-party services, including this one.
- ADB commands (`pm uninstall`, `pm disable`, `settings put`) bypass all in-app protections if USB debugging is enabled.
- Factory reset removes everything.
- True unremovable behavior requires Device Owner provisioning, which is explicitly unavailable per project constraints.

## 7. Milestones
1. Foreground app detection (Accessibility + Usage Stats fallback)
2. Lock overlay + PIN storage + basic lock/unlock
3. Timer-based cooldown / re-lock logic
4. Device Admin integration
5. Settings-screen interception (accessibility-based)
6. Persistence hardening (boot receiver, battery exemption, service restart)
7. Admin password separated from user PIN, wired into all protected actions
