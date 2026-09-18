import 'package:flutter_test/flutter_test.dart';
import 'package:lockkeeper/core/models/protection_status_model.dart';

void main() {
  group('ProtectionStatusModel Security State Tests', () {
    test('parses fully protected state correctly', () {
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
        'onboardingComplete': true,
      };

      final status = ProtectionStatusModel.fromMap(map);
      expect(status.overallStatus, SecurityOverallStatus.protected);
      expect(status.isProtected, isTrue);
      expect(status.isAccessibilityOperational, isTrue);
      expect(status.degradedReasons, isEmpty);
    });

    test('parses degraded state with reasons when accessibility is disconnected', () {
      final map = {
        'overallStatus': 'DEGRADED',
        'degradedReasons': ['Accessibility Service enabled but disconnected'],
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
      };

      final status = ProtectionStatusModel.fromMap(map);
      expect(status.overallStatus, SecurityOverallStatus.degraded);
      expect(status.isProtected, isFalse);
      expect(status.isAccessibilityGranted, isTrue);
      expect(status.isAccessibilityConnected, isFalse);
      expect(status.isAccessibilityOperational, isFalse);
      expect(status.degradedReasons, contains('Accessibility Service enabled but disconnected'));
    });

    test('parses recoveryRequired state accurately', () {
      final map = {
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
        'onboardingComplete': false,
      };

      final status = ProtectionStatusModel.fromMap(map);
      expect(status.overallStatus, SecurityOverallStatus.recoveryRequired);
      expect(status.recoveryRequired, isTrue);
      expect(status.isProtected, isFalse);
    });

    test('ProtectionStatusModel.unknown() safely defaults to fail-closed state', () {
      final status = ProtectionStatusModel.unknown(reasons: ['Platform channel communication error']);
      expect(status.overallStatus, SecurityOverallStatus.unknown);
      expect(status.isProtected, isFalse);
      expect(status.isAccessibilityOperational, isFalse);
      expect(status.isDeviceAdminGranted, isFalse);
      expect(status.degradedReasons, contains('Platform channel communication error'));
    });

    test('unknown status never claims isProtected', () {
      final status = ProtectionStatusModel.unknown();
      expect(status.isProtected, isFalse);
      expect(status.overallStatus == SecurityOverallStatus.protected, isFalse);
    });
  });
}
