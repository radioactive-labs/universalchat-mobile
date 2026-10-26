# universal_chat

The UniversalChatbot chat as a native screen in a Flutter app, for a
deployment on the **Mobile app** channel. It wraps the Android and iOS SDKs,
so the chat looks and behaves exactly as it does in a native app: the
deployment's widget settings, signed-in users, visitor attributes, files,
past conversations and push.

Android 7.0 (API 24) and iOS 16 or later. On iOS the plugin works with
CocoaPods, or as a Swift package once Flutter's Swift Package Manager support
is on:

```
flutter config --enable-swift-package-manager
```

## Use

```dart
import 'package:universal_chat/universal_chat.dart';

// Once, at launch. Both values are in the deployment's App setup panel.
await UniversalChat.configure(serverUrl: 'SERVER_URL', deploymentId: 'DEPLOYMENT_ID');

// From a button
await UniversalChat.present();

// Facts about the visitor, for flows and agents
await UniversalChat.setAttributes({'screen': 'orders', 'plan': 'pro'});
```

When a user signs in to your app, fetch a token for them from your server and
pass it on. Sign them out with your own sign-out:

```dart
try {
  await UniversalChat.login(tokenFromYourServer);
} on PlatformException catch (error) {
  // error.code is invalid_user_token when the token does not verify
}

await UniversalChat.logout();
```

## Links into your app

A flow or an agent can send a link to a screen of your app. Name the links
your app takes by how they start; a tap on one closes the chat and calls
`onLink`, and any other link opens as usual:

```dart
UniversalChat.setLinkHandler(prefixes: ['myapp://'], onLink: (url) => router.go(url));
```

A tapped notification opens the conversation it was about.

## Push

Notifications go through your app's own Firebase project. Upload its service
account key in the deployment's App setup, then hand the plugin your FCM token
and the messages it sends. With `firebase_messaging`:

```dart
final messaging = FirebaseMessaging.instance;
final token = await messaging.getToken();
if (token != null) await UniversalChat.setPushToken(token);
messaging.onTokenRefresh.listen(UniversalChat.setPushToken);

// A reply while the app is open. Android shows it unless the chat is open.
FirebaseMessaging.onMessage.listen((message) {
  UniversalChat.handlePush(message.data, title: message.notification?.title, body: message.notification?.body);
});

// A notification tapped while the app was in the background, or closed.
FirebaseMessaging.onMessageOpenedApp.listen((message) => UniversalChat.handleNotificationTap(message.data));
final initial = await messaging.getInitialMessage();
if (initial != null) await UniversalChat.handleNotificationTap(initial.data);
```

On iOS the system shows the notification. While the app is open,
`shouldPresentNotification(data)` says whether to show it: not when the chat
it is about is already on screen.

## Example

`example/` opens the chat, and signs a user in and out:

```
cd example
flutter run --dart-define=SERVER_URL=https://acme.universalchatbot.com --dart-define=DEPLOYMENT_ID=42 --dart-define=USER_TOKEN=...
```

Until the Android SDK is published, run `./gradlew publishToMavenLocal` in
`sdks/android` first; the example reads it from the local Maven repository.
