# Phase 0 — Repository Reconnaissance Note

**Date**: 2026-09-15  
**Project**: LockKeeper  
**Application ID**: `com.lockkeeper.app`  

## 1. Environment & Tooling Audit
- **Flutter SDK**: `Flutter 3.41.6` (Channel stable), located at `C:\flutter\bin`
- **Dart SDK**: `Dart 3.11.4`
- **Android SDK**: `C:\Android\Sdk`
  - Platforms: `android-36` (Android 16 Developer Preview/Final), `android-35`
  - Build Tools: `35.0.0`
- **Java Development Kit**: Microsoft OpenJDK `17.0.18+8-LTS` (`JAVA_HOME` configured)
- **Target OS / Build Host**: Windows 11 (25H2)

## 2. Existing Workspace State
- The workspace directory `c:\Users\Sauvraneel Paul\OneDrive\Desktop\AppLocker` contains 12 authoritative specification documents (`00-document-index.md` through `11-build-release-spec.md`).
- No prior Flutter or native project files existed in the root.
- The project is to be created directly in this directory preserving all specification documents.

## 3. Configuration Baseline
- **Application Name**: LockKeeper
- **Package ID / Application ID**: `com.lockkeeper.app`
- **Minimum SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 36 (Android 16)
- **Compile SDK**: 36
- **Architecture**: Flutter UI layer (Dart) + Native Android layer (Kotlin) via Platform Channels.
- **Assets**: User-uploaded shield emblem icon (`app_icon.jpg`) located in artifacts.

## 4. Next Step
Proceed to **Phase 1 — Baseline & Build Reproducibility** by initializing the Flutter project and validating clean compilation.
