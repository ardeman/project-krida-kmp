package com.ardeman.krida.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.protocol.Krida
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The signed-in Krida account ("Accounts" in docs/protocol.md): Google sign-in through the
 * browser and the relay, the session token, sign out and account deletion. Same endpoints
 * and stored fields as iOS (`Account/Account.swift`).
 */
object Account {
    data class User(val id: String, val name: String, val provider: String)

    private const val PREFS = "account"
    private const val KEY_TOKEN = "account.token"
    private const val KEY_USER = "account.user"
    private const val KEY_VERIFIER = "account.verifier"
    private val API = Krida.RELAY_URL.replaceFirst("ws", "http")
    private val JSON_TYPE = "application/json".toMediaType()

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { OkHttpClient() }
    private var token: String? = null

    /** The signed-in player; null when signed out. */
    var user by mutableStateOf<User?>(null)
        private set

    /** A request is running: the screen shows the loading indicator and disables its buttons. */
    var busy by mutableStateOf(false)
        private set

    /** The last sign-in, sign-out or deletion failed; cleared by the next attempt. */
    var failed by mutableStateOf(false)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        token = prefs().getString(KEY_TOKEN, null)
        user = token?.let { decodeUser(prefs().getString(KEY_USER, null)) }
        // A session ended elsewhere (deleted account) signs this phone out; offline keeps it.
        val saved = token ?: return
        send(Request.Builder().url("$API/v1/me").header("Authorization", "Bearer $saved").build()) { code, body ->
            if (token != saved) return@send
            if (code == 401) clear() else if (code == 200) body?.get("user")?.let { store(saved, it.jsonObject) }
        }
    }

    /** Opens Google's sign-in page in the browser; [handleLink] finishes it. */
    fun signInWithGoogle(context: Context) {
        failed = false
        val verifier = randomToken()
        prefs().edit().putString(KEY_VERIFIER, verifier).apply()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$API/v1/auth/google/start?challenge=$challenge")))
        } catch (e: ActivityNotFoundException) {
            failed = true
        }
    }

    /** Finishes a sign-in from "krida://auth?code=…". True when [link] was an account link. */
    fun handleLink(link: Uri?): Boolean {
        if (link?.scheme != "krida" || link.host != "auth") return false
        val code = link.getQueryParameter("code")
        val verifier = prefs().getString(KEY_VERIFIER, null)
        prefs().edit().remove(KEY_VERIFIER).apply()
        if (code == null || verifier == null) {
            // Backing out of Google's page is not an error worth a message.
            failed = link.getQueryParameter("error") != "cancelled"
            return true
        }
        failed = false
        busy = true
        val payload = buildJsonObject { put("code", code); put("verifier", verifier) }.toString()
        send(Request.Builder().url("$API/v1/auth/exchange").post(payload.toRequestBody(JSON_TYPE)).build()) { status, body ->
            busy = false
            val session = body?.get("token")?.jsonPrimitive?.content
            val signedIn = body?.get("user")?.jsonObject
            if (status == 200 && session != null && signedIn != null) store(session, signedIn) else failed = true
        }
        return true
    }

    fun signOut() {
        val saved = token ?: return
        failed = false
        // Best effort: the phone forgets the session even when the relay can't be reached.
        send(Request.Builder().url("$API/v1/auth/signout").header("Authorization", "Bearer $saved").post("".toRequestBody(null)).build()) { _, _ -> }
        clear()
    }

    /** Deletes the account on the relay, then signs out here. */
    fun delete() {
        val saved = token ?: return
        failed = false
        busy = true
        send(Request.Builder().url("$API/v1/me").header("Authorization", "Bearer $saved").delete().build()) { status, _ ->
            busy = false
            // 401: the account is already gone.
            if (status == 204 || status == 401) clear() else failed = true
        }
    }

    private fun store(session: String, signedIn: JsonObject) {
        val decoded = decodeUser(signedIn.toString()) ?: return
        token = session
        user = decoded
        prefs().edit().putString(KEY_TOKEN, session).putString(KEY_USER, signedIn.toString()).apply()
    }

    private fun clear() {
        token = null
        user = null
        prefs().edit().remove(KEY_TOKEN).remove(KEY_USER).apply()
    }

    private fun decodeUser(text: String?): User? = try {
        val o = Json.parseToJsonElement(text ?: "").jsonObject
        User(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content, o.getValue("provider").jsonPrimitive.content)
    } catch (e: Exception) {
        null
    }

    /** Runs [request]; [done] gets the status (0 when the relay wasn't reached) and JSON body on the main thread. */
    private fun send(request: Request, done: (Int, JsonObject?) -> Unit) {
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { done(0, null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.use { r ->
                    try { Json.parseToJsonElement(r.body?.string() ?: "").jsonObject } catch (e: Exception) { null }
                }
                main.post { done(response.code, body) }
            }
        })
    }

    private fun randomToken() = base64Url(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun base64Url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
