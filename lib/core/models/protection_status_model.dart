enum SecurityOverallStatus {
  protected,
  configured,
  degraded,
  recoveryRequired,
  initializing,
  setupInProgress,
  unknown;

  bool get isProtected => this == SecurityOverallStatus.protected;
  bool get isConfigured => this == SecurityOverallStatus.configured;
  bool get isDegraded => this == SecurityOverallStatus.degraded;
  bool get isRecoveryRequired => this == SecurityOverallStatus.recoveryRequired;
  bool get isInitializing => this == SecurityOverallStatus.initializing;
  bool get isSetupInProgress => this == SecurityOverallStatus.setupInProgress;
  bool get isUnknown => this == SecurityOverallStatus.unknown;
}

class ProtectionStatusModel {
  final SecurityOverallStatus overallStatus;
  final List<String> degradedReasons;
  final bool isForegroundServiceRunning;
  final bool isOverlayGranted;
  final bool isUsageGranted;
  final bool isAccessibilityGranted;
  final bool isAccessibilityConnected;
  final bool isAccessibilityOperational;
  final bool isDeviceAdminGranted;
  final bool isBatteryExempted;
  final bool hasPin;
  final bool hasAdminPassword;
  final bool selfLockActive;
  final bool appLockConfigured;
  final bool appLockOperational;
  final bool tamperLockedOut;
  final bool recoveryRequired;
  final bool securityProvisioned;
  final bool onboardingComplete;

  const ProtectionStatusModel({
    this.overallStatus = SecurityOverallStatus.unknown,
    this.degradedReasons = const [],
    required this.isForegroundServiceRunning,
    required this.isOverlayGranted,
    required this.isUsageGranted,
    required this.isAccessibilityGranted,
    this.isAccessibilityConnected = false,
    this.isAccessibilityOperational = false,
    required this.isDeviceAdminGranted,
    required this.isBatteryExempted,
    this.hasPin = false,
    this.hasAdminPassword = false,
    this.selfLockActive = false,
    this.appLockConfigured = false,
    this.appLockOperational = false,
    this.tamperLockedOut = false,
    this.recoveryRequired = false,
    this.securityProvisioned = false,
    this.onboardingComplete = false,
  });

  const ProtectionStatusModel.unknown({
    List<String> reasons = const ['Security status could not be determined'],
  }) : this(
         overallStatus: SecurityOverallStatus.unknown,
         degradedReasons: reasons,
         isForegroundServiceRunning: false,
         isOverlayGranted: false,
         isUsageGranted: false,
         isAccessibilityGranted: false,
         isAccessibilityConnected: false,
         isAccessibilityOperational: false,
         isDeviceAdminGranted: false,
         isBatteryExempted: false,
       );

  factory ProtectionStatusModel.fromMap(Map<dynamic, dynamic> map) {
    final statusStr = map['overallStatus'] as String? ?? '';
    final status = switch (statusStr) {
      'PROTECTED' => SecurityOverallStatus.protected,
      'CONFIGURED' => SecurityOverallStatus.configured,
      'DEGRADED' => SecurityOverallStatus.degraded,
      'RECOVERY_REQUIRED' => SecurityOverallStatus.recoveryRequired,
      'INITIALIZING' => SecurityOverallStatus.initializing,
      'SETUP_IN_PROGRESS' => SecurityOverallStatus.setupInProgress,
      _ => SecurityOverallStatus.unknown,
    };

    final rawReasons = map['degradedReasons'];
    final reasons = rawReasons is List
        ? rawReasons.map((e) => e.toString()).toList()
        : <String>[];

    final a11yGranted = map['isAccessibilityGranted'] as bool? ?? false;
    final a11yConnected = map['isAccessibilityConnected'] as bool? ?? false;
    final a11yOperational = map['isAccessibilityOperational'] as bool? ?? (a11yGranted && a11yConnected);

    return ProtectionStatusModel(
      overallStatus: status,
      degradedReasons: reasons,
      isForegroundServiceRunning: map['isForegroundServiceRunning'] as bool? ?? false,
      isOverlayGranted: map['isOverlayGranted'] as bool? ?? false,
      isUsageGranted: map['isUsageGranted'] as bool? ?? false,
      isAccessibilityGranted: a11yGranted,
      isAccessibilityConnected: a11yConnected,
      isAccessibilityOperational: a11yOperational,
      isDeviceAdminGranted: map['isDeviceAdminGranted'] as bool? ?? false,
      isBatteryExempted: map['isBatteryExempted'] as bool? ?? false,
      hasPin: map['hasPin'] as bool? ?? false,
      hasAdminPassword: map['hasAdminPassword'] as bool? ?? false,
      selfLockActive: map['selfLockActive'] as bool? ?? false,
      appLockConfigured: map['appLockConfigured'] as bool? ?? false,
      appLockOperational: map['appLockOperational'] as bool? ?? false,
      tamperLockedOut: map['tamperLockedOut'] as bool? ?? false,
      recoveryRequired: map['recoveryRequired'] as bool? ?? false,
      securityProvisioned: map['securityProvisioned'] as bool? ?? false,
      onboardingComplete: map['onboardingComplete'] as bool? ?? false,
    );
  }

  // Authoritative status truth: Never claims protected if overallStatus is not protected
  bool get isFullyProtected => overallStatus == SecurityOverallStatus.protected;
  bool get isProtected => isFullyProtected;
  bool get isSetupInProgress => overallStatus == SecurityOverallStatus.setupInProgress;

  bool get areAllProtectionsActive =>
      isOverlayGranted &&
      isUsageGranted &&
      isAccessibilityOperational &&
      isDeviceAdminGranted;

  List<String> get missingPermissions {
    final list = <String>[];
    if (!isOverlayGranted) list.add('overlay');
    if (!isUsageGranted) list.add('usage');
    if (!isAccessibilityGranted) list.add('accessibility');
    if (!isDeviceAdminGranted) list.add('deviceAdmin');
    return list;
  }
}
