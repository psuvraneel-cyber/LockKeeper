import 'dart:async';
import 'package:flutter/services.dart';
import '../models/installed_app_model.dart';
import '../models/locked_app_model.dart';
import '../models/protection_status_model.dart';

class PlatformBridge {
  static const MethodChannel _methodChannel = MethodChannel('com.lockkeeper.app/channel');
  static const EventChannel _eventChannel = EventChannel('com.lockkeeper.app/events');

  static Stream<dynamic>? _eventsStream;

  static Stream<dynamic> get eventsStream {
    _eventsStream ??= _eventChannel.receiveBroadcastStream();
    return _eventsStream!;
  }

  static Future<bool> checkPermission(String type) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('checkPermissionStatus', {'type': type});
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> requestPermission(String type) async {
    try {
      await _methodChannel.invokeMethod('requestPermission', {'type': type});
    } catch (_) {}
  }

  static Future<bool> setPin(String pin) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('setPin', {'pin': pin});
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> verifyPin(String pin) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('verifyPin', {'pin': pin});
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> hasPin() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('hasPin');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> setAdminPassword(String password) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('setAdminPassword', {'password': password});
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> verifyAdminPassword(String password) async {
    try {
      final dynamic result = await _methodChannel.invokeMethod('verifyAdminPassword', {'password': password});
      if (result is bool) return result;
      if (result is Map) {
        return result['success'] == true;
      }
      return false;
    } catch (_) {
      return false;
    }
  }

  static Future<Map<String, dynamic>> verifyAdminPasswordDetailed(String password) async {
    try {
      final dynamic result = await _methodChannel.invokeMethod('verifyAdminPassword', {'password': password});
      if (result is Map) {
        return Map<String, dynamic>.from(result);
      }
      return {'success': result == true, 'status': result == true ? 'SUCCESS' : 'DENIED'};
    } catch (e) {
      return {'success': false, 'status': 'ERROR', 'error': e.toString()};
    }
  }

  static Future<bool> hasAdminPassword() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('hasAdminPassword');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<List<LockedAppModel>> getLockedApps() async {
    try {
      final List<dynamic>? rawList = await _methodChannel.invokeMethod('getLockedApps');
      if (rawList == null) return [];
      return rawList.map((e) => LockedAppModel.fromMap(e as Map<dynamic, dynamic>)).toList();
    } catch (_) {
      return [];
    }
  }

  static Future<bool> setLockedApp({
    required String packageName,
    required bool isLocked,
    required int cooldownMinutes,
    required bool strictLock,
  }) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('setLockedApp', {
        'packageName': packageName,
        'isLocked': isLocked,
        'cooldownMinutes': cooldownMinutes,
        'strictLock': strictLock,
      });
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> removeLockedApp(String packageName) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('removeLockedApp', {
        'packageName': packageName,
      });
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<List<InstalledAppModel>> getInstalledApps() async {
    try {
      final List<dynamic>? rawList = await _methodChannel.invokeMethod('getInstalledApps');
      if (rawList == null) return [];
      return rawList.map((e) => InstalledAppModel.fromMap(e as Map<dynamic, dynamic>)).toList();
    } catch (_) {
      return [];
    }
  }

  static Future<void> startProtectionService() async {
    try {
      await _methodChannel.invokeMethod('startProtectionService');
    } catch (_) {}
  }

  static Future<ProtectionStatusModel> getProtectionStatus() async {
    try {
      final Map<dynamic, dynamic>? raw = await _methodChannel.invokeMethod('getProtectionStatus');
      if (raw == null) {
        return const ProtectionStatusModel.unknown(reasons: ['No response from native protection repository']);
      }
      return ProtectionStatusModel.fromMap(raw);
    } catch (e) {
      return ProtectionStatusModel.unknown(reasons: ['Platform channel exception: $e']);
    }
  }

  static Future<ProtectionStatusModel> getSecurityHealth() => getProtectionStatus();

  static Future<bool> isOnboardingComplete() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('isOnboardingComplete');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> isSecurityProvisioned() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('isSecurityProvisioned');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> completeInitialProvisioning() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('completeInitialProvisioning');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> setOnboardingComplete(bool complete) async {
    try {
      await _methodChannel.invokeMethod('setOnboardingComplete', {'complete': complete});
    } catch (_) {}
  }

  static Future<bool> resolveRecovery({
    required String pin,
    required String adminPassword,
  }) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('resolveRecovery', {
        'pin': pin,
        'adminPassword': adminPassword,
      });
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> isSelfLockEnabled() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('isSelfLockEnabled');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> setSelfLockEnabled(bool enabled) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('setSelfLockEnabled', {'enabled': enabled});
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<int> getSelfLockTimeout() async {
    try {
      final int? result = await _methodChannel.invokeMethod('getSelfLockTimeout');
      return result ?? 0;
    } catch (_) {
      return 0;
    }
  }

  static Future<bool> setSelfLockTimeout(int timeoutSeconds) async {
    try {
      final bool? result = await _methodChannel.invokeMethod('setSelfLockTimeout', {'timeoutSeconds': timeoutSeconds});
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<bool> checkSelfLockRequired() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('checkSelfLockRequired');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<Map<String, dynamic>> verifySelfLockPin(String pin) async {
    try {
      final Map<dynamic, dynamic>? raw = await _methodChannel.invokeMethod('verifySelfLockPin', {'pin': pin});
      if (raw == null) {
        return {'success': false, 'isLockedOut': false, 'remainingLockoutSeconds': 0, 'failedAttempts': 0};
      }
      return Map<String, dynamic>.from(raw);
    } catch (_) {
      return {'success': false, 'isLockedOut': false, 'remainingLockoutSeconds': 0, 'failedAttempts': 0};
    }
  }

  static Future<void> reportAppBackgrounded() async {
    try {
      await _methodChannel.invokeMethod('reportAppBackgrounded');
    } catch (_) {}
  }

  static Future<bool> reportAppResumed() async {
    try {
      final bool? result = await _methodChannel.invokeMethod('reportAppResumed');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  static Future<void> invalidateSelfLockSession() async {
    try {
      await _methodChannel.invokeMethod('invalidateSelfLockSession');
    } catch (_) {}
  }
}
