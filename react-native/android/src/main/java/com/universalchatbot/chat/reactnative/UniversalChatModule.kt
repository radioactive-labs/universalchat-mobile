package com.universalchatbot.chat.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.universalchatbot.chat.UniversalChat
import com.universalchatbot.chat.core.ApiException

/** Passes the JavaScript API's calls through to the UniversalChat SDK. */
class UniversalChatModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    override fun getName() = NAME

    @ReactMethod
    fun configure(serverUrl: String, deploymentId: String, promise: Promise) = onMain(promise) {
        UniversalChat.configure(reactApplicationContext, serverUrl, deploymentId)
        promise.resolve(null)
    }

    @ReactMethod
    fun present(promise: Promise) = onMain(promise) {
        UniversalChat.present(reactApplicationContext.currentActivity ?: reactApplicationContext)
        promise.resolve(null)
    }

    @ReactMethod
    fun login(userToken: String, promise: Promise) = onMain(promise) {
        UniversalChat.login(userToken) { outcome ->
            outcome.fold(
                onSuccess = { promise.resolve(it) },
                onFailure = { error -> promise.reject((error as? ApiException)?.code ?: "login_failed", error.message, error) },
            )
        }
    }

    @ReactMethod
    fun logout(promise: Promise) = onMain(promise) {
        UniversalChat.logout()
        promise.resolve(null)
    }

    @ReactMethod
    fun setAttributes(attributes: ReadableMap, promise: Promise) = onMain(promise) {
        UniversalChat.setAttributes(AttributeValues.normalizeAll(attributes.toHashMap()))
        promise.resolve(null)
    }

    @ReactMethod
    fun setPushToken(token: String, promise: Promise) = onMain(promise) {
        UniversalChat.setPushToken(token)
        promise.resolve(null)
    }

    @ReactMethod
    fun isUniversalChatPush(data: ReadableMap, promise: Promise) = onMain(promise) {
        promise.resolve(UniversalChat.isUniversalChatPush(strings(data)))
    }

    @ReactMethod
    fun handlePush(data: ReadableMap, title: String?, body: String?, promise: Promise) = onMain(promise) {
        promise.resolve(UniversalChat.handlePush(reactApplicationContext, strings(data), title, body))
    }

    // Android shows its own notification from handlePush, so the system never needs to.
    @ReactMethod
    fun shouldPresentNotification(data: ReadableMap, promise: Promise) {
        promise.resolve(false)
    }

    @ReactMethod
    fun handleNotificationTap(data: ReadableMap, promise: Promise) = onMain(promise) {
        promise.resolve(UniversalChat.handleNotificationTap(reactApplicationContext.currentActivity ?: reactApplicationContext, strings(data)))
    }

    @ReactMethod
    fun setLinkPrefixes(prefixes: ReadableArray, promise: Promise) = onMain(promise) {
        val taken = (0 until prefixes.size()).mapNotNull(prefixes::getString)
        // The chat asks while the visitor's tap is still in hand, so the answer
        // is decided here and JavaScript hears about the link after.
        UniversalChat.linkHandler = if (taken.isEmpty()) null else { url ->
            taken.any(url::startsWith).also { ours ->
                if (ours) {
                    val event = Arguments.createMap().apply { putString("url", url) }
                    reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(LINK_EVENT, event)
                }
            }
        }
        promise.resolve(null)
    }

    // NativeEventEmitter asks for these; the events are sent whether or not anyone listens.
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Double) {}

    // React Native calls in on a background thread, but the chat lives on the
    // main one. A call made before configure throws, and is handed back to
    // JavaScript as a rejection rather than crashing the app.
    private fun onMain(promise: Promise, call: () -> Unit) {
        UiThreadUtil.runOnUiThread {
            try {
                call()
            } catch (error: Exception) {
                promise.reject("universal_chat_failed", error.message, error)
            }
        }
    }

    private fun strings(data: ReadableMap): Map<String, String> = data.toHashMap().mapValues { it.value?.toString().orEmpty() }

    companion object {
        const val NAME = "UniversalChat"
        const val LINK_EVENT = "UniversalChatLink"
    }
}
