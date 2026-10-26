package com.universalchatbot.universal_chat

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.universalchatbot.chat.UniversalChat
import com.universalchatbot.chat.core.ApiException
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Passes the Dart API's calls through to the UniversalChat SDK. */
class UniversalChatPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private var activity: Activity? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "universal_chat")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "configure" -> {
                val server = call.argument<String>("serverUrl")
                val deployment = call.argument<String>("deploymentId")
                if (server == null || deployment == null) return result.error("invalid_arguments", "serverUrl and deploymentId are required", null)
                UniversalChat.configure(context, server, deployment)
                result.success(null)
            }
            "present" -> {
                UniversalChat.present(activity ?: context)
                result.success(null)
            }
            "login" -> UniversalChat.login(call.argument<String>("userToken").orEmpty()) { outcome ->
                // The SDK answers off the main thread; Flutter takes replies on it.
                main.post {
                    outcome.fold(
                        onSuccess = { result.success(it) },
                        onFailure = { error ->
                            val code = (error as? ApiException)?.code ?: "login_failed"
                            result.error(code, error.message, null)
                        },
                    )
                }
            }
            "logout" -> {
                UniversalChat.logout()
                result.success(null)
            }
            "setAttributes" -> {
                UniversalChat.setAttributes(call.argument<Map<String, Any?>>("attributes").orEmpty())
                result.success(null)
            }
            "setPushToken" -> {
                call.argument<String>("token")?.let(UniversalChat::setPushToken)
                result.success(null)
            }
            "isUniversalChatPush" -> result.success(UniversalChat.isUniversalChatPush(data(call)))
            "handlePush" ->
                result.success(UniversalChat.handlePush(context, data(call), call.argument("title"), call.argument("body")))
            // Android shows its own notification from handlePush, so the system never needs to.
            "shouldPresentNotification" -> result.success(false)
            "handleNotificationTap" -> result.success(UniversalChat.handleNotificationTap(activity ?: context, data(call)))
            "setLinkPrefixes" -> {
                val prefixes = call.argument<List<String>>("prefixes").orEmpty()
                // The chat asks while the visitor's tap is still in hand, so the
                // answer is decided here and Dart hears about the link after.
                UniversalChat.linkHandler = if (prefixes.isEmpty()) null else { url ->
                    prefixes.any(url::startsWith).also { ours -> if (ours) main.post { channel.invokeMethod("openLink", url) } }
                }
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun data(call: MethodCall): Map<String, String> = call.argument<Map<String, String>>("data").orEmpty()
}
