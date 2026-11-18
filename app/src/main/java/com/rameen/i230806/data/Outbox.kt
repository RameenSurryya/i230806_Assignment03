package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Every user action that changes data goes through Outbox.send() (send a message, create a
 * post, react, comment, friend request, edit / delete a message ...).
 *
 *  - Online and nothing waiting in the queue: sent straight away (with its UUID).
 *  - Offline, or older actions still waiting: saved in pending_actions and sent later by the
 *    SyncWorker, in order. Picked files are copied into the app's own folder first, so they
 *    still exist when the worker sends them.
 *  - If a direct send fails because the connection dropped, it is queued with the SAME UUID:
 *    if the server had in fact received it, it ignores the repeat (no duplicates).
 *
 * [onSent] gets the server's answer, or null when the action was queued (show it as pending).
 */
object Outbox {

    /** Changes after the sync worker has sent queued actions: screens observe it and reload. */
    private val _synced = MutableLiveData(0L)
    val synced: LiveData<Long> get() = _synced

    fun newUuid(): String = UUID.randomUUID().toString()

    /** The id a pending item uses in SQLite until the server's copy replaces it. */
    fun localId(uuid: String): String = "local-$uuid"

    fun send(
        context: Context,
        type: String,
        path: String,
        body: JSONObject,
        files: List<Pair<String, Uri>> = emptyList(),   // (form field, file) e.g. ("images[]", uri)
        uuid: String = newUuid(),
        localId: String? = null,
        onSent: (Api.Result?) -> Unit = {},
    ) {
        val app = context.applicationContext
        val parts = files.mapNotNull { (field, uri) -> Api.filePart(app, field, uri) }

        // Keep the order: if anything is still waiting, this action waits behind it.
        if (!NetworkMonitor.isOnline || LocalDb.pendingCount() > 0) {
            queue(app, uuid, type, path, body, parts, localId)
            onSent(null)
            return
        }

        val whenDone: (Api.Result) -> Unit = { result ->
            if (result.offline) {
                queue(app, uuid, type, path, body, parts, localId)
                onSent(null)
            } else {
                onSent(result)
            }
        }
        if (parts.isEmpty()) Api.post(path, body, uuid, whenDone)
        else Api.upload(path, fields(body), parts, uuid, whenDone)
    }

    /** Saves the action in pending_actions (files copied to filesDir/outbox) and wakes the worker. */
    private fun queue(
        context: Context,
        uuid: String,
        type: String,
        path: String,
        body: JSONObject,
        parts: List<Api.FilePart>,
        localId: String?,
    ) {
        val folder = File(context.filesDir, "outbox").apply { mkdirs() }
        val files = JSONArray()
        parts.forEachIndexed { i, part ->
            val copy = File(folder, "${uuid}_$i")
            copy.writeBytes(part.bytes)
            files.put(
                JSONObject()
                    .put("field", part.field)
                    .put("name", part.fileName)
                    .put("mime", part.mimeType)
                    .put("path", copy.absolutePath)
            )
        }
        LocalDb.enqueue(uuid, type, path, body, files, localId)
        SyncWorker.schedule(context)
    }

    /** JSON body -> form fields for a multipart upload. */
    fun fields(body: JSONObject): Map<String, String> {
        val map = HashMap<String, String>()
        for (key in body.keys()) map[key] = body.get(key).toString()
        return map
    }

    /** Called by the worker (background thread) after it has sent queued actions. */
    internal fun notifySynced() {
        _synced.postValue(System.currentTimeMillis())
    }
}