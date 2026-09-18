# LockKeeper — Engineering Documentation Index

## Purpose

This directory is the authoritative specification set for LockKeeper.

The existing project documents define the product, UX, architecture, and implementation intent. The documents in this package close the remaining engineering gaps so an AI coding agent can implement the Android application without inventing critical product, security, or platform decisions.

## Document Authority

When documents appear to conflict, use this precedence order:

1. `01-product-brief.md`
2. `02-prd.md`
3. `03-uiux-specification.md`
4. `04-technical-design-document.md`
5. `05-threat-model.md`
6. `06-android-platform-spec.md`
7. `07-security-privacy-spec.md`
8. `08-data-persistence-spec.md`
9. `09-implementation-plan.md`
10. `10-adversarial-testing.md`
11. `11-build-release-spec.md`
12. `12-definition-of-done.md`
13. `AGENT_RULES.md`

`AGENT_RULES.md` is procedural rather than product authority: it constrains how an AI implementation agent may modify the repository.

## Important Decisions Frozen by This Package

- Product remains a single-user, personal-device friction tool.
- Device Owner provisioning is explicitly out of scope.
- ADB, Safe Mode, factory reset, and system/root-level control remain accepted bypasses.
- Flutter is the UI/configuration layer.
- Kotlin owns Android system integration and protection state.
- AccessibilityService is the primary foreground-app detection mechanism.
- UsageStatsManager is a fallback where technically viable.
- Native Android overlays implement PIN/admin interception.
- Room stores non-secret persistent application state.
- Credentials are not stored in plaintext and must not be stored as Room columns.
- Android target baseline is API 36, with minSdk 26, subject to implementation-time dependency compatibility checks.
- No network/backend is required for the core product.
- No Google Play distribution is required for the project, although release documentation distinguishes APK from AAB.
- The generated LockKeeper app icon is part of the release asset set.

## How Antigravity Should Use These Files

Read the entire documentation set before making architectural changes.

Do not make a critical implementation choice based only on a single document or on assumptions from framework defaults.

When a requirement is technically impossible on a target Android version, do not silently substitute a weaker behavior. Record the incompatibility, preserve the strongest feasible behavior, update the implementation notes/tests, and surface the deviation.

## Recommended Reading Order

Start with:

`00 → 01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11 → 12 → AGENT_RULES`

The first implementation gate is completion of the architecture/platform/security/data-model review.

The second gate is a successful adversarial test pass.

The final gate is the release checklist and Definition of Done.
