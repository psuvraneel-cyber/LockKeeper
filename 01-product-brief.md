# Product Brief — Personal App Locker

## Working Title
**LockKeeper** (placeholder — rename freely; used for internal referencing only)

## Summary
LockKeeper is a personal-use Android application that lets a single user lock other installed apps behind a PIN/password for a configurable period of time, and — unlike most Play Store app lockers — resists casual removal. Removing or disabling LockKeeper itself requires a separate admin password, adding real friction against impulsive circumvention.

## Problem Statement
Off-the-shelf app lockers on the Play Store are trivially defeated: uninstalling the locker app removes the lock instantly. For a user trying to build a genuine barrier against their own impulsive app usage (e.g., social media, games), that defeats the purpose entirely. There is no consumer-grade option that treats the locker's own removability as part of the threat model.

## Goals
- Lock any selected app behind PIN entry.
- Support a "locked for N minutes/hours" cooldown so a locked app cannot simply be re-opened immediately after one unlock.
- Make removing/disabling the locker itself (uninstall, force-stop, disable accessibility service, revoke device admin) require a distinct admin password.
- Survive device reboot and OEM battery-management app-killing.
- Run entirely on a single, already-provisioned personal device — no factory reset, no Device Owner enrollment.

## Non-Goals
- Play Store distribution or compliance with Play policies.
- Multi-user / multi-device management.
- Defeating determined bypass paths that require system-level access the user already has (Safe Mode, ADB with USB debugging enabled, factory reset). These are explicitly out of scope — see Known Limitations in the TDD.
- Enterprise MDM-grade tamper resistance (that requires Device Owner, which is unavailable here).

## Target User
A single person (the developer) locking their own device, deliberately building friction against their own future impulses. Not designed for locking a device you don't control, or for monitoring another person.

## Constraints
- No Device Owner provisioning available (existing accounts on user 0; no factory reset acceptable).
- Must be built without Play Store review, so no restriction on permissions used (SYSTEM_ALERT_WINDOW, Accessibility, Device Admin, Usage Stats can all be used freely).
- Solo development effort, built with a Flutter/Dart foundation plus native Android modules where the platform requires it (Accessibility Service, Device Admin, overlay windows are not well covered by Flutter alone).

## Success Criteria
- Locked apps cannot be reopened without correct PIN, and cannot be reopened again during the cooldown window even with the correct PIN.
- Attempting to uninstall, force-stop, disable the accessibility service, or deactivate device admin triggers a password prompt that blocks the action until the admin password is entered.
- The locker survives a device reboot without manual relaunching.
- The locker survives at least the default battery-optimization behavior of the device without being killed in the background.

## Known Limitations (accepted, not solved)
Safe Mode, ADB shell commands, and factory reset all bypass this system. These require capabilities (developer-enabled USB debugging, willingness to factory reset) that represent a deliberate, high-effort override rather than casual removal, and are considered an acceptable residual risk for this personal-use project.
