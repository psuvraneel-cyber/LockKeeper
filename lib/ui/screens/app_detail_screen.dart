import 'package:flutter/material.dart';
import '../../core/models/installed_app_model.dart';
import '../../core/models/locked_app_model.dart';
import '../../core/services/platform_bridge.dart';
import '../theme/app_theme.dart';

class AppDetailScreen extends StatefulWidget {
  final InstalledAppModel app;
  final LockedAppModel? lockedConfig;

  const AppDetailScreen({
    super.key,
    required this.app,
    this.lockedConfig,
  });

  @override
  State<AppDetailScreen> createState() => _AppDetailScreenState();
}

class _AppDetailScreenState extends State<AppDetailScreen> {
  late bool _isLocked;
  late int _cooldownMinutes;
  late bool _strictLock;
  bool _isSaving = false;

  final List<int> _presetOptions = [15, 60, 240, 1440]; // 15m, 1h, 4h, 24h

  @override
  void initState() {
    super.initState();
    _isLocked = widget.lockedConfig?.isLocked ?? true;
    _cooldownMinutes = widget.lockedConfig?.cooldownMinutes ?? 15;
    _strictLock = widget.lockedConfig?.strictLock ?? false;
  }

  String _formatDuration(int minutes) {
    if (minutes < 60) return '${minutes}m';
    if (minutes % 60 == 0) return '${minutes ~/ 60}h';
    return '${minutes ~/ 60}h ${minutes % 60}m';
  }

  Future<void> _showCustomDurationDialog() async {
    final controller = TextEditingController(text: _cooldownMinutes.toString());
    final result = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.surface,
        title: const Text('Custom Cooldown (Minutes)'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            hintText: 'e.g. 30, 45, 120',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              final val = int.tryParse(controller.text);
              if (val != null && val > 0 && val <= 10080) { // Max 7 days
                Navigator.of(ctx).pop(val);
              }
            },
            child: const Text('Set'),
          ),
        ],
      ),
    );

    if (result != null) {
      setState(() {
        _cooldownMinutes = result;
      });
    }
  }

  Future<void> _saveConfig() async {
    setState(() => _isSaving = true);
    await PlatformBridge.setLockedApp(
      packageName: widget.app.packageName,
      isLocked: _isLocked,
      cooldownMinutes: _cooldownMinutes,
      strictLock: _strictLock,
    );
    if (mounted) {
      setState(() => _isSaving = false);
      Navigator.of(context).pop(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final iconBytes = widget.app.iconBytes;

    return Scaffold(
      appBar: AppBar(
        title: const Text('App Lock Settings'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                children: [
                  Container(
                    width: 64,
                    height: 64,
                    decoration: BoxDecoration(
                      color: AppTheme.surfaceElevated,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: AppTheme.border),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: iconBytes != null
                        ? Image.memory(iconBytes, fit: BoxFit.cover)
                        : const Icon(Icons.android, size: 32, color: AppTheme.textMuted),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          widget.app.name,
                          style: const TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          widget.app.packageName,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppTheme.textMuted,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 32),

              // Lock Switch
              Card(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 12.0),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Protection Status',
                            style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                          ),
                          SizedBox(height: 4),
                          Text(
                            'Require PIN authentication to open',
                            style: TextStyle(fontSize: 13, color: AppTheme.textSecondary),
                          ),
                        ],
                      ),
                      Switch(
                        value: _isLocked,
                        onChanged: (val) => setState(() => _isLocked = val),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),

              if (_isLocked) ...[
                // Cooldown Duration
                const Text(
                  'Cooldown Duration',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppTheme.textPrimary),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Cooldown enforced after closing or locking the app.',
                  style: TextStyle(fontSize: 13, color: AppTheme.textSecondary),
                ),
                const SizedBox(height: 16),
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    ..._presetOptions.map((duration) {
                      final isSelected = _cooldownMinutes == duration;
                      return ChoiceChip(
                        label: Text(_formatDuration(duration)),
                        selected: isSelected,
                        onSelected: (selected) {
                          if (selected) {
                            setState(() => _cooldownMinutes = duration);
                          }
                        },
                        selectedColor: AppTheme.primary,
                        backgroundColor: AppTheme.surfaceElevated,
                        labelStyle: TextStyle(
                          color: isSelected ? const Color(0xFF0F172A) : AppTheme.textPrimary,
                          fontWeight: FontWeight.bold,
                        ),
                      );
                    }),
                    ChoiceChip(
                      label: Text(
                        !_presetOptions.contains(_cooldownMinutes)
                            ? 'Custom: ${_formatDuration(_cooldownMinutes)}'
                            : 'Custom...',
                      ),
                      selected: !_presetOptions.contains(_cooldownMinutes),
                      onSelected: (_) => _showCustomDurationDialog(),
                      selectedColor: AppTheme.primary,
                      backgroundColor: AppTheme.surfaceElevated,
                      labelStyle: TextStyle(
                        color: !_presetOptions.contains(_cooldownMinutes)
                            ? const Color(0xFF0F172A)
                            : AppTheme.textPrimary,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 28),

                // Strict Lock Toggle
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Row(
                              children: [
                                Icon(Icons.lock_clock, color: AppTheme.warning, size: 20),
                                SizedBox(width: 8),
                                Text(
                                  'Strict Lock',
                                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                                ),
                              ],
                            ),
                            Switch(
                              value: _strictLock,
                              onChanged: (val) => setState(() => _strictLock = val),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        Text(
                          _strictLock
                              ? 'STRICT MODE: During cooldown, the app CANNOT be opened even with the correct PIN. Keypad is completely disabled until cooldown timer expires.'
                              : 'STANDARD MODE: PIN can always reopen the app, but each exit resets the cooldown timer.',
                          style: TextStyle(
                            fontSize: 13,
                            color: _strictLock ? AppTheme.warning : AppTheme.textSecondary,
                            height: 1.4,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 40),

              // Save Button
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: _isSaving ? null : _saveConfig,
                  child: _isSaving
                      ? const SizedBox(
                          height: 20,
                          width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Save Settings'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
