import 'dart:convert';
import 'dart:typed_data';

class InstalledAppModel {
  final String packageName;
  final String name;
  final bool isSystem;
  final String? iconBase64;
  Uint8List? _cachedIconBytes;

  InstalledAppModel({
    required this.packageName,
    required this.name,
    required this.isSystem,
    this.iconBase64,
  });

  factory InstalledAppModel.fromMap(Map<dynamic, dynamic> map) {
    return InstalledAppModel(
      packageName: map['packageName'] as String? ?? '',
      name: map['name'] as String? ?? '',
      isSystem: map['isSystem'] as bool? ?? false,
      iconBase64: map['iconBase64'] as String?,
    );
  }

  Uint8List? get iconBytes {
    if (_cachedIconBytes != null) return _cachedIconBytes;
    if (iconBase64 == null || iconBase64!.isEmpty) return null;
    try {
      _cachedIconBytes = base64Decode(iconBase64!);
      return _cachedIconBytes;
    } catch (_) {
      return null;
    }
  }
}
