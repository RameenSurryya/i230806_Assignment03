package com.rameen.i230806.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The background sync (WorkManager): sends the pending_actions queue, oldest first.
 *
 *  - Only runs when there is internet (a WorkManager constraint), so it starts by itself as
 *    soon as the phone reconnects, even if the app was closed in the meantime.
 *  - Each action is sent with its UUID as X-Request-Id; the server ignores repeats, so a retry
 *    after a dropped connection never creates a duplicate.
 *  - Sent OK                -> removed from the queue; its pending row in SQLite is removed
 *                              (screens then reload and show the server's copy).
 *  - No connection          -> stop here, WorkManager tries again later (order is kept).
 *  - Refused by the server  -> removed (it would never succeed, e.g. an edit after 5 minutes),
 *                              so it doesn't block everything behind it.
 *  - Logged out (401)       -> stop; the queue waits for the next login.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        var sentSomething = false
        try {
            while (true) {
                val action = LocalDb.nextPending() ?: break
                val files = (0 until action.files.length()).map { action.files.getJSONObject(it) }
                val parts = files.mapNotNull { f ->
                    val file = File(f.getString("path"))
                    if (file.exists()) Api.FilePart(f.getString("field"), f.getString("name"), f.getString("mime"), file.readBytes())
                    else null
                }

                val result =
                    if (parts.isEmpty()) Api.postNow(action.path, action.body, action.uuid)
                    else Api.uploadNow(action.path, Outbox.fields(action.body), parts, action.uuid)

                when {
                    result.ok -> {
                        finish(action, files)
                        sentSomething = true
                    }
                    result.offline -> {
                        LocalDb.markPendingFailed(action.uuid, result.error)
                        return Result.retry()
                    }
                    result.loggedOut -> return Result.failure()
                    result.status >= 500 && action.attempts < MAX_SERVER_ERRORS -> {
                        LocalDb.markPendingFailed(action.uuid, result.error)
                        return Result.retry()
                    }
                    else -> {
                        Log.w(TAG, "Server refused ${action.type}: ${result.error}")
                        finish(action, files)
                        sentSomething = true
                    }
                }
            }
        } finally {
            if (sentSomething) Outbox.notifySynced()
        }
        return Result.success()
    }

    /** Removes the action, its copied files and the local "pending" row it created. */
    private fun finish(action: LocalDb.PendingAction, files: List<org.json.JSONObject>) {
        LocalDb.removePending(action.uuid)
        files.forEach { File(it.getString("path")).delete() }
        action.localId?.let { id ->
            for (table in listOf(LocalDb.MESSAGES, LocalDb.POSTS, LocalDb.COMMENTS)) {
                LocalDb.delete(table, "id = ?", arrayOf(id))
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "kinnect-sync"
        private const val MAX_SERVER_ERRORS = 5

        /** Starts (or queues after the current run) a sync that waits for internet. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: a sync already running finishes, then this one runs, so an action
            // queued at the very end of a run is never left behind.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}