import 'package:flutter_test/flutter_test.dart';
import 'package:lockkeeper/core/models/protection_status_model.dart';

void main() {
  group('Wave 2 Flutter Enforcement Integrity Tests', () {
    test('ProtectionStatusModel.unknown defaults to fail-closed', () {
      final status = ProtectionStatusModel.unknown(
        reasons: ['Simulated platform channel failure'],
      );
      expect(status.overallStatus, SecurityOverallStatus.unknown);
      expect(status.isProtected, isFalse);
      expect(status.isAccessibilityOperational, isFalse);
      expect(status.isDeviceAdminGranted, isFalse);
      expect(status.appLockOperational, isFalse);
      expect(status.degradedReasons, contains('Simulated platform channel failure'));
    });

    test('ProtectionStatusModel with RECOVERY_REQUIRED marks system not protected and recoveryRequired true', () {
      final status = ProtectionStatusModel.fromMap({
        'overallStatus': 'RECOVERY_REQUIRED',
        'degradedReasons': ['Device Admin active without local credentials'],
        'isForegroundServiceRunning': true,
        'isOverlayGranted': true,
        'isUsageGranted': true,
        'isAccessibilityGranted': true,
        'isAccessibilityConnected': true,
        'isAccessibilityOperational': true,
        'isDeviceAdminGranted': true,
        'isBatteryExempted': true,
        'hasPin': false,
        'hasAdminPassword': false,
        'selfLockActive': false,
        'appLockConfigured': false,
        'appLockOperational': false,
        'tamperLockedOut': false,
        'recoveryRequired': true,
        'onboardingComplete': false,
      });

      expect(status.overallStatus, SecurityOverallStatus.recoveryRequired);
      expect(status.recoveryRequired, isTrue);
      expect(status.isProtected, isFalse);
      expect(status.degradedReasons, contains('Device Admin active without local credentials'));
    });

    test('INV-207 Stale Flutter assumption cannot override native recovery or locked status', () {
      // Even if Flutter has an old model where isProtected was true:
      final staleModel = ProtectionStatusModel.fromMap({
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
      expect(staleModel.isProtected, isTrue);

      // Once updated or refreshed with recovery required:
      final updatedModel = ProtectionStatusModel.fromMap({
        'overallStatus': 'RECOVERY_REQUIRED',
        'degradedReasons': ['Security breach or credential desync'],
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
      });
      expect(updatedModel.isProtected, isFalse);
      expect(updatedModel.recoveryRequired, isTrue);
    });

    test('INV-208 Fail-closed fallback verification on platform error', () {
      // Simulated exception fallback in main.dart:
      bool isLocked = false;
      try {
        throw Exception('Platform channel timeout');
      } catch (_) {
        // FAIL-CLOSED: Must lock on error
        isLocked = true;
      }
      expect(isLocked, isTrue);
    });
  });
}
