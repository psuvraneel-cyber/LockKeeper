import 'dart:async';
import 'package:flutter/material.dart';
import '../../core/models/protection_status_model.dart';
import '../../core/services/platform_bridge.dart';
import '../theme/app_theme.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  ProtectionStatusModel? _status;
  bool _selfLockEnabled = false;
  int _selfLockTimeout = 0;
  bool _isLoading = true;
  StreamSubscription? _eventsSubscription;

  @override
  void initState() {
    super.initState();
    _loadStatus();
    _eventsSubscription = PlatformBridge.eventsStream.listen((_) {
      if (mounted) _loadStatus();
    }, onError: (_) {});
  }

  @override
  void dispose() {
    _eventsSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadStatus() async {
    setState(() => _isLoading = true);
    final status = await PlatformBridge.getProtectionStatus();
    final selfLock = await PlatformBridge.isSelfLockEnabled();
    final timeout = await PlatformBridge.getSelfLockTimeout();
    if (mounted) {
      setState(() {
        _status = status;
        _selfLockEnabled = selfLock;
        _selfLockTimeout = timeout;
        _isLoading = false;
      });
    }
  }

  Future<void> _showChangePinDialog() async {
    final hasPin = _status?.hasPin ?? false;
    final currentPinController = TextEditingController();
    final newPinController = TextEditingController();
    final confirmPinController = TextEditingController();
    String? error;

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          backgroundColor: AppTheme.surface,
          title: Text(hasPin ? 'Change User PIN' : 'Set User PIN'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (hasPin) ...[
                TextField(
                  controller: currentPinController,
                  keyboardType: TextInputType.number,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: 'Current PIN'),
                ),
                const SizedBox(height: 12),
              ],
              TextField(
                controller: newPinController,
                keyboardType: TextInputType.number,
                obscureText: true,
                maxLength: 8,
                decoration: const InputDecoration(labelText: 'New PIN (4-8 digits)', counterText: ''),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: confirmPinController,
                keyboardType: TextInputType.number,
                obscureText: true,
                maxLength: 8,
                decoration: const InputDecoration(labelText: 'Confirm New PIN', counterText: ''),
              ),
              if (error != null) ...[
                const SizedBox(height: 12),
                Text(error!, style: const TextStyle(color: AppTheme.danger, fontSize: 13)),
              ],
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () async {
                if (hasPin) {
                  final current = currentPinController.text.trim();
                  final isCurrentValid = await PlatformBridge.verifyPin(current);
                  if (!isCurrentValid) {
                    setDialogState(() => error = 'Incorrect current PIN');
                    return;
                  }
                }
                final newPin = newPinController.text.trim();
                final confirm = confirmPinController.text.trim();

                if (newPin.length < 4 || newPin.length > 8 || !RegExp(r'^\d+$').hasMatch(newPin)) {
                  setDialogState(() => error = 'PIN must be 4 to 8 digits');
                  return;
                }
                if (newPin != confirm) {
                  setDialogState(() => error = 'New PINs do not match');
                  return;
                }

                final success = await PlatformBridge.setPin(newPin);
                if (success) {
                  if (ctx.mounted) Navigator.of(ctx).pop();
                  if (mounted) {
                    ScaffoldMessenger.of(this.context).showSnackBar(
                      SnackBar(content: Text(hasPin ? 'PIN updated successfully' : 'PIN set successfully')),
                    );
                    _loadStatus();
                  }
                } else {
                  setDialogState(() => error = 'Failed to update PIN');
                }
              },
              child: Text(hasPin ? 'Update PIN' : 'Set PIN'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showChangeAdminPasswordDialog() async {
    final hasAdminPass = _status?.hasAdminPassword ?? false;
    final currentPassController = TextEditingController();
    final newPassController = TextEditingController();
    final confirmPassController = TextEditingController();
    String? error;

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          backgroundColor: AppTheme.surface,
          title: Text(hasAdminPass ? 'Change Admin Password' : 'Set Admin Password'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (hasAdminPass) ...[
                TextField(
                  controller: currentPassController,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: 'Current Admin Password'),
                ),
                const SizedBox(height: 12),
              ],
              TextField(
                controller: newPassController,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'New Admin Password (min 4 chars)'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: confirmPassController,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Confirm New Password'),
              ),
              if (error != null) ...[
                const SizedBox(height: 12),
                Text(error!, style: const TextStyle(color: AppTheme.danger, fontSize: 13)),
              ],
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () async {
                if (hasAdminPass) {
                  final current = currentPassController.text.trim();
                  final isCurrentValid = await PlatformBridge.verifyAdminPassword(current);
                  if (!isCurrentValid) {
                    setDialogState(() => error = 'Incorrect current password');
                    return;
                  }
                }
                final newPass = newPassController.text.trim();
                final confirm = confirmPassController.text.trim();

                if (newPass.length < 4) {
                  setDialogState(() => error = 'Password must be at least 4 characters');
                  return;
                }
                if (newPass != confirm) {
                  setDialogState(() => error = 'Passwords do not match');
                  return;
                }

                final success = await PlatformBridge.setAdminPassword(newPass);
                if (success) {
                  if (ctx.mounted) Navigator.of(ctx).pop();
                  if (mounted) {
                    ScaffoldMessenger.of(this.context).showSnackBar(
                      SnackBar(content: Text(hasAdminPass ? 'Admin Password updated successfully' : 'Admin Password set successfully')),
                    );
                    _loadStatus();
                  }
                } else {
                  setDialogState(() => error = 'Failed to update password');
                }
              },
              child: Text(hasAdminPass ? 'Update Password' : 'Set Password'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _showRecoveryCredentialsDialog() async {
    final pinController = TextEditingController();
    final confirmPinController = TextEditingController();
    final adminPassController = TextEditingController();
    final confirmAdminPassController = TextEditingController();
    String? error;

    await showDialog(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          backgroundColor: AppTheme.surface,
          title: Row(
            children: const [
              Icon(Icons.restore_rounded, color: AppTheme.danger, size: 24),
              SizedBox(width: 8),
              Text('Security Recovery', style: TextStyle(fontSize: 18)),
            ],
          ),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Set a new User PIN and Admin Password to restore LockKeeper protection and synchronize security state.',
                  style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: pinController,
                  keyboardType: TextInputType.number,
                  obscureText: true,
                  maxLength: 8,
                  decoration: const InputDecoration(labelText: 'New User PIN (4-8 digits)', counterText: ''),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: confirmPinController,
                  keyboardType: TextInputType.number,
                  obscureText: true,
                  maxLength: 8,
                  decoration: const InputDecoration(labelText: 'Confirm User PIN', counterText: ''),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: adminPassController,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: 'New Admin Password (min 4 chars)'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: confirmAdminPassController,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: 'Confirm Admin Password'),
                ),
                if (error != null) ...[
                  const SizedBox(height: 12),
                  Text(error!, style: const TextStyle(color: AppTheme.danger, fontSize: 13)),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () async {
                final pin = pinController.text.trim();
                final confirmPin = confirmPinController.text.trim();
                final pass = adminPassController.text.trim();
                final confirmPass = confirmAdminPassController.text.trim();

                if (pin.length < 4 || pin.length > 8 || !RegExp(r'^\d+$').hasMatch(pin)) {
                  setDialogState(() => error = 'PIN must be 4 to 8 digits');
                  return;
                }
                if (pin != confirmPin) {
                  setDialogState(() => error = 'User PINs do not match');
                  return;
                }
                if (pass.length < 4) {
                  setDialogState(() => error = 'Admin Password must be at least 4 characters');
                  return;
                }
                if (pass == pin) {
                  setDialogState(() => error = 'Admin Password must be distinct from User PIN');
                  return;
                }
                if (pass != confirmPass) {
                  setDialogState(() => error = 'Admin Passwords do not match');
                  return;
                }

                final success = await PlatformBridge.resolveRecovery(pin: pin, adminPassword: pass);
                if (success) {
                  if (ctx.mounted) Navigator.of(ctx).pop();
                  if (mounted) {
                    ScaffoldMessenger.of(this.context).showSnackBar(
                      const SnackBar(content: Text('Security recovery completed. Protection active.')),
                    );
                    _loadStatus();
                  }
                } else {
                  setDialogState(() => error = 'Failed to apply recovery configuration');
                }
              },
              style: ElevatedButton.styleFrom(backgroundColor: AppTheme.danger),
              child: const Text('Restore Protection'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _handleSelfLockToggle(bool enable) async {
    if (enable) {
      await PlatformBridge.setSelfLockEnabled(true);
      setState(() => _selfLockEnabled = true);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Protect LockKeeper enabled')),
        );
      }
    } else {
      // Security-sensitive operation: Require Admin Password to disable self-lock
      final verified = await _showRequireAdminPasswordDialog(
        title: 'Disable LockKeeper Protection',
        message: 'Enter your Admin Password to disable self-lock protection.',
      );
      if (verified == true) {
        await PlatformBridge.setSelfLockEnabled(false);
        setState(() => _selfLockEnabled = false);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Protect LockKeeper disabled')),
          );
        }
      }
    }
  }

  Future<bool?> _showRequireAdminPasswordDialog({
    required String title,
    required String message,
  }) async {
    final passwordController = TextEditingController();
    String? error;

    return showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          backgroundColor: AppTheme.surface,
          title: Row(
            children: [
              const Icon(Icons.security, color: AppTheme.danger, size: 22),
              const SizedBox(width: 8),
              Expanded(child: Text(title, style: const TextStyle(fontSize: 18))),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                message,
                style: const TextStyle(color: AppTheme.textSecondary, fontSize: 13, height: 1.4),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: passwordController,
                obscureText: true,
                autofocus: true,
                decoration: InputDecoration(
                  labelText: 'Admin Password',
                  errorText: error,
                  prefixIcon: const Icon(Icons.lock_outline, color: AppTheme.textMuted),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              onPressed: () async {
                final pass = passwordController.text.trim();
                final isValid = await PlatformBridge.verifyAdminPassword(pass);
                if (isValid) {
                  if (ctx.mounted) Navigator.of(ctx).pop(true);
                } else {
                  setDialogState(() {
                    error = 'Incorrect admin password';
                  });
                }
              },
              child: const Text('Authorize'),
            ),
          ],
        ),
      ),
    );
  }

  String _formatTimeoutLabel(int seconds) {
    switch (seconds) {
      case 0:
        return 'Immediately';
      case 30:
        return '30 seconds';
      case 60:
        return '1 minute';
      case 300:
        return '5 minutes';
      default:
        return '${seconds}s';
    }
  }

  Future<void> _showTimeoutDialog() async {
    final options = [0, 30, 60, 300];
    await showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppTheme.surface,
        title: const Text('Lock Timeout'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: options.map((sec) {
            final isSelected = _selfLockTimeout == sec;
            return ListTile(
              title: Text(_formatTimeoutLabel(sec)),
              trailing: isSelected ? const Icon(Icons.check, color: AppTheme.primary) : null,
              onTap: () async {
                await PlatformBridge.setSelfLockTimeout(sec);
                setState(() => _selfLockTimeout = sec);
                if (ctx.mounted) Navigator.of(ctx).pop();
              },
            );
          }).toList(),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Security & Settings'),
      ),
      body: SafeArea(
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: const EdgeInsets.all(20.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (_status?.recoveryRequired == true) ...[
                      Card(
                        color: AppTheme.danger.withValues(alpha: 0.18),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                          side: const BorderSide(color: AppTheme.danger),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(16.0),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: const [
                                  Icon(Icons.gpp_bad_rounded, color: AppTheme.danger, size: 24),
                                  SizedBox(width: 8),
                                  Text(
                                    'Security Recovery Required',
                                    style: TextStyle(color: AppTheme.danger, fontWeight: FontWeight.bold, fontSize: 16),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 8),
                              const Text(
                                'Device Admin is active in Android, but local security credentials are missing. Please re-configure your credentials to restore protection.',
                                style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
                              ),
                              const SizedBox(height: 12),
                              ElevatedButton(
                                onPressed: _showRecoveryCredentialsDialog,
                                style: ElevatedButton.styleFrom(backgroundColor: AppTheme.danger),
                                child: const Text('Re-configure PIN & Credentials'),
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                    ],

                    const Text('Authentication',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
                    const SizedBox(height: 12),
                    Card(
                      child: Column(
                        children: [
                          ListTile(
                            leading: const Icon(Icons.pin_outlined, color: AppTheme.primary),
                            title: const Text('Change User PIN'),
                            subtitle: const Text('Daily access credential for locked apps'),
                            trailing: const Icon(Icons.chevron_right, color: AppTheme.textMuted),
                            onTap: _showChangePinDialog,
                          ),
                          const Divider(height: 1, color: AppTheme.border),
                          ListTile(
                            leading: const Icon(Icons.security, color: AppTheme.danger),
                            title: const Text('Change Admin Password'),
                            subtitle: const Text('Master tamper protection credential'),
                            trailing: const Icon(Icons.chevron_right, color: AppTheme.textMuted),
                            onTap: _showChangeAdminPasswordDialog,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 28),

                    const Text('App Protection',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
                    const SizedBox(height: 12),
                    Card(
                      child: Column(
                        children: [
                          SwitchListTile(
                            secondary: const Icon(Icons.shield_outlined, color: AppTheme.primary),
                            title: const Text('Protect LockKeeper'),
                            subtitle: const Text('Require your PIN when opening LockKeeper'),
                            value: _selfLockEnabled,
                            onChanged: (val) => _handleSelfLockToggle(val),
                          ),
                          if (_selfLockEnabled) ...[
                            const Divider(height: 1, color: AppTheme.border),
                            ListTile(
                              leading: const Icon(Icons.timer_outlined, color: AppTheme.textMuted),
                              title: const Text('Lock Timeout'),
                              subtitle: Text(_formatTimeoutLabel(_selfLockTimeout)),
                              trailing: const Icon(Icons.chevron_right, color: AppTheme.textMuted),
                              onTap: _showTimeoutDialog,
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(height: 28),

                    const Text('Protection Health',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
                    const SizedBox(height: 12),
                    Card(
                      child: Column(
                        children: [
                          _buildPermissionItem('Overlay Permission', _status?.isOverlayGranted ?? false, 'overlay'),
                          const Divider(height: 1, color: AppTheme.border),
                          _buildPermissionItem('Usage Access', _status?.isUsageGranted ?? false, 'usage'),
                          const Divider(height: 1, color: AppTheme.border),
                          _buildAccessibilityItem(),
                          const Divider(height: 1, color: AppTheme.border),
                          _buildPermissionItem('Device Administrator', _status?.isDeviceAdminGranted ?? false, 'deviceAdmin'),
                          const Divider(height: 1, color: AppTheme.border),
                          _buildPermissionItem('Battery Optimization Exemption', _status?.isBatteryExempted ?? false, 'battery'),
                        ],
                      ),
                    ),
                    const SizedBox(height: 28),

                    const Text('About',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
                    const SizedBox(height: 12),
                    Card(
                      child: Padding(
                        padding: const EdgeInsets.all(16.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Container(
                                  width: 48,
                                  height: 48,
                                  decoration: BoxDecoration(
                                    borderRadius: BorderRadius.circular(12),
                                    border: Border.all(color: AppTheme.border),
                                  ),
                                  clipBehavior: Clip.antiAlias,
                                  child: Image.asset('assets/icon/app_icon.png', fit: BoxFit.cover),
                                ),
                                const SizedBox(width: 14),
                                const Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text('LockKeeper', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                                    SizedBox(height: 2),
                                    Text('Version 1.0.0 (API 36 / Android 16)', style: TextStyle(color: AppTheme.textMuted, fontSize: 13)),
                                  ],
                                ),
                              ],
                            ),
                            const SizedBox(height: 16),
                            const Text(
                              'Single-user personal friction tool. Designed for tamper-resistant self-regulation without cloud accounts or data leakage.',
                              style: TextStyle(color: AppTheme.textSecondary, fontSize: 13, height: 1.4),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _buildAccessibilityItem() {
    final isGranted = _status?.isAccessibilityGranted ?? false;
    final isConnected = _status?.isAccessibilityConnected ?? false;
    final isOperational = _status?.isAccessibilityOperational ?? false;

    String labelText;
    Color statusColor;
    String? subtitleText;

    if (isOperational) {
      labelText = 'Active';
      statusColor = AppTheme.success;
    } else if (isGranted && !isConnected) {
      labelText = 'Disconnected';
      statusColor = AppTheme.warning;
      subtitleText = 'Permission enabled, but service binder is dormant';
    } else {
      labelText = 'Disabled';
      statusColor = AppTheme.danger;
      subtitleText = 'Accessibility service permission required';
    }

    return ListTile(
      title: const Text('Accessibility Service', style: TextStyle(fontSize: 15)),
      subtitle: subtitleText != null
          ? Text(subtitleText, style: const TextStyle(fontSize: 12, color: AppTheme.textMuted))
          : null,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: statusColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              labelText,
              style: TextStyle(
                color: statusColor,
                fontWeight: FontWeight.bold,
                fontSize: 12,
              ),
            ),
          ),
          if (!isOperational) ...[
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(Icons.build_outlined, color: AppTheme.primary, size: 20),
              onPressed: () async {
                await PlatformBridge.requestPermission('accessibility');
                _loadStatus();
              },
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildPermissionItem(String title, bool isGranted, String type) {
    return ListTile(
      title: Text(title, style: const TextStyle(fontSize: 15)),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: isGranted ? AppTheme.success.withValues(alpha: 0.15) : AppTheme.danger.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              isGranted ? 'Active' : 'Revoked',
              style: TextStyle(
                color: isGranted ? AppTheme.success : AppTheme.danger,
                fontWeight: FontWeight.bold,
                fontSize: 12,
              ),
            ),
          ),
          if (!isGranted) ...[
            const SizedBox(width: 8),
            IconButton(
              icon: const Icon(Icons.build_outlined, color: AppTheme.primary, size: 20),
              onPressed: () async {
                await PlatformBridge.requestPermission(type);
                _loadStatus();
              },
            ),
          ],
        ],
      ),
    );
  }
}
