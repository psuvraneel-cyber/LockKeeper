# LockKeeper — Threat Model

## 1. Purpose

LockKeeper is designed to create a meaningful barrier against impulsive self-circumvention on one Android device owned by the user.

It is not an enterprise MDM, a parental-control platform, a forensic security product, or a mathematically unbypassable system.

The threat model therefore prioritizes practical friction over protection against an attacker who already has unrestricted system-level control.

## 2. Assets to Protect

### Primary assets

- Lock state for protected applications.
- Cooldown timers.
- User PIN.
- Admin password.
- Accessibility-based interception state.
- Device Admin activation state.
- Protection-service state.
- Configuration for locked applications.

### Secondary assets

- Integrity of the locker's configuration.
- Persistence across normal reboot/background lifecycle events.
- User confidence that the lock is active when the UI indicates it is active.

## 3. Attacker Model

The attacker is the same physical owner of the device.

Assume the attacker:

- knows Android reasonably well;
- can interact with the Settings app;
- can open developer options if they choose;
- may be highly motivated in a moment of impulse;
- does not have root/system privileges as a prerequisite;
- does not need to preserve device data if they are willing to factory reset.

The application is specifically intended to create friction before the attacker escalates to deliberate system-level bypass.

## 4. In-Scope Attack Paths

### 4.1 Open a locked application

Attack:
- Launch the target application directly.
- Launch through launcher, recents, notification, deep link, or another app.
- Rapidly switch applications.

Expected defense:
- Foreground detection recognizes the protected package.
- Native lock state is checked.
- Native overlay is displayed.
- PIN is required unless a valid session or strict-lock cooldown applies.

### 4.2 Reopen during cooldown

Attack:
- Enter the correct PIN after the application has been re-locked.
- Repeatedly relaunch the app.

Expected defense:
- `lockedUntilTimestamp` is checked against current wall-clock time.
- In strict-lock mode, no PIN entry is accepted until the cooldown expires.
- In non-strict mode, PIN behavior follows the PRD's configured policy.

### 4.3 Attempt to disable Accessibility

Attack:
- Open Accessibility settings and turn off LockKeeper.

Expected defense:
- Accessibility detection identifies the relevant Settings destination/state.
- Native admin-password overlay requests the admin credential.
- Failure or dismissal triggers a back action where technically reliable.
- If the platform/OEM makes interception unreliable, the test must record the limitation rather than claim complete prevention.

### 4.4 Attempt to deactivate Device Admin

Attack:
- Open the device-admin management screen for LockKeeper.
- Attempt to deactivate the admin.

Expected defense:
- Accessibility layer recognizes the relevant Settings UI.
- Admin password gate is displayed before the sensitive action can complete, to the extent supported by the OEM Settings implementation.

### 4.5 Attempt uninstall

Attack:
- Open LockKeeper App Info and choose uninstall.
- Reach the deactivation stage required before uninstall.

Expected defense:
- Device Admin creates the deactivation requirement.
- Accessibility-based interception attempts to gate the sensitive Settings interaction.

Important:
Device Admin does not itself enforce the LockKeeper password. The intended chain is:
Device Admin → deactivation path → accessibility interception → admin-password gate.

## 5. Out-of-Scope Bypass Paths

The following are accepted residual risks:

### ADB with USB debugging

ADB can disable/uninstall/change settings outside application control.

Status: accepted.

### Safe Mode

Third-party services can be disabled in Safe Mode.

Status: accepted.

### Factory reset

Factory reset removes application state.

Status: accepted.

### Root/system compromise

A system-level attacker can defeat application-level controls.

Status: accepted.

### Physical compromise with unrestricted privileged tooling

Status: accepted.

## 6. Threat Matrix

| Threat | Defense | Residual Risk | Status |
|---|---|---|---|
| Direct launch of locked app | Accessibility + fallback detection + native overlay | Detection race/device-specific behavior | Test |
| Correct PIN during strict cooldown | Timestamp gate | Device clock manipulation | Accepted/Test |
| Disable Accessibility | Settings interception | OEM UI differences | Test |
| Deactivate Device Admin | Accessibility interception | OEM UI differences | Test |
| Uninstall | Device Admin + interception | ADB/Safe Mode | Accepted |
| Force-stop | Settings interception where detectable | OEM differences / privileged system actions | Test |
| Reboot | Boot receiver + protection recovery | OEM boot restrictions/power management | Test |
| Process kill | Service recovery + accessibility lifecycle | OEM process management | Test |
| Credential extraction | Native-only verification + protected credential storage | Device compromise | Accepted |
| Configuration corruption | Transactional persistence + validation | Physical/system compromise | Test |

## 7. Security Claims

The application must NOT make any of the following claims:

- "Impossible to bypass"
- "Uninstall-proof"
- "Root-proof"
- "Safe Mode-proof"
- "ADB-proof"
- "Tamper-proof"

Acceptable language:

- "Designed to resist casual circumvention."
- "Adds a separate admin credential and system-level friction."
- "Does not prevent deliberate system-level bypass."

## 8. Threat-Model Rule

Every new feature that increases authority, persistence, or access to other applications must add:

1. a stated threat it addresses;
2. a stated limitation;
3. a test case;
4. a justification for why the capability belongs in LockKeeper.

No privileged capability may be added merely because it makes implementation easier.
