# LockKeeper — Android Build & Release Specification

## 1. Release Objective

Produce a reproducible, signed Android release artifact suitable for direct sideloading.

The project does not require Google Play distribution.

## 2. Identity

Before release, freeze:

```text
Application name: LockKeeper
Application ID: <final package id>
Version name: <semantic/user-visible version>
Version code: <monotonically increasing integer>
Minimum SDK: 26
Target SDK: 36
Compile SDK: 36
```

The final package ID must not be changed after the first persistent release without an explicit migration decision.

## 3. Build Variants

At minimum:

### Debug
- development logging allowed;
- non-production signing;
- explicit test configuration where necessary.

### Release
- production configuration;
- no credential logging;
- no debug-only behavior;
- minification/shrinking enabled only after compatibility testing;
- signed artifact.

## 4. Signing

All release APKs must be digitally signed.

Requirements:

- release keystore is not committed to Git;
- signing passwords are not committed;
- signing credentials are supplied through secure local/CI configuration;
- backup of the signing key is maintained outside the repository;
- document the alias and certificate fingerprint securely.

Reference:
https://developer.android.com/studio/publish/preparing

## 5. APK vs AAB

For direct installation:

- Release APK is the primary artifact.

For Google Play, if distribution is added later:

- AAB is the normal submission artifact.
- Play-specific signing/configuration requirements must be handled separately.

Do not treat APK and AAB as interchangeable release workflows.

## 6. Icon and Branding

Use the generated LockKeeper icon asset.

Prepare:

- launcher icon;
- adaptive icon foreground/background assets as appropriate;
- high-density resources or vector source where appropriate.

Do not bake a large wordmark into the adaptive icon unless the design survives launcher masking and small-size rendering.

The app's visible label should remain `LockKeeper`.

## 7. Manifest Review

Before release, inspect:

- all permissions;
- exported components;
- services;
- receivers;
- intent filters;
- accessibility declaration;
- Device Admin declaration;
- foreground-service declarations;
- overlay requirements;
- package visibility queries.

Remove unused permissions and components.

## 8. Release Configuration

Verify:

- production package ID;
- correct app label;
- correct icon;
- version code/name;
- no debug endpoints;
- no test credentials;
- no placeholder values;
- no localhost dependencies;
- no development-only flags.

Because the core project is local-only, the release should not require a backend.

## 9. ProGuard/R8

Enable code shrinking/obfuscation only after native services, reflection, Room, Flutter integration, and platform-channel behavior pass release-mode tests.

Maintain required keep rules for any framework/library behavior that depends on reflection or generated metadata.

A release crash caused by over-aggressive shrinking is a release blocker.

## 10. Backup & Restore

Decide and test:

- what application configuration is backed up;
- whether credentials are excluded;
- how reinstall/restore affects onboarding;
- how Device Admin state is re-established.

Do not claim that a restored installation remains protected unless tested.

## 11. Installation Test

On a clean device:

1. install release APK;
2. complete onboarding;
3. grant required capabilities;
4. lock a test app;
5. verify lock;
6. verify cooldown;
7. verify admin interception;
8. reboot;
9. verify recovery;
10. uninstall only through the documented bypass/recovery mechanism.

## 12. Update Test

From release N to release N+1:

- install over existing application;
- verify database migration;
- verify credential behavior;
- verify lock configuration;
- verify service/accessibility state;
- verify no unexpected data reset.

## 13. Release Artifacts

Each release should produce:

```text
LockKeeper-vX.Y.Z-release.apk
release-notes.md
test-report.md
checksums.txt
```

Optional:

```text
LockKeeper-vX.Y.Z-release.aab
```

## 14. Checksum

Generate SHA-256 checksums for release artifacts.

## 15. Release Blockers

Do not ship when:

- release build fails;
- signing fails or is unverifiable;
- credentials are exposed;
- known lock bypass exists within the documented in-scope threat model;
- major regression exists in Accessibility/overlay flow;
- database migration fails;
- app cannot complete clean onboarding;
- release artifact cannot be installed.

## 16. Official References

Android release preparation:
https://developer.android.com/studio/publish/preparing

Foreground services:
https://developer.android.com/develop/background-work/services/fgs

Target API requirements:
https://developer.android.com/google/play/requirements/target-sdk
