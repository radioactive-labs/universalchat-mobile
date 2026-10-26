package com.universalchatbot.chat.core

/**
 * Where the SDK keeps who the visitor is between launches. A signed-in user's
 * visitor is kept apart from the anonymous one, so signing out falls back to
 * the anonymous conversation rather than ending up in nobody's.
 */
interface IdentityStore {
    var anonymousVisitor: String?
    var signedInUserId: String?
    var signedInVisitor: String?
    var pushToken: String?
    /** The visitor the push token was last registered under, so it can be moved or dropped. */
    var pushVisitor: String?
    var userInfoCollected: Boolean
    /** The visitor's own light or dark choice from the header toggle, overriding the deployment's. */
    var themeOverride: String?
}

class InMemoryIdentityStore : IdentityStore {
    override var anonymousVisitor: String? = null
    override var signedInUserId: String? = null
    override var signedInVisitor: String? = null
    override var pushToken: String? = null
    override var pushVisitor: String? = null
    override var userInfoCollected: Boolean = false
    override var themeOverride: String? = null
}
