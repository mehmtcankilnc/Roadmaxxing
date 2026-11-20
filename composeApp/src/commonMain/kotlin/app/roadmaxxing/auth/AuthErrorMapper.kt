package app.roadmaxxing.auth

import app.roadmaxxing.shared.account.AuthError

/**
 * Maps a Firebase failure to an [AuthError]. Works on the exception's class name and message so the mapping is
 * identical on Android and iOS (GitLive surfaces different exception subclasses per platform) and is unit-testable
 * without Firebase classes.
 */
internal fun mapAuthFailure(throwable: Throwable): AuthError =
    mapAuthFailure(throwable::class.simpleName.orEmpty(), throwable.message.orEmpty())

internal fun mapAuthFailure(typeName: String, message: String): AuthError {
    val text = "$typeName $message".uppercase()
    return when {
        "RECENTLOGIN" in text || "REQUIRES_RECENT_LOGIN" in text || "REQUIRES RECENT" in text ->
            AuthError.RequiresRecentLogin
        "WEAKPASSWORD" in text || "WEAK_PASSWORD" in text || "WEAK PASSWORD" in text -> AuthError.WeakPassword
        "COLLISION" in text || "EMAIL_EXISTS" in text || "EMAIL_ALREADY_IN_USE" in text ||
            "ALREADY IN USE" in text -> AuthError.EmailInUse
        "TOO_MANY" in text || "TOO MANY" in text || "BLOCKED ALL REQUESTS" in text -> AuthError.TooManyRequests
        "NETWORK" in text || "UNREACHABLE" in text || "TIMEOUT" in text || "OFFLINE" in text -> AuthError.Network
        "INVALID_EMAIL" in text || "BADLY FORMATTED" in text || "INVALID EMAIL" in text -> AuthError.InvalidEmail
        "INVALIDUSER" in text || "USER_NOT_FOUND" in text || "NO USER RECORD" in text ||
            "USER_DISABLED" in text -> AuthError.UserNotFound
        "INVALIDCREDENTIALS" in text || "INVALID_CREDENTIAL" in text || "WRONG_PASSWORD" in text ||
            "INVALID_LOGIN_CREDENTIALS" in text || "PASSWORD IS INVALID" in text ||
            "SUPPLIED AUTH CREDENTIAL" in text -> AuthError.InvalidCredentials
        else -> AuthError.Unknown
    }
}
