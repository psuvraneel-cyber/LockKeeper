import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../core/services/platform_bridge.dart';
import '../theme/app_theme.dart';

class SelfLockGateScreen extends StatefulWidget {
  final VoidCallback onUnlocked;

  const SelfLockGateScreen({super.key, required this.onUnlocked});

  @override
  State<SelfLockGateScreen> createState() => _SelfLockGateScreenState();
}

class _SelfLockGateScreenState extends State<SelfLockGateScreen> with SingleTickerProviderStateMixin {
  final StringBuffer _pinBuffer = StringBuffer();
  String _statusText = 'Enter your PIN';
  Color _statusColor = AppTheme.textSecondary;
  bool _isVerifying = false;

  // Lockout state
  bool _isLockedOut = false;
  int _lockoutSecondsRemaining = 0;
  Timer? _lockoutTimer;

  // Shake animation controller
  late AnimationController _shakeController;
  late Animation<double> _shakeAnimation;

  @override
  void initState() {
    super.initState();
    _shakeController = AnimationController(
      duration: const Duration(milliseconds: 400),
      vsync: this,
    );
    _shakeAnimation = TweenSequence<double>([
      TweenSequenceItem(tween: Tween(begin: 0.0, end: -12.0), weight: 1),
      TweenSequenceItem(tween: Tween(begin: -12.0, end: 12.0), weight: 2),
      TweenSequenceItem(tween: Tween(begin: 12.0, end: -12.0), weight: 2),
      TweenSequenceItem(tween: Tween(begin: -12.0, end: 12.0), weight: 2),
      TweenSequenceItem(tween: Tween(begin: 12.0, end: 0.0), weight: 1),
    ]).animate(CurvedAnimation(parent: _shakeController, curve: Curves.easeInOut));
  }

  @override
  void dispose() {
    _lockoutTimer?.cancel();
    _shakeController.dispose();
    super.dispose();
  }

  void _onKeyPressed(String key) {
    if (_isVerifying || _isLockedOut) return;

    HapticFeedback.lightImpact();

    if (key == '⌫') {
      if (_pinBuffer.isNotEmpty) {
        setState(() {
          final str = _pinBuffer.toString();
          _pinBuffer.clear();
          _pinBuffer.write(str.substring(0, str.length - 1));
          _statusText = 'Enter your PIN';
          _statusColor = AppTheme.textSecondary;
        });
      }
      return;
    }

    if (key == '✓') {
      if (_pinBuffer.length >= 4) {
        _submitPin();
      } else {
        setState(() {
          _statusText = 'PIN must be at least 4 digits';
          _statusColor = AppTheme.danger;
        });
        _shakeController.forward(from: 0.0);
      }
      return;
    }

    // Number key pressed
    if (_pinBuffer.length < 8) {
      setState(() {
        _pinBuffer.write(key);
      });

      // Auto-submit if PIN length is reached
      if (_pinBuffer.length >= 4) {
        _submitPin();
      }
    }
  }

  Future<void> _submitPin() async {
    final enteredPin = _pinBuffer.toString();
    if (enteredPin.length < 4 || _isVerifying) return;

    setState(() {
      _isVerifying = true;
    });

    final result = await PlatformBridge.verifySelfLockPin(enteredPin);
    final success = result['success'] as bool? ?? false;
    final isLockedOut = result['isLockedOut'] as bool? ?? false;
    final remainingSec = (result['remainingLockoutSeconds'] as num?)?.toInt() ?? 0;
    final failedAttempts = (result['failedAttempts'] as num?)?.toInt() ?? 0;

    if (!mounted) return;

    if (success) {
      HapticFeedback.mediumImpact();
      widget.onUnlocked();
      return;
    }

    // Auth failed
    HapticFeedback.heavyImpact();
    _shakeController.forward(from: 0.0);
    _pinBuffer.clear();

    if (isLockedOut && remainingSec > 0) {
      _startLockout(remainingSec);
    } else {
      setState(() {
        _isVerifying = false;
        _statusText = 'Incorrect PIN ($failedAttempts/5)';
        _statusColor = AppTheme.danger;
      });
    }
  }

  void _startLockout(int seconds) {
    _lockoutTimer?.cancel();
    setState(() {
      _isLockedOut = true;
      _isVerifying = false;
      _lockoutSecondsRemaining = seconds;
      _statusText = 'Too many attempts. Locked out for ${seconds}s';
      _statusColor = AppTheme.danger;
    });

    _lockoutTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) {
        timer.cancel();
        return;
      }
      setState(() {
        _lockoutSecondsRemaining--;
        if (_lockoutSecondsRemaining <= 0) {
          timer.cancel();
          _isLockedOut = false;
          _statusText = 'Enter your PIN';
          _statusColor = AppTheme.textSecondary;
        } else {
          _statusText = 'Too many attempts. Locked out for ${_lockoutSecondsRemaining}s';
        }
      });
    });
  }

  Widget _buildDot(int index) {
    final isFilled = index < _pinBuffer.length;
    return Container(
      width: 16,
      height: 16,
      margin: const EdgeInsets.symmetric(horizontal: 10),
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: isFilled ? AppTheme.primary : AppTheme.surfaceElevated,
        border: Border.all(
          color: isFilled ? AppTheme.primary : AppTheme.border,
          width: 2,
        ),
      ),
    );
  }

  Widget _buildKeypadButton(String label) {
    Color btnColor;
    Color textColor = Colors.white;

    if (label == '✓') {
      btnColor = const Color(0xFF0284C7);
    } else if (label == '⌫') {
      btnColor = const Color(0xFF334155);
    } else {
      btnColor = const Color(0xFF1E293B);
    }

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      child: Material(
        color: btnColor,
        shape: const CircleBorder(),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => _onKeyPressed(label),
          child: SizedBox(
            width: 64,
            height: 64,
            child: Center(
              child: Text(
                label,
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  color: textColor,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          // Pressing Back on the lock screen safely exits/minimizes the app
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        backgroundColor: const Color(0xFF0F172A),
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(vertical: 16.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                mainAxisSize: MainAxisSize.min,
                children: [
                  // LockKeeper Shield Icon
                  Container(
                    width: 64,
                    height: 64,
                    decoration: BoxDecoration(
                      color: AppTheme.primary.withValues(alpha: 0.15),
                      shape: BoxShape.circle,
                      border: Border.all(color: AppTheme.primary.withValues(alpha: 0.4), width: 2),
                    ),
                    child: const Center(
                      child: Icon(Icons.lock_outline_rounded, color: AppTheme.primary, size: 32),
                    ),
                  ),
                  const SizedBox(height: 14),

                  // Title
                  const Text(
                    'LockKeeper',
                    style: TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.bold,
                      color: AppTheme.textPrimary,
                      letterSpacing: 0.5,
                    ),
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    'Unlock LockKeeper',
                    style: TextStyle(
                      fontSize: 13,
                      color: AppTheme.textMuted,
                    ),
                  ),
                  const SizedBox(height: 20),

              // Animated PIN Dots
              AnimatedBuilder(
                animation: _shakeAnimation,
                builder: (context, child) {
                  return Transform.translate(
                    offset: Offset(_shakeAnimation.value, 0),
                    child: child,
                  );
                },
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: List.generate(4, (index) => _buildDot(index)),
                ),
              ),
              const SizedBox(height: 20),

              // Status text
              Text(
                _statusText,
                style: TextStyle(
                  fontSize: 14,
                  color: _statusColor,
                  fontWeight: FontWeight.w500,
                ),
              ),

              const SizedBox(height: 16),

              // Keypad
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24.0),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: ['1', '2', '3'].map(_buildKeypadButton).toList(),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: ['4', '5', '6'].map(_buildKeypadButton).toList(),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: ['7', '8', '9'].map(_buildKeypadButton).toList(),
                    ),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: ['✓', '0', '⌫'].map(_buildKeypadButton).toList(),
                    ),
                  ],
                ),
              ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
