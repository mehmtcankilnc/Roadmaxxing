@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.roadmaxxing.auth

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import app.roadmaxxing.shared.account.AuthError
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCodeCanceledLogin
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dictionaryWithContentsOfFile
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Google sign-in on iOS without Swift or a Google SDK: the standard OAuth 2.0 authorization-code flow with PKCE in an
 * ASWebAuthenticationSession, using the iOS OAuth client from GoogleService-Info.plist (CLIENT_ID and
 * REVERSED_CLIENT_ID). The returned Google ID token is exchanged for a Firebase session by [FirebaseAuthBackend].
 *
 * ASWebAuthenticationSession handles the custom callback scheme itself, so no URL scheme has to be added to Info.plist.
 */
class IosGoogleCredentialProvider : GoogleCredentialProvider {

    // Strong reference: the session and its presentation provider must outlive the call.
    private var session: ASWebAuthenticationSession? = null
    private val presenter = PresentationProvider()

    override suspend fun requestCredential(): GoogleCredentialResult {
        val plist = bundledPlist() ?: return GoogleCredentialResult.Failure(AuthError.Unknown)
        val clientId = plist.string("CLIENT_ID") ?: return GoogleCredentialResult.Failure(AuthError.Unknown)
        val scheme = plist.string("REVERSED_CLIENT_ID") ?: return GoogleCredentialResult.Failure(AuthError.Unknown)
        val redirect = "$scheme:/oauth2redirect"

        val verifier = randomVerifier() ?: return GoogleCredentialResult.Failure(AuthError.Unknown)
        val challenge = sha256Base64Url(verifier)
        val state = randomVerifier()?.take(24) ?: return GoogleCredentialResult.Failure(AuthError.Unknown)

        val components = NSURLComponents(string = "https://accounts.google.com/o/oauth2/v2/auth")
        components.queryItems = listOf(
            NSURLQueryItem("client_id", clientId),
            NSURLQueryItem("redirect_uri", redirect),
            NSURLQueryItem("response_type", "code"),
            NSURLQueryItem("scope", "openid email profile"),
            NSURLQueryItem("code_challenge", challenge),
            NSURLQueryItem("code_challenge_method", "S256"),
            NSURLQueryItem("state", state),
        )
        val authUrl = components.URL ?: return GoogleCredentialResult.Failure(AuthError.Unknown)

        // ASWebAuthenticationSession must be started on the main thread.
        val callback = withContext(Dispatchers.Main) { authenticate(authUrl, scheme) }
        if (callback is Callback.Cancelled) return GoogleCredentialResult.Cancelled
        if (callback !is Callback.Url) return GoogleCredentialResult.Failure(AuthError.Unknown)

        val items = NSURLComponents(uRL = callback.url, resolvingAgainstBaseURL = false).queryItems
            ?.filterIsInstance<NSURLQueryItem>().orEmpty()
        val code = items.firstOrNull { it.name == "code" }?.value
        val returnedState = items.firstOrNull { it.name == "state" }?.value
        if (code == null || returnedState != state) return GoogleCredentialResult.Failure(AuthError.Unknown)

        val idToken = exchangeCode(clientId, code, verifier, redirect)
            ?: return GoogleCredentialResult.Failure(AuthError.Network)
        return GoogleCredentialResult.Token(idToken)
    }

    private sealed interface Callback {
        data class Url(val url: NSURL) : Callback
        data object Cancelled : Callback
        data object Failed : Callback
    }

    private suspend fun authenticate(url: NSURL, scheme: String): Callback =
        suspendCancellableCoroutine { cont ->
            val s = ASWebAuthenticationSession(uRL = url, callbackURLScheme = scheme) { callbackUrl, error ->
                session = null
                cont.resume(
                    when {
                        callbackUrl != null -> Callback.Url(callbackUrl)
                        error?.code == ASWebAuthenticationSessionErrorCodeCanceledLogin -> Callback.Cancelled
                        else -> Callback.Failed
                    },
                )
            }
            s.presentationContextProvider = presenter
            session = s
            if (!s.start()) {
                session = null
                cont.resume(Callback.Failed)
            }
            cont.invokeOnCancellation {
                s.cancel()
                session = null
            }
        }

    /** POSTs the authorization code to Google's token endpoint and returns the `id_token`, or null on failure. */
    private suspend fun exchangeCode(clientId: String, code: String, verifier: String, redirect: String): String? =
        suspendCancellableCoroutine { cont ->
            val request = NSMutableURLRequest(uRL = NSURL(string = "https://oauth2.googleapis.com/token"))
            request.setHTTPMethod("POST")
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField = "Content-Type")
            val body = listOf(
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to redirect,
            ).joinToString("&") { (k, v) -> "$k=${formEncode(v)}" }
            request.setHTTPBody(body.encodeToByteArray().toNSData())
            val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
                val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                val token = if (error == null && status in 200..299 && data != null) {
                    val json = NSJSONSerialization.JSONObjectWithData(data, 0u, null) as? Map<*, *>
                    json?.get("id_token") as? String
                } else {
                    null
                }
                cont.resume(token)
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }

    private class PresentationProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }
}

private fun bundledPlist(): Map<Any?, *>? {
    val path = NSBundle.mainBundle.pathForResource("GoogleService-Info", "plist") ?: return null
    return NSDictionary.dictionaryWithContentsOfFile(path)
}

private fun Map<Any?, *>.string(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

private fun formEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (byte >= 0 && c in UNRESERVED) {
            append(c)
        } else {
            append('%')
            append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF])
            append("0123456789ABCDEF"[byte.toInt() and 0xF])
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun base64Url(data: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(data)

/** 32 random bytes, base64url encoded (43 chars: a valid PKCE verifier). */
private fun randomVerifier(): String? {
    val bytes = ByteArray(32)
    val ok = bytes.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), pinned.addressOf(0)) == 0
    }
    if (!ok) return null
    return base64Url(bytes)
}

private fun sha256Base64Url(input: String): String {
    val data = input.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { src ->
        digest.usePinned { dst ->
            CC_SHA256(src.addressOf(0), data.size.convert(), dst.addressOf(0).reinterpret<UByteVar>())
        }
    }
    return base64Url(digest)
}

private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
