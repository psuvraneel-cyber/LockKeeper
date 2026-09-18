import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'core/services/platform_bridge.dart';
import 'ui/screens/home_screen.dart';
import 'ui/screens/onboarding_screen.dart';
import 'ui/screens/self_lock_gate_screen.dart';
import 'ui/theme/app_theme.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Dark navigation and status bar styling
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: AppTheme.background,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );

  bool onboardingComplete = false;
  bool recoveryRequired = false;
  bool securityProvisioned = false;
  try {
    final status = await PlatformBridge.getProtectionStatus();
    onboardingComplete = status.onboardingComplete;
    recoveryRequired = status.recoveryRequired;
    securityProvisioned = status.securityProvisioned;
  } catch (_) {
    onboardingComplete = false;
    recoveryRequired = false;
    securityProvisioned = false;
  }

  runApp(LockKeeperApp(
    isOnboardingComplete: onboardingComplete,
    isRecoveryRequired: recoveryRequired,
    isSecurityProvisioned: securityProvisioned,
  ));
}

class LockKeeperApp extends StatefulWidget {
  final bool isOnboardingComplete;
  final bool isRecoveryRequired;
  final bool isSecurityProvisioned;

  const LockKeeperApp({
    super.key,
    required this.isOnboardingComplete,
    this.isRecoveryRequired = false,
    this.isSecurityProvisioned = false,
  });

  @override
  State<LockKeeperApp> createState() => _LockKeeperAppState();
}

class _LockKeeperAppState extends State<LockKeeperApp> with WidgetsBindingObserver {
  bool _isLocked = false;
  bool _isInitialized = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkInitialLock();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Future<void> _checkInitialLock() async {
    if (!widget.isOnboardingComplete) {
      if (mounted) {
        setState(() {
          _isLocked = false;
          _isInitialized = true;
        });
      }
      return;
    }

    try {
      final required = await PlatformBridge.checkSelfLockRequired();
      if (mounted) {
        setState(() {
          _isLocked = required;
          _isInitialized = true;
        });
      }
    } catch (_) {
      // Fail closed: exception must never permit an unauthenticated session
      if (mounted) {
        setState(() {
          _isLocked = true;
          _isInitialized = true;
        });
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (!widget.isOnboardingComplete) return;

    if (state == AppLifecycleState.paused || state == AppLifecycleState.inactive) {
      PlatformBridge.reportAppBackgrounded();
    } else if (state == AppLifecycleState.resumed) {
      _handleAppResumed();
    }
  }

  Future<void> _handleAppResumed() async {
    try {
      final required = await PlatformBridge.reportAppResumed();
      if (mounted && required && !_isLocked) {
        setState(() {
          _isLocked = true;
        });
      }
    } catch (_) {
      if (mounted && !_isLocked) {
        setState(() {
          _isLocked = true;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'LockKeeper',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.darkTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: ThemeMode.dark,
      builder: (context, child) {
        if (!widget.isOnboardingComplete) {
          return child!;
        }

        if (!_isInitialized) {
          // Dark splash while checking initial lock to prevent UI flash
          return Container(color: const Color(0xFF0F172A));
        }

        return Stack(
          children: [
            child ?? const SizedBox.shrink(),
            if (_isLocked)
              Positioned.fill(
                child: SelfLockGateScreen(
                  onUnlocked: () {
                    setState(() {
                      _isLocked = false;
                    });
                  },
                ),
              ),
          ],
        );
      },
      home: (widget.isOnboardingComplete || (widget.isRecoveryRequired && widget.isSecurityProvisioned))
          ? const HomeScreen()
          : const OnboardingScreen(),
    );
  }
}
