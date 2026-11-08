package com.rameen.i230806.push

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.rameen.i230806.data.Db
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors

/**
 * Sends push notifications with Firebase Cloud Messaging (FCM), without Cloud Functions
 * (those need the paid Blaze plan).
 *
 * How it works:
 *  1. Every phone saves its FCM token (its "address" for pushes) in users/{uid}/fcmToken
 *     (registerThisPhone, called after login).
 *  2. To notify someone, we read their token and call the FCM HTTP v1 API:
 *       POST https://fcm.googleapis.com/v1/projects/{project}/messages:send
 *  3. That API needs a Google access token. We make one from a service account key
 *     (assets/service-account.json, downloaded from the Firebase console):
 *       - build a JWT (header.claims), sign it with the key's private RSA key (SHA256withRSA),
 *       - exchange it at https://oauth2.googleapis.com/token for an access token (valid 1 hour).
 *  4. The other phone's KinnectMessagingService receives the data and shows the notification.
 *
 * Note: keeping a service account key inside the app is fine for a class project, but a real
 * app would do step 3 on a server. The key is in .gitignore, so it is never pushed to GitHub.
 * If the file is missing, pushes are simply skipped (the app still works).
 */
object PushSender {

    private const val TAG = "PushSender"
    private const val KEY_FILE = "service-account.json"
    private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /** Network calls must not run on the main thread. */
    private val background = Executors.newSingleThreadExecutor()

    private var account: JSONObject? = null
    private var accessToken: String? = null
    private var accessTokenExpiry = 0L

    // ---------------------------------------------------------------------------------------
    // This phone's token
    // ---------------------------------------------------------------------------------------

    /** Turns FCM on for this phone and saves its token under the logged-in user. */
    fun registerThisPhone() {
        val me = Db.uid ?: return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        messaging.token.addOnSuccessListener { token -> Db.user(me).child("fcmToken").setValue(token) }
    }

    // ---------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------

    /** A new chat message: "Aisha Khan: See you at 5". */
    fun sendMessage(context: Context, toUid: String, title: String, body: String, chatId: String) {
        send(
            context, toUid,
            mapOf("type" to TYPE_MESSAGE, "title" to title, "body" to body, "chatId" to chatId),
        )
    }

    /**
     * Sends [data] to [toUid]'s phone. Only "data" is sent (no "notification" part), so
     * KinnectMessagingService always builds the notification itself, even when the app is closed.
     */
    fun send(context: Context, toUid: String, data: Map<String, String>) {
        val me = Db.uid ?: return
        val appContext = context.applicationContext
        Db.user(toUid).child("fcmToken").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val token = snapshot.getValue(String::class.java).orEmpty()
                if (token.isEmpty()) return       // that person never opened the app on a phone
                val full = data + mapOf("fromUid" to me, "toUid" to toUid)
                background.execute { sendNow(appContext, token, full) }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /** Runs on the background thread. */
    private fun sendNow(context: Context, token: String, data: Map<String, String>) {
        try {
            val key = serviceAccount(context) ?: return
            val access = accessToken(key) ?: return
            val message = JSONObject()
                .put("token", token)
                .put("data", JSONObject(data))
                .put("android", JSONObject().put("priority", "HIGH"))
            val url = "https://fcm.googleapis.com/v1/projects/${key.getString("project_id")}/messages:send"
            post(url, "application/json", JSONObject().put("message", message).toString(), access)
        } catch (e: Exception) {
            Log.w(TAG, "Push not sent: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Access token from the service account key
    // ---------------------------------------------------------------------------------------

    /** assets/service-account.json, read once. Null (and a log line) if it isn't there. */
    private fun serviceAccount(context: Context): JSONObject? {
        account?.let { return it }
        return try {
            val text = context.assets.open(KEY_FILE).bufferedReader().use { it.readText() }
            JSONObject(text).also { account = it }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "No $KEY_FILE in assets: push notifications are off")
            null
        }
    }

    /** A Google access token, reused until 1 minute before it expires. */
    private fun accessToken(key: JSONObject): String? {
        val now = System.currentTimeMillis()
        accessToken?.let { if (now < accessTokenExpiry - 60_000) return it }

        // 1. The JWT: base64url(header) . base64url(claims) . base64url(signature)
        val seconds = now / 1000
        val header = base64Url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = base64Url(
            JSONObject()
                .put("iss", key.getString("client_email"))
                .put("scope", SCOPE)
                .put("aud", TOKEN_URL)
                .put("iat", seconds)
                .put("exp", seconds + 3600)
                .toString().toByteArray()
        )
        val unsigned = "$header.$claims"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey(key.getString("private_key")))
        signer.update(unsigned.toByteArray())
        val jwt = "$unsigned.${base64Url(signer.sign())}"

        // 2. Exchange it for an access token.
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
                "&assertion=" + URLEncoder.encode(jwt, "UTF-8")
        val answer = JSONObject(post(TOKEN_URL, "application/x-www-form-urlencoded", form, null))
        accessToken = answer.getString("access_token")
        accessTokenExpiry = now + answer.optLong("expires_in", 3600) * 1000
        return accessToken
    }

    /** The "-----BEGIN PRIVATE KEY----- ... -----END PRIVATE KEY-----" text as a Java key. */
    private fun privateKey(pem: String) = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), ""),
                Base64.DEFAULT,
            )
        )
    )

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** A simple HTTP POST; returns the response text, or throws with the error text. */
    private fun post(url: String, contentType: String, body: String, bearer: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", contentType)
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val ok = connection.responseCode in 200..299
            val stream = if (ok) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw IllegalStateException("HTTP ${connection.responseCode}: $text")
            return text
        } finally {
            connection.disconnect()
        }
    }

    // Kinds of push, read by KinnectMessagingService.
    const val TYPE_MESSAGE = "message"
    const val TYPE_CALL = "call"
}