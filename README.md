# UniversalChatbot mobile SDKs

Native chat clients for a deployment on the **Mobile app** channel:

- `android/`: Kotlin library with a Jetpack Compose chat screen.
- `ios/`: Swift package with a SwiftUI chat screen (manifest at the root).
- `flutter/universal_chat/`: Flutter plugin over the two.
- `react-native/`: React Native module over the two, with an Expo config plugin.

The wrappers draw nothing of their own; every screen comes from the native
SDKs, so a change there reaches Flutter and React Native apps as is.

## Releasing

This directory is published as its own public repository,
`github.com/radioactive-labs/universalchat-mobile`, which is what every
package's source link points at. `Package.swift` sits at its root because
Swift Package Manager reads a package from there; the iOS podspecs sit beside
it for the same reason.

Every package carries the same version, and one bare tag (`0.9.0`) in the
public repository marks a release for all of them. Bump it in
`android/universalchat/build.gradle.kts`, both root `*.podspec` files,
`flutter/universal_chat/` (`pubspec.yaml`, `CHANGELOG.md`, the podspec,
`ios/universal_chat/Package.swift`, `android/build.gradle.kts`) and
`react-native/` (`package.json`, `android/build.gradle`, the podspec's
`minimumVersion`).

1. **Public repository.** Copy this directory to the public repository's
   root, commit and tag the version. Xcode installs the iOS SDK from the tag.
2. **Android** to Maven Central as `com.universalchatbot:universalchat`. Put
   `mavenCentralUsername`, `mavenCentralPassword` (a Central Portal token),
   `signingInMemoryKey`, `signingInMemoryKeyId` and
   `signingInMemoryKeyPassword` in `~/.gradle/gradle.properties`, then with
   JDK 17 run `./gradlew publishAndReleaseToMavenCentral` in `android/`. It
   takes a few minutes to appear. `./gradlew publishToMavenLocal` puts it in
   the local repository for trying the wrappers first, and needs no key.
3. **iOS pods** for CocoaPods users, core first, from this directory:
   `pod trunk push UniversalChatCore.podspec`, then
   `pod trunk push UniversalChat.podspec --synchronous`.
4. **Flutter** to pub.dev: `flutter pub publish` in `flutter/universal_chat/`,
   under the `universalchatbot.com` verified publisher.
5. **React Native** to npm: `npm publish` in `react-native/`, under the
   `@universalchatbot` organisation.

The native SDKs speak the protocol below, the same one the website widget uses. A mobile
deployment has no embed page; everything else is shared with webchat.

## Protocol

`{server}` is the workspace's portal origin and `{id}` the deployment id,
both shown in the deployment's App setup panel. Every request is JSON. The
endpoints are public; a visitor proves who they are with the signed `visitor`
token, sent as a body field on POSTs and a query parameter on GETs.

| Call | Purpose |
|---|---|
| `GET {server}/deployments/{id}/config` | `chatWidget` settings (title, colours, `collectUserInfo`), `entrypoints` and the resolved `csat` settings. 404 means the deployment is not answering. |
| `POST /deployments/{id}/visitor` | Anonymous visitor: `{visitor}`. With `{user_token}`: the signed-in user's visitor, `{visitor, user_id}`, or 401 `invalid_user_token`. |
| `POST /deployments/{id}/media` | Multipart `file` and `visitor`. Stores a file the visitor is about to send: `{ref, mime_type, media_type}`. 25 MB at most; images, PDF, audio, MP4/3GP video and plain text. |
| `POST /deployments/{id}/processor` | One turn: `{input, visitor, user_info?: {name, email, phone}, attributes?: {...}, media_url?, media_type?, mime_type?}`. An empty input starts the flow. `media_url` is the `ref` an upload returned. |
| `GET /deployments/{id}/history?visitor=&before_id=&conversation_id=` | The visitor's latest conversation, or with `conversation_id` an earlier one of theirs, its messages carrying any `attachments` as `{url, filename, kind, content_type}`. For the latest, without `before_id`, it also returns `cable_token`, `csat_offered`, `signed_in` and `past_conversations` (whether a signed-in visitor has an earlier conversation to offer). |
| `GET /deployments/{id}/conversations?visitor=&before_id=` | A signed-in visitor's conversations, newest first, 20 a page: `{conversations: [{id, ended, started_at, last_message: {role, text, author_name, has_attachment, sent_at}}], has_more}`. Page on with the last id as `before_id`. 403 `signed_in_only` for an anonymous visitor. |
| `POST /deployments/{id}/csat` | `{visitor, conversation_id, score}` with a score from 1 to 5. |
| `POST /deployments/{id}/read` | `{visitor, conversation_id}` while the chat screen is showing. Marks the replies sent so far as read in the agent inbox; changes nothing the visitor sees. Answers 204. The SDKs send it at most every three seconds. |
| `POST /deployments/{id}/devices` | Registers a push token: `{visitor, token, os: "android" or "ios"}`. |
| `DELETE /deployments/{id}/devices` | Drops it on sign-out: `{visitor, token}`. |

A turn answers with one of:

```json
{"type": "prompt", "message": "Which order?", "choices": {"a": "Order 1001"}, "media": {"url": "...", "type": "image", "caption": "..."}}
{"type": "terminal", "message": "Thanks, bye."}
{"type": "skip"}
```

`skip` means a person has taken over and the bot stays quiet; their reply
arrives on the cable. To pick a choice, send its label as the input. A
response carrying an `X-Webchat-Visitor` header means the presented visitor
was refused: keep the token in the header from then on. For a signed-in user
it means their session lapsed, so exchange the user token again.

The visitor token is the visitor to anything holding it, so the SDKs keep it
where it can't be read off the device or restored elsewhere: the Keychain on
iOS (this device only) and preferences sealed with an Android Keystore key.
A restored backup starts as a new anonymous visitor.

### Live updates

ActionCable at `wss://{server-host}/cable`. Native sockets must send an
`Origin` header equal to `{server}`. Subscribe with:

```json
{"command": "subscribe", "identifier": "{\"channel\":\"UniversalInbox::WebchatChannel\",\"cable_token\":\"...\"}"}
```

The token comes from history and lasts an hour. When a subscription is
rejected, fetch history again for a fresh one. Events:

- `agent_message`: `{platform_message_id, role, body, author_name, sent_at}`
- `conversation_closed`: `{conversation_id, csat_offered}`

### Signed-in users

The customer's server signs an HS256 JWT with the deployment's identity
secret: `sub` (their user id, required), `exp`, and optionally `name`,
`email` and `phone`, which agents see as verified, and `attributes`, custom
attributes a flow can trust. The app hands it to the
SDK's `login`, which exchanges it at `/visitor`. The visitor token it gets
back holds only on that deployment, lapses with the JWT's `exp`, and keys the
conversation on the user, so it follows them across devices.

### Attributes

Flat scalars under keys of letters, digits, `_`, `-` and `.`; at most 25,
strings cut at 255 characters. Signed ones ride in the JWT's `attributes`
claim. Ones the app sets go in each turn's `attributes`, build up over the
conversation, and are cleared by sending `null`. A signed value wins over an
app-set one of the same name. Flows read them as `{{@visitor.attributes.x}}`,
or `{{@visitor.verified_attributes.x}}` for signed ones only.

### Push

When an agent replies, every device the visitor registered gets an FCM
message through the customer's own Firebase project. Its `data` carries
`ucb: "1"`, `ucb_deployment_id` and `ucb_conversation_id`. A tap opens that
conversation: the chat while it is still running, and once it has ended,
the transcript from a signed-in visitor's past conversations.
