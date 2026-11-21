package com.rameen.i230806.data

import android.content.Context
import org.json.JSONObject

/**
 * Ringing, answering and ending calls through our server (Assignment 3: the calls/ PHP files,
 * table calls). The audio and video themselves go through Agora (AgoraCall).
 *
 *   start   caller: calls/start.php -> the server saves the call and sends the callee a
 *           "call" push, so their phone rings even when the app is closed
 *   status  both phones ask calls/status.php every 1.5 s while their call screen shows
 *   answer  callee: ringing -> accepted        decline  callee: ringing -> declined
 *   end     either side: ringing -> missed, accepted -> ended
 *
 * The Agora token for the channel comes from the server (start / answer), made with the App
 * Certificate that stays on the server. The calls table is also the call log (calls/log.php).
 */
object CallRepo {

    /** A call and the Agora token to join its channel (null when the server has no certificate). */
    class Joined(val call: Call, val token: String?)

    /** A call from the server's JSON. */
    fun callOf(j: JSONObject) = Call(
        callId = Json.str(j, "id"),
        callerId = j.optLong("caller_id").toString(),
        calleeId = j.optLong("callee_id").toString(),
        channel = Json.str(j, "channel"),
        type = Json.str(j, "type").ifEmpty { CallType.VOICE },
        status = Json.str(j, "status"),
        timestamp = j.optLong("created_at"),
    )

    private fun joinedOf(result: Api.Result): Joined? {
        val json = result.json ?: return null
        val call = json.optJSONObject("call") ?: return null
        return Joined(callOf(call), if (json.isNull("token")) null else Json.str(json, "token").ifEmpty { null })
    }

    /**
     * Starts ringing [otherUid]. [onDone] gets the call and token, or null with the reason:
     * "busy" when they're already in a call, or the server / network error.
     */
    fun start(otherUid: String, type: String, onDone: (Joined?, String?) -> Unit) {
        val body = JSONObject().put("to", otherUid.toLong()).put("type", type)
        Api.post("calls/start.php", body) { result ->
            if (result.ok) onDone(joinedOf(result), null)
            else onDone(null, if (result.status == 409) BUSY else result.error)
        }
    }

    /** Callee answers. [onDone] gets the call and token, or null if it already ended. */
    fun answer(callId: String, onDone: (Joined?) -> Unit) {
        Api.post("calls/answer.php", JSONObject().put("id", callId.toLong())) { result ->
            onDone(if (result.ok) joinedOf(result) else null)
        }
    }

    /** The call's current state, or null when it couldn't be read (offline). */
    fun status(callId: String, onDone: (Call?) -> Unit) {
        Api.get("calls/status.php", mapOf("id" to callId)) { result ->
            onDone(result.json?.optJSONObject("call")?.takeIf { result.ok }?.let { callOf(it) })
        }
    }

    fun decline(callId: String) {
        Api.post("calls/decline.php", JSONObject().put("id", callId.toLong())) { }
    }

    /** Hang up (or cancel ringing). Safe to call twice. */
    fun end(callId: String) {
        Api.post("calls/end.php", JSONObject().put("id", callId.toLong())) { }
    }

    const val BUSY = "busy"
}
