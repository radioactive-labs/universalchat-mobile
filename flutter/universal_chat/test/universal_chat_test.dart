import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:universal_chat/universal_chat.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('universal_chat');
  final calls = <MethodCall>[];
  dynamic Function(MethodCall call) reply = (_) => null;

  setUp(() {
    calls.clear();
    reply = (_) => null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return reply(call);
    });
  });

  test('configure passes the server and deployment', () async {
    await UniversalChat.configure(serverUrl: 'https://acme.example.com', deploymentId: '42');

    expect(calls.single.method, 'configure');
    expect(calls.single.arguments, {'serverUrl': 'https://acme.example.com', 'deploymentId': '42'});
  });

  test('login returns the user id', () async {
    reply = (_) => 'user-7';

    expect(await UniversalChat.login('signed.jwt'), 'user-7');
    expect(calls.single.arguments, {'userToken': 'signed.jwt'});
  });

  test('a refused user token throws', () async {
    reply = (_) => throw PlatformException(code: 'invalid_user_token', message: 'That user token is not valid');

    expect(UniversalChat.login('bad'), throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'invalid_user_token')));
  });

  test('attributes keep their types', () async {
    await UniversalChat.setAttributes({'plan': 'pro', 'seats': 3, 'trial': false, 'old': null});

    expect(calls.single.arguments, {
      'attributes': {'plan': 'pro', 'seats': 3, 'trial': false, 'old': null},
    });
  });

  test('push data is handed over as strings', () async {
    reply = (_) => true;

    final ours = await UniversalChat.handlePush({'ucb': 1, 'ucb_deployment_id': 42}, title: 'Sam', body: 'Hi');

    expect(ours, isTrue);
    expect(calls.single.arguments, {
      'data': {'ucb': '1', 'ucb_deployment_id': '42'},
      'title': 'Sam',
      'body': 'Hi',
    });
  });

  test('a message that is not ours reads as false', () async {
    expect(await UniversalChat.handleNotificationTap({'other': 'x'}), isFalse);
  });

  test('the link handler sends its prefixes and hears links back', () async {
    final opened = <String>[];
    await UniversalChat.setLinkHandler(prefixes: ['myapp://'], onLink: opened.add);

    expect(calls.single.method, 'setLinkPrefixes');
    expect(calls.single.arguments, {'prefixes': ['myapp://']});

    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      'universal_chat',
      const StandardMethodCodec().encodeMethodCall(const MethodCall('openLink', 'myapp://orders/1001')),
      (_) {},
    );
    expect(opened, ['myapp://orders/1001']);
  });

  test('no prefixes stops taking links', () async {
    await UniversalChat.setLinkHandler(prefixes: ['myapp://'], onLink: (_) {});
    calls.clear();

    await UniversalChat.setLinkHandler();

    expect(calls.single.arguments, {'prefixes': <String>[]});
  });
}
