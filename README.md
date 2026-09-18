# LockKeeper 🛡️

<p align="center">
  <strong>Zero-telemetry, anti-tamper personal app locker engineered for intentional digital focus.</strong>
</p>

<p align="center">
  <a href="https://flutter.dev"><img src="https://img.shields.io/badge/Flutter-3.x-02569B?style=for-the-badge&logo=flutter&logoColor=white" alt="Flutter" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-Native_Subsystem-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" /></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-API_26--36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" /></a>
  <img src="https://img.shields.io/badge/Network-Air--Gapped_0%25-brightgreen?style=for-the-badge" alt="Zero Network" />
  <img src="https://img.shields.io/badge/Telemetry-None-red?style=for-the-badge" alt="Zero Telemetry" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License" /></a>
</p>

---

## 📖 Overview

Most commercial app lockers fail at their fundamental premise: **they are trivially bypassed**. When an impulsive urge strikes, users can simply uninstall the locker, force-stop it via Settings, or disable its permissions in seconds. Furthermore, many commercial alternatives harvest behavioral metrics, query cloud servers, and clutter the interface with ads.

**LockKeeper** takes a fundamentally different security and architectural approach:
- **Friction by Design:** Removing or modifying protections requires a separate, high-entropy **Admin Password**, adding deliberate cognitive friction against impulsive override.
- **Air-Gapped & Private:** `android.permission.INTERNET` is completely omitted from the manifest. No cloud pings, no trackers, and zero analytics.
- **Hybrid Security Engine:** High-performance native Android services (Kotlin) handle low-latency event interception and overlays, coupled with a fast, modern utilitarian dark UI (Flutter).

---

## ✨ Key Features

### 🛡️ Dual-Tier Credential Hierarchy
- **Daily User PIN (4–6 digits):** Quick, conscious authentication to unlock protected applications for regular sessions.
- **Emergency Admin Password (8+ alphanumeric characters):** Required for critical configurations, credential rotation, and accessing system protection settings.
- **Hardware-Backed Cryptography:** Salted **PBKDF2-HMAC-SHA256** (≥120,000 iterations) with cryptographic salts, wrapped in **AES-256-GCM** keys backed by the Android Hardware Keystore.

### ⏱️ Strict Lock & Cooldown Modes
- **Standard Cooldown:** After unlocking an app, a configurable timer (15m, 1h, 4h, 24h, or custom) elapses before the app locks again.
- **Strict Lock:** For high-friction habit breaking. When enabled, locked apps **cannot be unlocked at all** while a cooldown or active lockout timer is in effect. Overlays display remaining time and redirect directly back to the home launcher.
- **Dynamic Lockout Backoff:** Repeated failed PIN attempts trigger progressive lockout penalties to thwart brute-force attempts.

### 🧱 Active Anti-Tamper & Self-Protection
- **Settings Interception:** Real-time detection of navigation into system menus (App Info, Uninstall Confirmation, Device Administrators, Accessibility Settings) for LockKeeper, instantly blocking unauthorized removal attempts.
- **Device Administrator Integration:** Enrolled `DeviceAdminReceiver` guarantees the operating system prevents direct uninstallation without deactivation.
- **Native Hardware Overlays:** Full-screen hardware-accelerated overlays (`TYPE_APPLICATION_OVERLAY`) consume navigation touch and key events (`KEYCODE_BACK`) to prevent dismissal leaks.
- **Boot & Process Resilience:** `BootReceiver` automatically restores foreground services and enforcement policies on device boot (`BOOT_COMPLETED`).

### ⚡ Dual-Engine Detection Pipeline
1. **Primary: Event-Driven `AccessibilityService`** — Sub-millisecond window state change detection (`TYPE_WINDOW_STATE_CHANGED`) to intercept target activities prior to render.
2. **Secondary: Fallback `UsageStatsManager` Polling** — A foreground worker continuously samples task states, ensuring fallback coverage if accessibility experiences OEM degradation.

---

## 🏛️ Architecture

LockKeeper adopts a clean separation of concerns between presentation and system-level security enforcement:

```
┌─────────────────────────────────────────────────────────────┐
│                   Flutter UI Layer (Dart)                   │
│   - Guided 9-Step Onboarding Wizard                         │
│   - App Inventory & Search (All / Locked / Unlocked)        │
│   - Per-App Rule Configurator (Cooldown & Strict Mode)      │
│   - System Health & Cryptographic Audit Dashboard           │
└──────────────────────────────┬──────────────────────────────┘
                               │ Platform Channels (Method & Event)
┌──────────────────────────────▼──────────────────────────────┐
│                Native Security Subsystem (Kotlin)           │
│  ┌────────────────────────┐     ┌────────────────────────┐  │
│  │  AccessibilityService  │     │   Foreground Service   │  │
│  │ (Window State Monitor) │     │ (UsageStats Fallback)  │  │
│  └───────────┬────────────┘     └───────────┬────────────┘  │
│              │                              │               │
│              ▼                              ▼               │
│     ┌────────────────────────────────────────────────┐      │
│     │        Lock Decision Engine & Policy           │      │
│     └───────────────────────┬────────────────────────┘      │
│                             │                               │
│       ┌─────────────────────┴──────────────────────┐        │
│       ▼                                            ▼        │
│  ┌─────────────────────────┐         ┌────────────────────┐ │
│  │  Native Overlay Manager │         │ Persistence Layer  │ │
│  │ (Pin/Cooldown/Admin UI) │         │ (Room DB + KeyStore│ │
│  └─────────────────────────┘         └────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔒 Security & Privacy Guarantees

| Invariant | Guarantee |
| :--- | :--- |
| **Network Capability** | **Zero.** The `INTERNET` permission is absent from `AndroidManifest.xml`. Physical exfiltration is impossible. |
| **Credential Storage** | Hashes are computed via **PBKDF2-HMAC-SHA256** and encrypted with **AES-256-GCM** using keys generated in the **Android Keystore Provider**. Raw credentials never touch persistent disk. |
| **Persistence Isolation** | Policy definitions and cooldown states are stored locally in an application-sandboxed **SQLite Room Database**. |
| **Analytics & SDKs** | **0 third-party trackers.** No Firebase, no telemetry engines, no advertising libraries, no crash reporters. |

### Threat Model & Boundaries

- **Defended Surfaces:** Impulsive uninstallation, force-stopping via Settings, disabling Accessibility services, revoking Device Admin, background process killing, and reboot circumvention.
- **Accepted Out-of-Scope Surfaces:** Hostile root access, physical Safe Mode booting, and desktop ADB debugging sessions. These represent deliberate, high-effort bypass actions rather than casual, impulsive overrides.

---

## 📋 System Permissions Required

| Permission | Reason for Requirement |
| :--- | :--- |
| `SYSTEM_ALERT_WINDOW` | Renders native PIN, Cooldown, and Admin verification overlays above target applications. |
| `BIND_ACCESSIBILITY_SERVICE` | Real-time detection of foreground application transitions and Settings tampering attempts. |
| `BIND_DEVICE_ADMIN` | Prevents unauthorized application uninstallation without explicit credentialed deactivation. |
| `PACKAGE_USAGE_STATS` | High-resiliency fallback monitor in the event of accessibility service interruption. |
| `RECEIVE_BOOT_COMPLETED` | Restores protection services immediately following device startup. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Protects the monitoring services from aggressive OEM background termination. |

---

## 📂 Repository Structure

```text
LockKeeper/
├── android/                   # Native Android Security Engine
│   └── app/src/main/kotlin/com/lockkeeper/app/
│       ├── bridge/            # Flutter Platform Channel Handlers
│       ├── data/db/           # Room Database (LockedApp & AppSettings)
│       ├── domain/            # Lock Decision Engine & Policy
│       ├── overlay/           # Native Hardware Overlay Windows
│       ├── receiver/          # DeviceAdmin & Boot Receivers
│       ├── security/          # Keystore Cryptography & PBKDF2
│       └── service/           # Accessibility & Foreground Services
├── lib/                       # Flutter Presentation Layer
│   ├── core/                  # Models, Data Sources & Native Bridge
│   ├── ui/
│   │   ├── screens/           # Onboarding, Home, Settings, App Detail
│   │   └── theme/             # Dark Utilitarian Theme Tokens
│   └── main.dart              # Application Entrypoint & Route Gate
├── test/                      # Flutter Unit & Integration Test Suites
├── audit/                     # Security, Threat Model & Invariant Specs
├── scripts/                   # PowerShell Build, Test & Emulation Helpers
└── pubspec.yaml               # Flutter Package Manifest
```

---

## 🛠️ Getting Started

### Prerequisites
- [Flutter SDK](https://docs.flutter.dev/get-started/install) (3.11+ / Dart 3.x)
- Android SDK (API 26 minimum, API 36 compile target)
- Java Development Kit (JDK 17)
- An Android physical device or emulator running Android 8.0+

### Build & Run

1. **Clone the repository:**
   ```bash
   git clone https://github.com/psuvraneel-cyber/LockKeeper.git
   cd LockKeeper
   ```

2. **Fetch dependencies:**
   ```bash
   flutter pub get
   ```

3. **Run automated test suites:**
   ```bash
   # Run Flutter unit tests
   flutter test

   # Run Native Kotlin test suite
   cd android && ./gradlew test && cd ..
   ```

4. **Run on a connected device/emulator:**
   ```bash
   flutter run --release
   ```

5. **Build Signed Release APK:**
   ```powershell
   .\scripts\android_build.ps1 -Release
   ```
   *(Ensure signing keys are configured via `android/key.properties` for production builds).*

---

## 📄 Documentation Index

Comprehensive technical specifications and audit reports are located in the repository:

- [`01-product-brief.md`](01-product-brief.md) — Product requirements and vision
- [`04-technical-design-document.md`](04-technical-design-document.md) — Architectural blueprints & subsystem specs
- [`05-threat-model.md`](05-threat-model.md) — STRIDE security model & tamper mitigation analysis
- [`07-security-privacy-spec.md`](07-security-privacy-spec.md) — Cryptographic standards & data isolation
- [`release-notes.md`](release-notes.md) — Release notes and sideload instructions

---

## ⚖️ License

Distributed under the **MIT License**. See `LICENSE` for details.
