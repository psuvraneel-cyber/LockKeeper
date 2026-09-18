import 'package:flutter_test/flutter_test.dart';
import 'package:lockkeeper/core/models/protection_status_model.dart';

void main() {
  group('Incident Resolution - First Run / Device Admin Setup Safeguards', () {
    test('parses SETUP_IN_PROGRESS state correctly with securityProvisioned false', () {
      final map = {
        'overallStatus': 'SETUP_IN_PROGRESS',
        'degradedReasons': <String>[],
        'isForegroundServiceRunning': false,
        'isOverlayGranted': true,
        'isUsageGranted': true,
        'isAccessibilityGranted': true,
        'isAccessibilityConnected': true,
        'isAccessibilityOperational': true,
        'isDeviceAdminGranted': true,
        'isBatteryExempted': false,
        'hasPin': false,
        'hasAdminPassword': false,
        'selfLockActive': false,
        'appLockConfigured': false,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': false,
        'securityProvisioned': false,
        'isSetupInProgress': true,
        'onboardingComplete': false,
      };

      final status = ProtectionStatusModel.fromMap(map);
      expect(status.overallStatus, SecurityOverallStatus.setupInProgress);
      expect(status.isSetupInProgress, isTrue);
      expect(status.securityProvisioned, isFalse);
      expect(status.recoveryRequired, isFalse);
      expect(status.isProtected, isFalse);
      expect(status.onboardingComplete, isFalse);
    });

    test('clean install with active Device Admin does NOT trigger recoveryRequired in model', () {
      final map = {
        'overallStatus': 'SETUP_IN_PROGRESS',
        'degradedReasons': <String>[],
        'isForegroundServiceRunning': false,
        'isOverlayGranted': false,
        'isUsageGranted': false,
        'isAccessibilityGranted': false,
        'isAccessibilityConnected': false,
        'isAccessibilityOperational': false,
        'isDeviceAdminGranted': true, // Device Admin active
        'isBatteryExempted': false,
        'hasPin': false,              // No PIN yet
        'hasAdminPassword': false,    // No Admin Password yet
        'selfLockActive': false,
        'appLockConfigured': false,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': false,
        'securityProvisioned': false,
        'isSetupInProgress': true,
        'onboardingComplete': false,
      };

      final status = ProtectionStatusModel.fromMap(map);
      expect(status.overallStatus, SecurityOverallStatus.setupInProgress);
      expect(status.isSetupInProgress, isTrue);
      expect(status.recoveryRequired, isFalse);
      expect(status.securityProvisioned, isFalse);
    });

    test('genuine recovery requires BOTH recoveryRequired true AND securityProvisioned true', () {
      final compromisedMap = {
        'overallStatus': 'RECOVERY_REQUIRED',
        'degradedReasons': ['Device Admin active without local credentials'],
        'isForegroundServiceRunning': false,
        'isOverlayGranted': false,
        'isUsageGranted': false,
        'isAccessibilityGranted': false,
        'isAccessibilityConnected': false,
        'isAccessibilityOperational': false,
        'isDeviceAdminGranted': true,
        'isBatteryExempted': false,
        'hasPin': false,
        'hasAdminPassword': false,
        'selfLockActive': false,
        'appLockConfigured': false,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': true,
        'securityProvisioned': true,
        'isSetupInProgress': false,
        'onboardingComplete': true,
      };

      final status = ProtectionStatusModel.fromMap(compromisedMap);
      expect(status.overallStatus, SecurityOverallStatus.recoveryRequired);
      expect(status.recoveryRequired, isTrue);
      expect(status.securityProvisioned, isTrue);
      expect(status.isSetupInProgress, isFalse);

      // Onboarding screen gate check:
      // status.recoveryRequired && status.securityProvisioned
      final shouldEvictToHomeScreen = status.recoveryRequired && status.securityProvisioned;
      expect(shouldEvictToHomeScreen, isTrue);
    });

    test('unprovisioned device with spurious recovery flag cannot evict onboarding', () {
      final spuriousMap = {
        'overallStatus': 'RECOVERY_REQUIRED',
        'degradedReasons': ['Spurious recovery trigger'],
        'isForegroundServiceRunning': false,
        'isOverlayGranted': false,
        'isUsageGranted': false,
        'isAccessibilityGranted': false,
        'isAccessibilityConnected': false,
        'isAccessibilityOperational': false,
        'isDeviceAdminGranted': true,
        'isBatteryExempted': false,
        'hasPin': false,
        'hasAdminPassword': false,
        'selfLockActive': false,
        'appLockConfigured': false,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': true,
        'securityProvisioned': false, // Unprovisioned!
        'isSetupInProgress': false,
        'onboardingComplete': false,
      };

      final status = ProtectionStatusModel.fromMap(spuriousMap);
      // Even if recoveryRequired is true, because securityProvisioned is false,
      // onboarding screen will NOT evict to home screen
      final shouldEvictToHomeScreen = status.recoveryRequired && status.securityProvisioned;
      expect(shouldEvictToHomeScreen, isFalse);
    });

    test('provisioned state parses securityProvisioned true', () {
      final map = {
        'overallStatus': 'PROTECTED',
        'degradedReasons': <String>[],
        'isForegroundServiceRunning': true,
        'isOverlayGranted': true,
        'isUsageGranted': true,
        'isAccessibilityGranted': true,
        'isAccessibilityConnected': true,
        'isAccessibilityOperational': true,
        'isDeviceAdminGranted': true,
        'isBatteryExempted': true,
        'hasPin': true,
        'hasAdminPassword': true,
        'selfLockActive': true,
        'appLockConfigured': true,
        'appLockOperational': true,
        'tamperLockedOut': false,
        'recoveryRequired': false,
        'securityProvisioned': true,
        'isSetupInProgress': false,
        'onboardingComplete': true,
      };

      final status = ProtectionStatusModel.fromMap(map);
      expect(status.overallStatus, SecurityOverallStatus.protected);
      expect(status.securityProvisioned, isTrue);
      expect(status.isSetupInProgress, isFalse);
      expect(status.isProtected, isTrue);
    });
  });
}
