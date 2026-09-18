import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lockkeeper/main.dart';
import 'package:lockkeeper/ui/screens/self_lock_gate_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('SelfLockGateScreen Widget Tests', () {
    const MethodChannel channel = MethodChannel('com.lockkeeper.app/channel');

    setUp(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (MethodCall call) async {
        if (call.method == 'verifySelfLockPin') {
          final pin = call.arguments['pin'] as String?;
          if (pin == '1234') {
            return {
              'success': true,
              'isLockedOut': false,
              'remainingLockoutSeconds': 0,
              'failedAttempts': 0,
            };
          } else {
            return {
              'success': false,
              'isLockedOut': false,
              'remainingLockoutSeconds': 0,
              'failedAttempts': 1,
            };
          }
        }
        if (call.method == 'checkSelfLockRequired') {
          return true;
        }
        if (call.method == 'isOnboardingComplete') {
          return true;
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    testWidgets('renders all self-lock elements correctly', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: SelfLockGateScreen(onUnlocked: () {}),
        ),
      );

      expect(find.text('LockKeeper'), findsOneWidget);
      expect(find.text('Unlock LockKeeper'), findsOneWidget);
      expect(find.text('Enter your PIN'), findsOneWidget);

      // Keypad numbers 0 to 9 and actions
      for (int i = 0; i <= 9; i++) {
        expect(find.text('$i'), findsOneWidget);
      }
      expect(find.text('✓'), findsOneWidget);
      expect(find.text('⌫'), findsOneWidget);
    });

    testWidgets('typing digits and backspace updates state', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: SelfLockGateScreen(onUnlocked: () {}),
        ),
      );

      // Tap 1, 2
      await tester.tap(find.text('1'));
      await tester.pump();
      await tester.tap(find.text('2'));
      await tester.pump();

      // Tap backspace
      await tester.tap(find.text('⌫'));
      await tester.pump();
      expect(find.text('Enter your PIN'), findsOneWidget);
    });

    testWidgets('incomplete PIN shows error when tick pressed', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: SelfLockGateScreen(onUnlocked: () {}),
        ),
      );

      await tester.tap(find.text('1'));
      await tester.pump();
      await tester.tap(find.text('2'));
      await tester.pump();

      // Tick pressed with only 2 digits
      await tester.tap(find.text('✓'));
      await tester.pumpAndSettle();

      expect(find.text('PIN must be at least 4 digits'), findsOneWidget);
    });

    testWidgets('correct PIN calls onUnlocked callback', (WidgetTester tester) async {
      bool unlocked = false;

      await tester.pumpWidget(
        MaterialApp(
          home: SelfLockGateScreen(
            onUnlocked: () {
              unlocked = true;
            },
          ),
        ),
      );

      // Enter correct PIN: 1234
      await tester.tap(find.text('1'));
      await tester.pump();
      await tester.tap(find.text('2'));
      await tester.pump();
      await tester.tap(find.text('3'));
      await tester.pump();
      await tester.tap(find.text('4'));
      await tester.pumpAndSettle();

      expect(unlocked, isTrue);
    });

    testWidgets('incorrect PIN displays error and failed attempts', (WidgetTester tester) async {
      bool unlocked = false;

      await tester.pumpWidget(
        MaterialApp(
          home: SelfLockGateScreen(
            onUnlocked: () {
              unlocked = true;
            },
          ),
        ),
      );

      // Enter incorrect PIN: 9999
      await tester.tap(find.text('9'));
      await tester.pump();
      await tester.tap(find.text('9'));
      await tester.pump();
      await tester.tap(find.text('9'));
      await tester.pump();
      await tester.tap(find.text('9'));
      await tester.pumpAndSettle();

      expect(unlocked, isFalse);
      expect(find.text('Incorrect PIN (1/5)'), findsOneWidget);
    });

    testWidgets('PopScope prevents popping back into protected content', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: SelfLockGateScreen(onUnlocked: () {}),
        ),
      );

      final popScopeFinder = find.byWidgetPredicate((widget) => widget is PopScope);
      expect(popScopeFinder, findsOneWidget);
      final popScope = tester.widget<PopScope>(popScopeFinder);
      expect(popScope.canPop, isFalse);
    });
  });

  group('LockKeeperApp Root Gate Tests', () {
    testWidgets('un-onboarded app goes to OnboardingScreen without gate', (WidgetTester tester) async {
      await tester.pumpWidget(const LockKeeperApp(isOnboardingComplete: false));
      await tester.pump();

      expect(find.text('LockKeeper Setup (1/9)'), findsOneWidget);
    });
  });
}
