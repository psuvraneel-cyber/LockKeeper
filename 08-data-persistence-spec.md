# LockKeeper — Data & Persistence Specification

## 1. Persistence Authority

Native Kotlin owns the authoritative protection state.

Flutter is a presentation/configuration client and must not maintain an independent security state.

Source of truth:

`Kotlin domain state → persistence layer → Flutter reads through Platform Channels`

If Flutter and native disagree, native state wins.

## 2. Storage Separation

### Room

Use Room for normal structured application state.

Suggested entities:

### `LockedApp`

| Field | Type | Rules |
|---|---|---|
| packageName | String | Primary key; validated Android package name |
| isLocked | Boolean | Required |
| cooldownMinutes | Int | Positive bounded value |
| strictLock | Boolean | Required |
| lockedUntilTimestamp | Long? | Absolute wall-clock time; nullable |
| updatedAt | Long | Last mutation timestamp |

Optional future fields:
- open-session metadata;
- schema/version flags.

### `AppSettings`

Suggested fields:

| Field | Type | Rules |
|---|---|---|
| id | Int | Single-row fixed identifier |
| failedPinAttempts | Int | Non-negative |
| pinLockoutUntil | Long? | Absolute timestamp |
| onboardingComplete | Boolean | Required |
| schemaVersion | Int | Required |
| updatedAt | Long | Required |

Do NOT put:

- PIN plaintext;
- admin-password plaintext;
- raw password hashes/salts unless the final protected-storage design explicitly proves why they must be in Room.

## 3. Credential Store

Credential material is stored in a separate secure storage component.

The implementation should expose an interface such as:

```text
CredentialStore
 ├── setPinCredential(...)
 ├── verifyPin(...)
 ├── setAdminCredential(...)
 └── verifyAdminCredential(...)
```

The rest of the codebase must depend on this interface rather than directly manipulating credential storage.

## 4. Database Access

Use Room DAOs and repositories.

Rules:

- No direct raw database queries from Flutter.
- No blocking DB operations on the main thread.
- Configuration writes must be transactional where multiple state changes must remain consistent.
- Reads required for lock decisions should be optimized and indexed.

## 5. Lock Decision Algorithm

For a detected foreground package:

```text
1. Ignore packages that are not lockable.
2. Read authoritative LockedApp record.
3. If no record or isLocked=false → allow.
4. Read current time.
5. If strictLock and lockedUntilTimestamp > now → show strict cooldown.
6. Otherwise determine whether a PIN/session is required.
7. Render exactly one native gate.
8. On successful unlock, persist session/cooldown state as required.
```

Do not use UI state as the authoritative lock decision.

## 6. Cooldown Semantics

Persist:

`lockedUntilTimestamp = start + cooldownDuration`

Do not persist "remaining minutes" as the primary state.

After reboot:

`remaining = max(0, lockedUntilTimestamp - now)`

The UI may display a rounded human-readable countdown.

## 7. Clock Changes

The implementation must test:

- forward clock adjustment;
- backward clock adjustment;
- reboot during cooldown;
- timezone change.

The product must explicitly accept that a user controlling the system clock may be able to influence wall-clock cooldown behavior unless a stronger time source is introduced.

Do not silently add internet time synchronization because the product is intentionally local-only.

## 8. Concurrency

Potential duplicate triggers include:

- accessibility event;
- UsageStats fallback event;
- rapid app switch;
- service restart;
- Activity recreation.

The overlay manager must be idempotent.

Rules:

- one active gate per gate type;
- repeated detection of the same package must not stack gates;
- transitions must be guarded by synchronized/state-machine logic;
- stale callbacks must not unlock a newer gate.

## 9. Uninstall/Reinstall

Define behavior explicitly:

- Uninstall removes app-private persistent state.
- Reinstall starts as a new installation.
- No cloud restore is expected.
- Device Admin must be cleanly handled during uninstall/reinstall testing.

## 10. Schema Migration

Every Room schema change requires a migration.

Do not rely on destructive migration for the release build.

Development-only destructive migration is acceptable only before any persistent production data matters.

## 11. Repository Interfaces

Suggested structure:

```text
LockRepository
PermissionRepository
CredentialStore
ProtectionStateRepository
InstalledAppsRepository
```

The domain layer should not depend directly on Android framework storage APIs beyond clearly isolated adapters.

## 12. Persistence Tests

Must cover:

- CRUD for locked apps;
- cooldown persistence;
- strict-lock persistence;
- failure counter persistence;
- lockout persistence;
- reboot-equivalent state recovery;
- migration;
- malformed values;
- duplicate writes;
- concurrent reads/writes.
