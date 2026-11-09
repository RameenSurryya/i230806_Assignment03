package com.rameen.i230806.ui.chat

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.util.zip.Deflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Makes the Agora token a phone needs to join a call channel, when the Agora project has an
 * App Certificate (Agora's "secured mode").
 *
 * A real app asks its own server for tokens. Kinnect has no server (free plan), so the app
 * builds the token itself from the App Certificate, exactly like Agora's official
 * RtcTokenBuilder2 (token version "007"):
 *
 *   content   = appId, issue time, expiry, salt, and the "RTC" service with its privileges
 *               (join the channel, publish audio / video / data) for this channel name
 *   signature = HMAC-SHA256 of the content, keyed with the certificate (mixed with time + salt)
 *   token     = "007" + base64( zlib( signature + content ) )
 *
 * The certificate is read from assets/agora-certificate.txt, which is in .gitignore so it
 * never reaches GitHub. If that file is missing, null is returned and the call joins without
 * a token (which works for projects in testing mode).
 */
object AgoraToken {

    private const val CERT_FILE = "agora-certificate.txt"
    private const val VERSION = "007"
    private const val VALID_SECONDS = 3600          // a token lasts one hour

    // Agora's numbers for the RTC service and its privileges.
    private const val SERVICE_RTC: Short = 1
    private const val JOIN_CHANNEL: Short = 1
    private const val PUBLISH_AUDIO: Short = 2
    private const val PUBLISH_VIDEO: Short = 3
    private const val PUBLISH_DATA: Short = 4

    /** A token for [channel] that lets any uid join and send audio and video, or null. */
    fun build(context: Context, appId: String, channel: String): String? {
        val certificate = readCertificate(context) ?: return null
        if (!isHex32(appId) || !isHex32(certificate)) return null

        val issuedAt = (System.currentTimeMillis() / 1000).toInt()
        val salt = Random.nextInt(1, 99_999_999)

        // The content: who, when, for how long, and what this token allows.
        val content = Packer()
            .string(appId)
            .int(issuedAt)
            .int(VALID_SECONDS)
            .int(salt)
            .short(1)                                // one service: RTC
            .short(SERVICE_RTC)
            .short(4)                                // four privileges, each valid for an hour
            .short(JOIN_CHANNEL).int(VALID_SECONDS)
            .short(PUBLISH_AUDIO).int(VALID_SECONDS)
            .short(PUBLISH_VIDEO).int(VALID_SECONDS)
            .short(PUBLISH_DATA).int(VALID_SECONDS)
            .string(channel)
            .string("")                              // uid "" (0) = any uid may use this token
            .bytes()

        // The signing key: the certificate mixed with the issue time, then with the salt.
        val step1 = hmac(Packer().int(issuedAt).bytes(), certificate.toByteArray())
        val signingKey = hmac(Packer().int(salt).bytes(), step1)
        val signature = hmac(signingKey, content)

        val whole = Packer().blob(signature).raw(content).bytes()
        return VERSION + Base64.encodeToString(zlib(whole), Base64.NO_WRAP)
    }

    /** assets/agora-certificate.txt, or null when it isn't there. */
    private fun readCertificate(context: Context): String? = try {
        context.assets.open(CERT_FILE).bufferedReader().use { it.readText() }.trim().ifEmpty { null }
    } catch (e: FileNotFoundException) {
        null
    }

    private fun isHex32(text: String) = text.length == 32 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** zlib compression (what Java's Deflater does by default). */
    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        return out.toByteArray()
    }

    /** Writes numbers little-endian and texts / byte arrays with a 2-byte length first, like Agora. */
    private class Packer {
        private val out = ByteArrayOutputStream()

        fun short(value: Short) = apply {
            out.write(value.toInt() and 0xFF)
            out.write((value.toInt() shr 8) and 0xFF)
        }

        fun int(value: Int) = apply {
            for (shift in 0 until 32 step 8) out.write((value shr shift) and 0xFF)
        }

        fun blob(value: ByteArray) = apply {
            short(value.size.toShort())
            out.write(value)
        }

        fun string(value: String) = blob(value.toByteArray())

        fun raw(value: ByteArray) = apply { out.write(value) }

        fun bytes(): ByteArray = out.toByteArray()
    }
}