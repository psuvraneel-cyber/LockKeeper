import 'package:flutter/material.dart';
import '../../core/services/platform_bridge.dart';
import '../theme/app_theme.dart';
import 'home_screen.dart';

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> with WidgetsBindingObserver {
  int _currentStep = 0;
  bool _isLoading = false;

  // Permission statuses
  bool _overlayGranted = false;
  bool _usageGranted = false;
  bool _accessibilityGranted = false;
  bool _deviceAdminGranted = false;
  bool _batteryExempted = false;

  // PIN setup
  final _pinController = TextEditingController();
  final _confirmPinController = TextEditingController();
  String? _pinError;

  // Admin password setup
  final _adminPassController = TextEditingController();
  final _confirmAdminPassController = TextEditingController();
  String? _adminPassError;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshPermissions();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pinController.dispose();
    _confirmPinController.dispose();
    _adminPassController.dispose();
    _confirmAdminPassController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermissions();
    }
  }

  Future<void> _refreshPermissions() async {
    setState(() => _isLoading = true);
    final status = await PlatformBridge.getProtectionStatus();
    if (mounted) {
      if (status.recoveryRequired && status.securityProvisioned) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const HomeScreen()),
        );
        return;
      }
      setState(() {
        _overlayGranted = status.isOverlayGranted;
        _usageGranted = status.isUsageGranted;
        _accessibilityGranted = status.isAccessibilityGranted;
        _deviceAdminGranted = status.isDeviceAdminGranted;
        _batteryExempted = status.isBatteryExempted;
        _isLoading = false;
      });
    }
  }

  void _nextStep() {
    if (_currentStep < 8) {
      setState(() {
        _currentStep++;
      });
    }
  }

  Future<void> _submitPin() async {
    final pin = _pinController.text.trim();
    final confirm = _confirmPinController.text.trim();

    if (pin.length < 4 || pin.length > 8 || !RegExp(r'^\d+$').hasMatch(pin)) {
      setState(() => _pinError = 'PIN must be between 4 and 8 digits');
      return;
    }
    if (pin != confirm) {
      setState(() => _pinError = 'PINs do not match');
      return;
    }

    final success = await PlatformBridge.setPin(pin);
    if (success) {
      setState(() => _pinError = null);
      _nextStep();
    } else {
      setState(() => _pinError = 'Failed to store PIN securely');
    }
  }

  Future<void> _submitAdminPassword() async {
    final pass = _adminPassController.text.trim();
    final confirm = _confirmAdminPassController.text.trim();
    final pin = _pinController.text.trim();

    if (pass.length < 4) {
      setState(() => _adminPassError = 'Password must be at least 4 characters');
      return;
    }
    if (pass == pin) {
      setState(() => _adminPassError = 'Admin password must be distinct from user PIN');
      return;
    }
    if (pass != confirm) {
      setState(() => _adminPassError = 'Passwords do not match');
      return;
    }

    final success = await PlatformBridge.setAdminPassword(pass);
    if (success) {
      setState(() => _adminPassError = null);
      _nextStep();
    } else {
      setState(() => _adminPassError = 'Failed to store admin password securely');
    }
  }

  Future<void> _finishOnboarding() async {
    await PlatformBridge.setOnboardingComplete(true);
    await PlatformBridge.startProtectionService();
    if (mounted) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const HomeScreen()),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('LockKeeper Setup (${_currentStep + 1}/9)'),
      ),
      body: SafeArea(
        child: _isLoading && _currentStep == 0
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
                child: _buildCurrentStep(),
              ),
      ),
    );
  }

  Widget _buildCurrentStep() {
    switch (_currentStep) {
      case 0:
        return _buildWelcomeStep();
      case 1:
        return _buildPermissionStep(
          title: 'Display Over Other Apps',
          description:
              'LockKeeper requires the overlay permission to draw the PIN and cooldown lock screen directly over apps when opened.',
          whyText: 'Required to intercept and gate access to locked apps immediately.',
          isGranted: _overlayGranted,
          permissionType: 'overlay',
        );
      case 2:
        return _buildPermissionStep(
          title: 'Usage Access',
          description:
              'Allows LockKeeper to identify which application is running in the foreground as an active fallback.',
          whyText: 'Ensures lock enforcement continues even if accessibility events are delayed.',
          isGranted: _usageGranted,
          permissionType: 'usage',
        );
      case 3:
        return _buildPermissionStep(
          title: 'Accessibility Service',
          description:
              'Provides real-time foreground window detection and intercepts attempts to uninstall or tamper with LockKeeper in Settings.',
          whyText: 'Primary friction mechanism to detect app switches and protect system settings.',
          isGranted: _accessibilityGranted,
          permissionType: 'accessibility',
        );
      case 4:
        return _buildPermissionStep(
          title: 'Device Admin',
          description:
              'Activates Device Administrator to block impulsive uninstallation. Any uninstall attempt requires deactivation via Settings, which is guarded by your Admin Password.',
          whyText: 'Creates essential anti-uninstall friction.',
          isGranted: _deviceAdminGranted,
          permissionType: 'deviceAdmin',
        );
      case 5:
        return _buildPermissionStep(
          title: 'Battery Optimization Exemption',
          description:
              'Exempting LockKeeper prevents the Android system from killing background monitoring during deep sleep.',
          whyText: 'Reduces the likelihood that aggressive OEM memory managers stop protection.',
          isGranted: _batteryExempted,
          permissionType: 'battery',
          isOptional: true,
        );
      case 6:
        return _buildPinStep();
      case 7:
        return _buildAdminPasswordStep();
      case 8:
        return _buildDoneStep();
      default:
        return const SizedBox();
    }
  }

  Widget _buildWelcomeStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Center(
          child: Container(
            width: 100,
            height: 100,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: AppTheme.border, width: 2),
            ),
            clipBehavior: Clip.antiAlias,
            child: Image.asset('assets/icon/app_icon.png', fit: BoxFit.cover),
          ),
        ),
        const SizedBox(height: 32),
        const Text(
          'Personal App Friction & Focus Barrier',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: AppTheme.textPrimary),
        ),
        const SizedBox(height: 16),
        const Text(
          'LockKeeper enforces intentional friction on distracting applications behind strict cooldowns, protected by layered system security.',
          style: TextStyle(fontSize: 15, color: AppTheme.textSecondary, height: 1.5),
        ),
        const SizedBox(height: 24),
        _buildBullet('Enforce strict cooldowns where apps cannot be unlocked until time expires.'),
        _buildBullet('Separate daily user PIN from high-stakes Admin Password.'),
        _buildBullet('Intercept Android Settings to prevent impulsive uninstallation or deactivation.'),
        _buildBullet('100% local, zero internet dependency, keystore-backed security.'),
        const SizedBox(height: 40),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _nextStep,
            child: const Text('Begin Setup'),
          ),
        ),
      ],
    );
  }

  Widget _buildBullet(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12.0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.check_circle, size: 20, color: AppTheme.primary),
          const SizedBox(width: 12),
          Expanded(
            child: Text(text, style: const TextStyle(fontSize: 14, color: AppTheme.textSecondary)),
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionStep({
    required String title,
    required String description,
    required String whyText,
    required bool isGranted,
    required String permissionType,
    bool isOptional = false,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: isGranted ? AppTheme.success.withValues(alpha: 0.15) : AppTheme.warning.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: isGranted ? AppTheme.success : AppTheme.warning,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                isGranted ? Icons.check : Icons.shield_outlined,
                size: 16,
                color: isGranted ? AppTheme.success : AppTheme.warning,
              ),
              const SizedBox(width: 6),
              Text(
                isGranted ? 'PERMISSION GRANTED' : 'ACTION REQUIRED',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: isGranted ? AppTheme.success : AppTheme.warning,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        Text(title, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
        const SizedBox(height: 12),
        Text(description, style: const TextStyle(fontSize: 15, color: AppTheme.textSecondary, height: 1.5)),
        const SizedBox(height: 20),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: AppTheme.surface,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: AppTheme.border),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.info_outline, size: 20, color: AppTheme.primary),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Why LockKeeper needs this:',
                        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13, color: AppTheme.textPrimary)),
                    const SizedBox(height: 4),
                    Text(whyText, style: const TextStyle(fontSize: 13, color: AppTheme.textSecondary)),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 36),
        if (!isGranted)
          SizedBox(
            width: double.infinity,
            child: ElevatedButton.icon(
              onPressed: () => PlatformBridge.requestPermission(permissionType),
              icon: const Icon(Icons.open_in_new, size: 18),
              label: Text('Grant $title'),
            ),
          ),
        if (!isGranted && isOptional) ...[
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              onPressed: _nextStep,
              child: const Text('Skip for Now'),
            ),
          ),
        ],
        if (isGranted)
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _nextStep,
              child: const Text('Continue to Next Step'),
            ),
          ),
      ],
    );
  }

  Widget _buildPinStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Set Daily User PIN',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
        const SizedBox(height: 12),
        const Text(
          'This numeric PIN (4–8 digits) is used daily to unlock protected applications when allowed by cooldown policies.',
          style: TextStyle(fontSize: 15, color: AppTheme.textSecondary, height: 1.5),
        ),
        const SizedBox(height: 24),
        TextField(
          controller: _pinController,
          keyboardType: TextInputType.number,
          obscureText: true,
          maxLength: 8,
          decoration: const InputDecoration(
            labelText: 'New PIN',
            hintText: '4 to 8 digits',
            counterText: '',
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _confirmPinController,
          keyboardType: TextInputType.number,
          obscureText: true,
          maxLength: 8,
          decoration: const InputDecoration(
            labelText: 'Confirm PIN',
            hintText: 'Re-enter PIN',
            counterText: '',
          ),
        ),
        if (_pinError != null) ...[
          const SizedBox(height: 12),
          Text(_pinError!, style: const TextStyle(color: AppTheme.danger, fontSize: 13)),
        ],
        const SizedBox(height: 32),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _submitPin,
            child: const Text('Save PIN & Continue'),
          ),
        ),
      ],
    );
  }

  Widget _buildAdminPasswordStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: AppTheme.danger.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: AppTheme.danger),
          ),
          child: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.warning_amber_rounded, size: 16, color: AppTheme.danger),
              SizedBox(width: 6),
              Text(
                'HIGH STAKES CREDENTIAL',
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold, color: AppTheme.danger),
              ),
            ],
          ),
        ),
        const SizedBox(height: 20),
        const Text('Set Admin Password',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: AppTheme.textPrimary)),
        const SizedBox(height: 12),
        const Text(
          'This password is separate from your daily PIN. You will need it to modify settings, change credentials, or uninstall/deactivate LockKeeper.',
          style: TextStyle(fontSize: 15, color: AppTheme.textSecondary, height: 1.5),
        ),
        const SizedBox(height: 16),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: const Color(0xFF1E1313),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: const Color(0xFF7F1D1D)),
          ),
          child: const Text(
            '⚠️ IMPORTANT: There is no recovery backdoor by design. If you forget your Admin Password, uninstallation requires ADB or Safe Mode recovery.',
            style: TextStyle(fontSize: 13, color: Color(0xFFFCA5A5), height: 1.4),
          ),
        ),
        const SizedBox(height: 24),
        TextField(
          controller: _adminPassController,
          obscureText: true,
          decoration: const InputDecoration(
            labelText: 'Admin Password',
            hintText: 'Enter robust password',
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _confirmAdminPassController,
          obscureText: true,
          decoration: const InputDecoration(
            labelText: 'Confirm Admin Password',
            hintText: 'Re-enter admin password',
          ),
        ),
        if (_adminPassError != null) ...[
          const SizedBox(height: 12),
          Text(_adminPassError!, style: const TextStyle(color: AppTheme.danger, fontSize: 13)),
        ],
        const SizedBox(height: 32),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _submitAdminPassword,
            child: const Text('Save Admin Password & Proceed'),
          ),
        ),
      ],
    );
  }

  Widget _buildDoneStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Center(
          child: Icon(Icons.verified_user, size: 72, color: AppTheme.success),
        ),
        const SizedBox(height: 24),
        const Center(
          child: Text(
            'Setup Complete!',
            style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: AppTheme.textPrimary),
          ),
        ),
        const SizedBox(height: 12),
        const Center(
          child: Text(
            'LockKeeper is active and monitoring your device.',
            style: TextStyle(fontSize: 15, color: AppTheme.textSecondary),
          ),
        ),
        const SizedBox(height: 32),
        _buildSummaryRow('Overlay Gate', _overlayGranted),
        _buildSummaryRow('Usage Stats Fallback', _usageGranted),
        _buildSummaryRow('Accessibility Protection', _accessibilityGranted),
        _buildSummaryRow('Device Admin Barrier', _deviceAdminGranted),
        _buildSummaryRow('User PIN Configured', true),
        _buildSummaryRow('Admin Password Configured', true),
        const SizedBox(height: 40),
        SizedBox(
          width: double.infinity,
          child: ElevatedButton(
            onPressed: _finishOnboarding,
            child: const Text('Go to App Locker'),
          ),
        ),
      ],
    );
  }

  Widget _buildSummaryRow(String label, bool active) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontSize: 15, color: AppTheme.textPrimary)),
          Row(
            children: [
              Icon(
                active ? Icons.check_circle : Icons.error,
                size: 18,
                color: active ? AppTheme.success : AppTheme.danger,
              ),
              const SizedBox(width: 6),
              Text(
                active ? 'Active' : 'Disabled',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.bold,
                  color: active ? AppTheme.success : AppTheme.danger,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
