# @universalchatbot/react-native

The UniversalChatbot chat as a native screen in a React Native app, for a
deployment on the **Mobile app** channel. It wraps the Android and iOS SDKs,
so the chat looks and behaves exactly as it does in a native app: the
deployment's widget settings, signed-in users, visitor attributes, files,
past conversations and push.

React Native 0.75 or later, Android 7.0 (API 24) and iOS 16 or later.

## Install

```
npm install @universalchatbot/react-native
npx pod-install
```

With Expo, add the config plugin, which raises the app's iOS and Android
minimums to what the chat needs, then prebuild:

```json
{ "expo": { "plugins": ["@universalchatbot/react-native"] } }
```

## Use

```js
import UniversalChat from '@universalchatbot/react-native'

// Once, at launch. Both values are in the deployment's App setup panel.
await UniversalChat.configure({ serverUrl: 'SERVER_URL', deploymentId: 'DEPLOYMENT_ID' })

// From a button
await UniversalChat.present()

// Facts about the visitor, for flows and agents
await UniversalChat.setAttributes({ screen: 'orders', plan: 'pro' })
```

When a user signs in to your app, fetch a token for them from your server and
pass it on. Sign them out with your own sign-out:

```js
try {
  await UniversalChat.login(tokenFromYourServer)
} catch (error) {
  // error.code is invalid_user_token when the token does not verify
}

await UniversalChat.logout()
```

## Links into your app

A flow or an agent can send a link to a screen of your app. Name the links
your app takes by how they start; a tap on one closes the chat and calls the
handler, and any other link opens as usual:

```js
UniversalChat.setLinkHandler(['myapp://'], (url) => navigation.navigate(routeFor(url)))
```

A tapped notification opens the conversation it was about.

## Push

Notifications go through your app's own Firebase project. Upload its service
account key in the deployment's App setup, then hand the module your FCM token
and the messages it sends. With `@react-native-firebase/messaging`:

```js
import messaging from '@react-native-firebase/messaging'

UniversalChat.setPushToken(await messaging().getToken())
messaging().onTokenRefresh(UniversalChat.setPushToken)

// A reply while the app is open. Android shows it unless the chat is open.
messaging().onMessage((message) =>
  UniversalChat.handlePush(message.data, { title: message.notification?.title, body: message.notification?.body })
)

// A notification tapped while the app was in the background, or closed.
messaging().onNotificationOpenedApp((message) => UniversalChat.handleNotificationTap(message.data))
const initial = await messaging().getInitialNotification()
if (initial) UniversalChat.handleNotificationTap(initial.data)
```

On iOS the system shows the notification. While the app is open,
`shouldPresentNotification(data)` says whether to show it: not when the chat
it is about is already on screen.
