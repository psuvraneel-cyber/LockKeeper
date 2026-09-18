import 'package:flutter_test/flutter_test.dart';
import 'package:lockkeeper/main.dart';

void main() {
  testWidgets('LockKeeperApp smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const LockKeeperApp(isOnboardingComplete: false));
    expect(find.text('LockKeeper Setup (1/9)'), findsOneWidget);
  });
}
