# LockKeeper — Security & Privacy Specification

## 1. Security Objective

LockKeeper's security objective is to protect its configuration and credentials against ordinary application-level access while increasing the effort required to circumvent protection.

It is not a secure enclave, DRM system, MDM, or root-resistant security boundary.

## 2. Credential Separation

There are two distinct credentials:

### User PIN

Purpose:
- daily access to locked applications.

Properties:
- numeric
- 4–8 digits
- separate from admin password
- protected by failed-attempt throttling.

### Admin Password

Purpose:
- high-stakes configuration/tamper actions.

Properties:
- distinct from PIN
- not required for ordinary locked-app use
- required for protected configuration/tamper actions defined by the PRD.

The application must never silently reuse one credential as the other.

## 3. Credential Storage

### Non-negotiable rules

- Never store PIN or admin password in plaintext.
- Never log credentials.
- Never put credentials in analytics events.
- Never send credentials over a network.
- Never pass plaintext credentials through persistent storage.
- Flutter must not receive credential hashes/salts for normal operation.

### Storage architecture

Room stores non-secret state, such as:

- locked-app configuration;
- cooldown configuration;
- lock state;
- failure counters and expiration metadata where appropriate.

Credential material must be stored separately from Room's regular application tables using a current Android-supported, Keystore-backed protection strategy.

The previous concept of storing hash/salt fields in Room and "wrapping the Room DB" with `EncryptedSharedPreferences` is not the required design.

`EncryptedSharedPreferences` is deprecated in AndroidX Security Crypto. Do not introduce it into new code solely because it appeared in the old TDD.

Reference:
https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences

## 4. Password Hashing

Preferred design:

1. Generate a cryptographically random per-credential salt.
2. Derive a verification value with a password/PIN KDF.
3. Store only the derived verification value and salt in protected storage.
4. Use a KDF cost appropriate for the target device class.
5. Measure the selected cost on the slowest supported test device.

PBKDF2-HMAC-SHA-256 remains an acceptable implementation if correctly parameterized.

Do not hard-code "120,000 iterations" as a security guarantee. The final cost must be documented and benchmarked.

The KDF configuration must be versioned so a future migration path exists.

## 5. Verification

Verification occurs natively in Kotlin.

The Flutter layer may request:

- set PIN
- verify PIN
- set admin password
- verify admin password

Flutter must receive only success/failure and any non-sensitive status needed for UX.

Avoid sending hashes, salts, derived keys, or internal credential metadata to Flutter.

## 6. Failed PIN Attempts

Default product behavior:

- 5 consecutive incorrect attempts → 60-second lockout.

Requirements:

- Persist the lockout timestamp so process death cannot reset it.
- Define what resets the consecutive counter.
- Correct authentication resets the failure counter.
- Failed admin-password behavior must have an explicit policy separate from PIN failures; do not invent unlimited or irreversible admin lockouts without product approval.

## 7. Session State

Unlocked app sessions must be modeled explicitly.

Suggested state:

- locked;
- awaiting PIN;
- temporarily unlocked session;
- cooldown active;
- strict cooldown active;
- protection unavailable.

Do not infer security state from UI presence.

## 8. Sensitive Logging

Production logs must not include:

- PIN;
- admin password;
- credential hashes;
- salts;
- full authentication input;
- secret-derived material.

Debug logs should also avoid secrets.

Sensitive exception information must be scrubbed before persistent logging.

## 9. Data Minimization

The core product is local-only.

Do not add:

- account creation;
- cloud credential storage;
- remote analytics;
- telemetry;
- advertising SDKs;
- remote configuration;
- unnecessary device identifiers.

Any future addition of a network component requires a new privacy and threat-model review.

## 10. Backup

The implementation must deliberately decide whether protected configuration should be included in Android backup/restore.

Credential material must not be restored in a way that silently invalidates the security model.

For this personal-use project, the preferred default is:

- do not back up credential material;
- document what happens after restore;
- require explicit re-setup if necessary.

## 11. Permission Principle

Only permissions required by the defined product behavior may be requested.

For each permission, the code and onboarding copy must answer:

- What capability does this enable?
- Why does LockKeeper need it?
- What functionality stops working if it is revoked?

## 12. Accessibility Safety

The AccessibilityService must not collect unrelated user content.

The implementation should:

- inspect only required event/package/window information;
- avoid reading window text unless needed for the protected Settings-detection strategy;
- not store accessibility event content;
- not transmit event content anywhere.

## 13. Secure Coding Requirements

- Validate all package names before use.
- Treat Accessibility events as untrusted input.
- Treat package/class names as advisory, not cryptographic identity.
- Handle null, malformed, and duplicate events.
- Make overlay operations idempotent.
- Enforce authentication in native business logic, not only in the UI.
- Keep configuration changes behind explicit authorization where required.

## 14. Security Review Gate

Before release, verify:

- no plaintext credential storage;
- no plaintext credentials in logs;
- no credentials in Flutter debug output;
- no secrets committed to Git;
- no unnecessary network libraries or telemetry initialized;
- no exported internal components unless required;
- no unnecessary manifest permissions.
