package com.universalchatbot.chat.core

import android.content.Context

/**
 * The identity kept in the app's private preferences, one file per deployment,
 * so two deployments in one app never share a visitor. The visitor tokens are
 * sealed with [cipher], since each one is the visitor to anything holding it.
 */
class SharedPreferencesIdentityStore(
    context: Context,
    deploymentId: String,
    cipher: SecretCipher = KeystoreCipher(),
) : IdentityStore {
    private val prefs = context.applicationContext.getSharedPreferences("universalchat_$deploymentId", Context.MODE_PRIVATE)
    private val sealed = SealedValues(
        object : Values {
            override fun get(key: String) = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        },
        cipher,
    )

    override var anonymousVisitor by secret("anonymous_visitor")
    override var signedInUserId by string("signed_in_user_id")
    override var signedInVisitor by secret("signed_in_visitor")
    override var pushToken by string("push_token")
    override var pushVisitor by secret("push_visitor")
    override var themeOverride by string("theme_override")
    override var userInfoCollected: Boolean
        get() = prefs.getBoolean("user_info_collected", false)
        set(value) = prefs.edit().putBoolean("user_info_collected", value).apply()

    private fun string(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, null)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    }

    private fun secret(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = sealed.get(key)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) = sealed.put(key, value)
    }
}
