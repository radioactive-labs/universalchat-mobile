/// The Universal Chatbot chat for a deployment on the Mobile app channel, as
/// a native screen in a Flutter app.
///
/// ```dart
/// await UniversalChat.configure(serverUrl: 'https://acme.universalchatbot.com', deploymentId: '42');
/// await UniversalChat.present();
/// ```
library;

import 'package:flutter/services.dart';

/// The chat's calls, passed through to the Android and iOS SDKs.
class UniversalChat {
  UniversalChat._();

  static const MethodChannel _channel = MethodChannel('universal_chat');

  /// Call once at launch. [serverUrl] and [deploymentId] are shown in the
  /// deployment's App setup panel.
  static Future<void> configure({required String serverUrl, required String deploymentId}) =>
      _channel.invokeMethod('configure', {'serverUrl': serverUrl, 'deploymentId': deploymentId});

  /// Opens the chat full screen.
  static Future<void> present() => _channel.invokeMethod('present');

  /// Signs in the user [userToken] names: a JWT your server signs with the
  /// deployment's identity secret. Their conversation follows them to any
  /// device they sign in on, and agents see them as verified.
  ///
  /// Returns the user's id. Throws a [PlatformException] when the token does
  /// not verify.
  static Future<String> login(String userToken) async =>
      (await _channel.invokeMethod<String>('login', {'userToken': userToken}))!;

  /// Signs the user out. Their device stops getting their notifications, and
  /// the chat goes back to an anonymous conversation.
  static Future<void> logout() => _channel.invokeMethod('logout');

  /// Custom attributes about the visitor, such as their plan or the screen
  /// they opened the chat from: strings, numbers and booleans. Merged with
  /// what was set before; set a key to null to clear it.
  ///
  /// These come from the device, so a flow should not trust them for anything
  /// that matters. Put those in the user token's `attributes` claim instead.
  static Future<void> setAttributes(Map<String, Object?> attributes) =>
      _channel.invokeMethod('setAttributes', {'attributes': attributes});

  /// Pass the FCM registration token at launch and whenever it changes.
  static Future<void> setPushToken(String token) => _channel.invokeMethod('setPushToken', {'token': token});

  /// Whether a push message's data is one of ours.
  static Future<bool> isUniversalChatPush(Map<String, dynamic> data) async =>
      await _channel.invokeMethod<bool>('isUniversalChatPush', {'data': _strings(data)}) ?? false;

  /// Call with a message that arrived while the app was open. On Android it
  /// shows the reply as a notification that opens the chat, unless the chat
  /// is already open; on iOS the system shows it, so ask
  /// [shouldPresentNotification] whether to. Returns false for a message
  /// that is not ours.
  static Future<bool> handlePush(Map<String, dynamic> data, {String? title, String? body}) async =>
      await _channel.invokeMethod<bool>('handlePush', {'data': _strings(data), 'title': title, 'body': body}) ?? false;

  /// iOS: whether a notification arriving while the app is open should show.
  /// Not when the chat it is about is already on screen.
  static Future<bool> shouldPresentNotification(Map<String, dynamic> data) async =>
      await _channel.invokeMethod<bool>('shouldPresentNotification', {'data': _strings(data)}) ?? false;

  /// Opens the chat when the user tapped one of our notifications. Returns
  /// false for a notification that is not ours.
  static Future<bool> handleNotificationTap(Map<String, dynamic> data) async =>
      await _channel.invokeMethod<bool>('handleNotificationTap', {'data': _strings(data)}) ?? false;

  /// Lets your app take links tapped in the chat, such as one a flow sends to
  /// a screen of your app. A link starting with any of [prefixes] goes to
  /// [onLink] and the chat closes, so the visitor lands where it points; any
  /// other opens as usual. Call with no prefixes to stop.
  ///
  /// ```dart
  /// UniversalChat.setLinkHandler(prefixes: ['myapp://'], onLink: router.go);
  /// ```
  static Future<void> setLinkHandler({List<String> prefixes = const [], void Function(String url)? onLink}) {
    _onLink = prefixes.isEmpty ? null : onLink;
    _channel.setMethodCallHandler(_onLink == null ? null : _fromNative);
    return _channel.invokeMethod('setLinkPrefixes', {'prefixes': _onLink == null ? <String>[] : prefixes});
  }

  static void Function(String url)? _onLink;

  static Future<void> _fromNative(MethodCall call) async {
    if (call.method == 'openLink') _onLink?.call(call.arguments as String);
  }

  // FCM data values are strings; a plugin can hand them over typed.
  static Map<String, String> _strings(Map<String, dynamic> data) =>
      data.map((key, value) => MapEntry(key, value?.toString() ?? ''));
}
