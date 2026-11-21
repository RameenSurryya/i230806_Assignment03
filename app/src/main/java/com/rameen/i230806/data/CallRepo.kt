package com.rameen.i230806.data

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

/**
 * Ringing, answering and ending calls. The audio and video themselves go through Agora
 * (AgoraCall); Firebase only tells the two phones what is happening:
 *
 *   calls/{calleeUid}       Call: the call ringing / going on for that person right now
 *   callLogs/{uid}/{callId} CallLog: each person's own call history
 *
 * Both phones watch calls/{calleeUid}:
 *   - status "ringing"  -> the callee's Incoming call screen is showing
 *   - status "accepted" -> both phones are in the Agora channel (channel = callId)
 *   - status "declined" -> the caller shows "Call declined" and removes the node
 *   - node removed      -> the call is over (hung up, cancelled or not answered)
 */
object CallRepo {

    /** A ringing / accepted call older than this was left behind by a crash: not "busy". */
    private const val STALE_MS = 2 * 60 * 1000L

    private val me: String get() = Db.uid ?: ""

    /**
     * Starts ringing [otherUid]: writes the call and sends them a push. [onStarted] gets the
     * call, or null when they are already in another call.
     */
    fun start(context: Context, otherUid: String, type: String, onStarted: (Call?) -> Unit) {
        val ref = Db.calls(otherUid)
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val current = snapshot.getValue(Call::class.java)
                val busy = current != null && Db.now() - current.timestamp < STALE_MS &&
                        (current.status == CallStatus.RINGING || current.status == CallStatus.ACCEPTED)
                if (busy) {
                    onStarted(null)
                    return
                }
                val callId = Db.root.child("calls").push().key ?: return
                val call = Call(
                    callId = callId,
                    callerId = me,
                    calleeId = otherUid,
                    channel = callId,          // both phones join the Agora channel with this name
                    type = type,
                    status = CallStatus.RINGING,
                    timestamp = Db.now(),
                )
                ref.setValue(call)
                // The ringing push is sent by the server from step 9 (calls/start.php).
                onStarted(call)
            }

            override fun onCancelled(error: DatabaseError) = onStarted(null)
        })
    }

    /** Callee answers: the caller's screen sees "accepted". */
    fun accept(call: Call) {
        Db.calls(call.calleeId).child("status").setValue(CallStatus.ACCEPTED)
    }

    /** Callee declines: the caller's screen sees "declined" and cleans up. */
    fun decline(call: Call) {
        Db.calls(call.calleeId).child("status").setValue(CallStatus.DECLINED)
    }

    /** Either side ends (or the caller cancels): removing the node ends it on both phones. */
    fun end(call: Call) {
        Db.calls(call.calleeId).removeValue()
    }

    /** Adds the call to my own history. */
    fun log(call: Call, status: String, startedAt: Long, durationSec: Long) {
        if (me.isEmpty()) return
        val outgoing = call.callerId == me
        Db.callLogs(me).child(call.callId).setValue(
            CallLog(
                callId = call.callId,
                otherUid = if (outgoing) call.calleeId else call.callerId,
                type = call.type,
                outgoing = outgoing,
                status = status,
                startedAt = startedAt,
                durationSec = durationSec,
            )
        )
    }
}