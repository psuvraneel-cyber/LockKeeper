class LockedAppModel {
  final String packageName;
  final bool isLocked;
  final int cooldownMinutes;
  final bool strictLock;
  final int? lockedUntilTimestamp;
  final int updatedAt;

  const LockedAppModel({
    required this.packageName,
    required this.isLocked,
    required this.cooldownMinutes,
    required this.strictLock,
    this.lockedUntilTimestamp,
    required this.updatedAt,
  });

  factory LockedAppModel.fromMap(Map<dynamic, dynamic> map) {
    return LockedAppModel(
      packageName: map['packageName'] as String? ?? '',
      isLocked: map['isLocked'] as bool? ?? true,
      cooldownMinutes: (map['cooldownMinutes'] as num?)?.toInt() ?? 15,
      strictLock: map['strictLock'] as bool? ?? false,
      lockedUntilTimestamp: (map['lockedUntilTimestamp'] as num?)?.toInt(),
      updatedAt: (map['updatedAt'] as num?)?.toInt() ?? 0,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'packageName': packageName,
      'isLocked': isLocked,
      'cooldownMinutes': cooldownMinutes,
      'strictLock': strictLock,
      'lockedUntilTimestamp': lockedUntilTimestamp,
      'updatedAt': updatedAt,
    };
  }

  bool get isInCooldown {
    if (lockedUntilTimestamp == null) return false;
    return lockedUntilTimestamp! > DateTime.now().millisecondsSinceEpoch;
  }

  Duration get remainingCooldown {
    if (lockedUntilTimestamp == null) return Duration.zero;
    final diff = lockedUntilTimestamp! - DateTime.now().millisecondsSinceEpoch;
    return diff > 0 ? Duration(milliseconds: diff) : Duration.zero;
  }
}
