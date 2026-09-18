import 'package:flutter_test/flutter_test.dart';
import 'package:lockkeeper/core/models/protection_status_model.dart';

void main() {
  group('Wave 3 Flutter Runtime Resilience Tests', () {
    test('INV-312 Corrupted or partial map from native channel safely defaults to fail-closed', () {
      final partialModel = ProtectionStatusModel.fromMap({
        // Missing critical keys, simulating truncated IPC payload
        'overallStatus': null,
      });

      expect(partialModel.overallStatus, SecurityOverallStatus.unknown);
      expect(partialModel.isProtected, isFalse);
      expect(partialModel.appLockOperational, isFalse);
    });

    test('INV-315 Tamper lockout in model strictly flags tamperLockedOut and not protected', () {
      final lockoutModel = ProtectionStatusModel.fromMap({
        'overallStatus': 'DEGRADED',
        'degradedReasons': ['Admin lockout active: 5 failed attempts'],
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
        'tamperLockedOut': true,
        'recoveryRequired': false,
        'onboardingComplete': true,
      });

      expect(lockoutModel.tamperLockedOut, isTrue);
      expect(lockoutModel.isProtected, isFalse);
    });

    test('INV-314 Un-onboarded runtime state cannot claim protected status', () {
      final unonboardedModel = ProtectionStatusModel.fromMap({
        'overallStatus': 'INITIALIZING',
        'degradedReasons': ['Onboarding not completed'],
        'isForegroundServiceRunning': false,
        'isOverlayGranted': false,
        'isUsageGranted': false,
        'isAccessibilityGranted': false,
        'isAccessibilityConnected': false,
        'isAccessibilityOperational': false,
        'isDeviceAdminGranted': false,
        'isBatteryExempted': false,
        'hasPin': false,
        'hasAdminPassword': false,
        'selfLockActive': false,
        'appLockConfigured': false,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': false,
        'onboardingComplete': false,
      });

      expect(unonboardedModel.onboardingComplete, isFalse);
      expect(unonboardedModel.isProtected, isFalse);
      expect(unonboardedModel.overallStatus, SecurityOverallStatus.initializing);
    });

    test('INV-308 Dynamic transition from protected to degraded preserves fail-closed behavior', () {
      var currentStatus = ProtectionStatusModel.fromMap({
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
        'onboardingComplete': true,
      });
      expect(currentStatus.isProtected, isTrue);

      // Accessibility service drops / crashes:
      currentStatus = ProtectionStatusModel.fromMap({
        'overallStatus': 'DEGRADED',
        'degradedReasons': ['Accessibility service disconnected'],
        'isForegroundServiceRunning': true,
        'isOverlayGranted': true,
        'isUsageGranted': true,
        'isAccessibilityGranted': true,
        'isAccessibilityConnected': false,
        'isAccessibilityOperational': false,
        'isDeviceAdminGranted': true,
        'isBatteryExempted': true,
        'hasPin': true,
        'hasAdminPassword': true,
        'selfLockActive': true,
        'appLockConfigured': true,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': false,
        'onboardingComplete': true,
      });

      expect(currentStatus.isProtected, isFalse);
      expect(currentStatus.isAccessibilityOperational, isFalse);
      expect(currentStatus.degradedReasons, contains('Accessibility service disconnected'));
    });
  });
}
