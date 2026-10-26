export type AttributeValue = string | number | boolean | null

export interface UniversalChatApi {
  /** Call once at launch. Both values are in the deployment's App setup panel. */
  configure(options: { serverUrl: string; deploymentId: string | number }): Promise<void>
  /** Opens the chat full screen. */
  present(): Promise<void>
  /** Signs in the user a token from your server names. Resolves with their id. */
  login(userToken: string): Promise<string>
  /** Signs the user out, back to an anonymous conversation. */
  logout(): Promise<void>
  /** Custom attributes about the visitor. Set a key to null to clear it. */
  setAttributes(attributes: Record<string, AttributeValue>): Promise<void>
  /** Pass the FCM registration token at launch and whenever it changes. */
  setPushToken(token: string): Promise<void>
  /** Whether a push message's data is one of ours. */
  isUniversalChatPush(data: Record<string, unknown>): Promise<boolean>
  /** A message that arrived while the app was open. Resolves false when it is not ours. */
  handlePush(data: Record<string, unknown>, notification?: { title?: string | null; body?: string | null }): Promise<boolean>
  /** iOS: whether a notification arriving while the app is open should show. */
  shouldPresentNotification(data: Record<string, unknown>): Promise<boolean>
  /**
   * Lets your app take links tapped in the chat. A link starting with any of
   * the prefixes goes to onLink and the chat closes; any other opens as usual.
   * Call with no prefixes to stop.
   */
  setLinkHandler(prefixes?: string[], onLink?: ((url: string) => void) | null): Promise<void>
  /** Opens the chat on the conversation one of our notifications was about. */
  handleNotificationTap(data: Record<string, unknown>): Promise<boolean>
}

declare const UniversalChat: UniversalChatApi
export { UniversalChat }
export default UniversalChat
