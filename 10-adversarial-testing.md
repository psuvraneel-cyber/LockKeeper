# LockKeeper — Adversarial Testing & Verification Plan

## 1. Objective

The purpose of this document is to prove that LockKeeper behaves correctly under ordinary use, failure conditions, rapid interaction, lifecycle changes, and known bypass attempts.

Passing happy-path tests is insufficient.

## 2. Test Environments

Maintain at least:

### Environment A
Fresh emulator or secondary physical device for unstable development.

### Environment B
Clean release-like test installation.

### Environment C
Recent Android device using a non-stock/OEM Settings experience if available.

Keep ADB recovery access throughout development.

Do not develop Settings interception first on the daily-driver device.

## 3. Functional Test Matrix

### Locking

- [ ] List launchable applications.
- [ ] Lock an application.
- [ ] Unlock with correct PIN.
- [ ] Reject incorrect PIN.
- [ ] Handle malformed/empty input.
- [ ] Lock/unlock configuration persists after app restart.
- [ ] Lock state survives reboot.

### Cooldown

- [ ] Configure 15-minute cooldown.
- [ ] Configure 1-hour cooldown.
- [ ] Configure 4-hour cooldown.
- [ ] Configure 24-hour cooldown.
- [ ] Configure custom valid value.
- [ ] Reject invalid/custom out-of-range value.
- [ ] Cooldown starts at the specified semantic event.
- [ ] Cooldown survives process death.
- [ ] Cooldown survives reboot.
- [ ] Expiration unlocks according to defined policy.

### Strict Lock

- [ ] Strict lock ON prevents PIN reopening during cooldown.
- [ ] Countdown view shows remaining time.
- [ ] Correct PIN does not bypass strict cooldown.
- [ ] Strict-lock state persists.

### PIN

- [ ] 4-digit PIN accepted.
- [ ] 8-digit PIN accepted.
- [ ] Out-of-range length rejected.
- [ ] Correct PIN unlocks.
- [ ] 1–4 wrong attempts behave normally.
- [ ] 5th wrong attempt creates 60-second lockout.
- [ ] Lockout survives process death.
- [ ] Lockout expires correctly.
- [ ] Correct PIN resets failed-attempt count.

### Admin Credential

- [ ] Admin password is distinct from PIN.
- [ ] Correct admin credential succeeds.
- [ ] Wrong admin credential fails.
- [ ] Admin credential changes require the current admin credential.
- [ ] PIN changes require current PIN.

## 4. Launch Vectors

For every locked package, test launch through:

- [ ] launcher;
- [ ] recent apps;
- [ ] notification;
- [ ] external intent;
- [ ] deep link;
- [ ] another application's explicit intent;
- [ ] rapid repeated launches;
- [ ] app switching.

## 5. Lifecycle Tests

- [ ] screen off/on;
- [ ] device lock/unlock;
- [ ] orientation/configuration change;
- [ ] app process killed;
- [ ] service recreated;
- [ ] swipe application from recents;
- [ ] reboot;
- [ ] low-memory simulation where possible;
- [ ] battery restriction;
- [ ] battery optimization exemption;
- [ ] accessibility disabled;
- [ ] accessibility re-enabled.

## 6. Settings Interception

### App Info

- [ ] Open LockKeeper App Info.
- [ ] Navigate toward uninstall.
- [ ] Verify admin prompt appears where intended.
- [ ] Wrong admin credential causes back-out behavior where supported.
- [ ] Correct admin credential grants only the documented short grace period.
- [ ] Grace window expires automatically.

### Device Admin

- [ ] Open Device Admin management.
- [ ] Select LockKeeper.
- [ ] Attempt deactivation.
- [ ] Wrong credential blocks/back-outs where supported.
- [ ] Correct credential grants intended action window.

### Accessibility

- [ ] Open Accessibility settings.
- [ ] Locate LockKeeper service.
- [ ] Attempt disabling.
- [ ] Verify intended admin gate behavior.
- [ ] Re-enable service and verify recovery.

### Force Stop

- [ ] Open App Info.
- [ ] Attempt force-stop.
- [ ] Verify whether the platform/OEM exposes a reliably interceptable path.
- [ ] Record actual behavior rather than assuming success.

## 7. Adversarial Input

- [ ] Rapidly tap PIN keys.
- [ ] Paste input where possible.
- [ ] Submit while overlay is appearing.
- [ ] Submit while overlay is disappearing.
- [ ] Rapidly switch between apps while PIN overlay is active.
- [ ] Trigger multiple accessibility events during one gate.
- [ ] Trigger stale callbacks after gate dismissal.
- [ ] Change locked-app configuration while target app is in foreground.
- [ ] Change system clock forward.
- [ ] Change system clock backward.
- [ ] Change timezone.
- [ ] Reboot during cooldown.

## 8. Known Bypass Confirmation

The following tests should succeed in bypassing LockKeeper, demonstrating that accepted limitations have not been misrepresented:

- [ ] ADB package uninstall/disable where authorized by test setup.
- [ ] ADB settings modification where authorized.
- [ ] Safe Mode behavior.
- [ ] Factory reset.

These are not release failures. They are threat-model confirmation tests.

## 9. Performance Tests

Measure:

- foreground detection → gate display latency;
- average event processing time;
- memory footprint;
- overlay creation frequency;
- fallback polling CPU impact.

The existing PRD target is approximately <500 ms for lock-overlay display under normal conditions. Treat this as an engineering target rather than a universal platform guarantee.

## 10. Accessibility/UI Tests

- [ ] PIN controls are understandable to TalkBack where applicable.
- [ ] Focus order is deterministic.
- [ ] Text contrast is adequate.
- [ ] No critical action relies solely on animation.
- [ ] Large text does not break layout.
- [ ] Gesture/back behavior remains understandable.

## 11. Release Regression Matrix

Every release candidate must repeat:

- locking;
- PIN;
- cooldown;
- strict lock;
- admin credential;
- Accessibility;
- Device Admin;
- reboot;
- battery lifecycle;
- uninstall friction;
- APK installation/update.

## 12. Test Evidence

A release candidate is not considered verified unless there is:

- test result record;
- device/API version record;
- list of known failures;
- classification of each failure:
  - blocker;
  - acceptable limitation;
  - deferred improvement.

Do not mark tests as passed without executing them.
