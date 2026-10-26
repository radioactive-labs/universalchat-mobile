const { NativeModules, NativeEventEmitter } = require('react-native')

const LINKING_ERROR =
  "@universalchatbot/react-native isn't linked. Rebuild the app after installing it " +
  '(npx pod-install on iOS, or npx expo prebuild with Expo).'

const Native = NativeModules.UniversalChat

function native() {
  if (!Native) throw new Error(LINKING_ERROR)
  return Native
}

let linkSubscription = null

// FCM data values are strings; a messaging library can hand them over typed.
function strings(data) {
  const out = {}
  for (const [key, value] of Object.entries(data || {})) out[key] = value == null ? '' : String(value)
  return out
}

/** The chat's calls, passed through to the Android and iOS SDKs. */
const UniversalChat = {
  /** Call once at launch. Both values are in the deployment's App setup panel. */
  configure({ serverUrl, deploymentId }) {
    return native().configure(serverUrl, String(deploymentId))
  },

  /** Opens the chat full screen. */
  present() {
    return native().present()
  },

  /**
   * Signs in the user a token from your server names. Resolves with their id;
   * rejects with code invalid_user_token when the token does not verify.
   */
  login(userToken) {
    return native().login(userToken)
  },

  /** Signs the user out, back to an anonymous conversation. */
  logout() {
    return native().logout()
  },

  /**
   * Custom attributes about the visitor: strings, numbers and booleans.
   * Merged with what was set before; set a key to null to clear it. These
   * come from the device, so put anything a flow must trust in the user
   * token's attributes claim instead.
   */
  setAttributes(attributes) {
    return native().setAttributes(attributes || {})
  },

  /** Pass the FCM registration token at launch and whenever it changes. */
  setPushToken(token) {
    return native().setPushToken(token)
  },

  /** Whether a push message's data is one of ours. */
  isUniversalChatPush(data) {
    return native().isUniversalChatPush(strings(data))
  },

  /**
   * A message that arrived while the app was open. On Android it shows the
   * reply as a notification that opens the chat, unless the chat is open; on
   * iOS the system shows it, so ask shouldPresentNotification. Resolves false
   * for a message that is not ours.
   */
  handlePush(data, { title = null, body = null } = {}) {
    return native().handlePush(strings(data), title, body)
  },

  /** iOS: whether a notification arriving while the app is open should show. */
  shouldPresentNotification(data) {
    return native().shouldPresentNotification(strings(data))
  },

  /**
   * Lets your app take links tapped in the chat, such as one a flow sends to
   * a screen of your app. A link starting with any of the prefixes goes to
   * onLink and the chat closes, so the visitor lands where it points; any
   * other opens as usual. Call with no prefixes to stop.
   */
  setLinkHandler(prefixes = [], onLink = null) {
    linkSubscription?.remove()
    linkSubscription = null
    const taking = prefixes.length > 0 && onLink
    if (taking) {
      linkSubscription = new NativeEventEmitter(native()).addListener('UniversalChatLink', (event) => onLink(event.url))
    }
    return native().setLinkPrefixes(taking ? prefixes : [])
  },

  /** Opens the chat on the conversation one of our notifications was about. */
  handleNotificationTap(data) {
    return native().handleNotificationTap(strings(data))
  }
}

module.exports = UniversalChat
module.exports.default = UniversalChat
module.exports.UniversalChat = UniversalChat
