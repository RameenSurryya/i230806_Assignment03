package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Everything about stories (Home's stories row, Story editor, Story viewer, Your story).
 * Assignment 3: stories live in MySQL (stories, story_views) behind the stories/ PHP files, and the
 * photo or video is a file in uploads/stories on our server.
 *
 * 24-hour expiry, in both places:
 *  - Server: every query only returns stories whose expires_at is in the future, and
 *    stories/feed.php deletes expired rows and their files (purge_expired_stories).
 *  - Phone: SQLite keeps expires_at; expired rows are deleted before every read
 *    (LocalDb.deleteExpiredStories), so an old story never shows, even offline.
 * The server's clock decides: stories/feed.php sends its time and [now] uses the difference.
 *
 * Close-friends stories are filtered by the server (only people in the author's close friends
 * get them). Writes (share, seen, react, delete) go through the Outbox, so they also work
 * offline: a story shared offline shows in Your story at once and is uploaded later.
 */
object StoryRepo {

    const val LIFETIME_MS = 24 * 60 * 60 * 1000L

    private val me: String get() = Db.uid ?: ""

    /** Server clock minus phone clock, from the last stories/feed.php answer. */
    @Volatile private var serverOffset = 0L

    /** "Now" on the server's clock. */
    fun now(): Long = System.currentTimeMillis() + serverOffset

    private fun isLocal(id: String) = id.startsWith("local-")

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    /** Every story on the phone that hasn't expired, oldest first (the order they play in). */
    fun cachedAll(): List<Story> {
        LocalDb.deleteExpiredStories(now())
        return LocalDb.list(LocalDb.STORIES, null, null, "created_at").map { Json.story(it) }
    }

    /** One person's stories on the phone, oldest first. */
    fun cachedOf(author: String): List<Story> = cachedAll().filter { it.authorId == author }

    /**
     * Asks the server for every story I may see and replaces the SQLite copy (stories still
     * waiting to be uploaded stay). [onDone] gets false when the server couldn't be reached.
     */
    fun refreshAll(onDone: (Boolean) -> Unit) {
        Api.get("stories/feed.php") { result ->
            val json = result.json
            if (result.ok && json != null) {
                if (json.has("time")) serverOffset = json.optLong("time") - System.currentTimeMillis()
                val stories = Json.objects(json.optJSONArray("stories"))
                LocalDb.replaceAll(LocalDb.STORIES, "id NOT LIKE 'local-%'", null) {
                    stories.forEach { Json.saveStory(it) }
                }
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    /** [author]'s stories: fresh from the server when online, otherwise the SQLite copy. */
    fun loadVisible(author: String, onLoaded: (List<Story>) -> Unit) {
        refreshAll { onLoaded(cachedOf(author)) }
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Shares a new story: [media] (photo or video from the camera / gallery) with the text
     * sticker [label] for [audience]. It is saved in SQLite at once (so Your story can show
     * it), then uploaded to stories/create.php in one multipart request. Offline it waits in
     * the queue. [onDone] gets (true, null) when sent or queued, (false, message) if refused.
     */
    fun add(
        context: Context,
        media: Uri,
        mediaType: String,
        label: String,
        audience: String,
        onDone: (Boolean, String?) -> Unit,
    ) {
        val uuid = Outbox.newUuid()
        val localId = Outbox.localId(uuid)
        val created = now()
        Json.saveStory(
            JSONObject()
                .put("id", localId)
                .put("author_id", me.toLong())
                .put("media_url", media.toString())
                .put("media_type", mediaType)
                .put("label", label)
                .put("audience", audience)
                .put("created_at", created)
                .put("expires_at", created + LIFETIME_MS)
        )
        val body = JSONObject().put("label", label).put("audience", audience)
        Outbox.send(context, "create_story", "stories/create.php", body, listOf("media" to media), uuid, localId) { result ->
            when {
                result == null -> onDone(true, null)   // queued: uploaded when the internet is back
                result.ok -> {
                    LocalDb.delete(LocalDb.STORIES, "id = ?", arrayOf(localId))
                    result.json?.optJSONObject("story")?.let { Json.saveStory(it) }
                    onDone(true, null)
                }
                else -> {
                    LocalDb.delete(LocalDb.STORIES, "id = ?", arrayOf(localId))
                    onDone(false, result.error)
                }
            }
        }
    }

    /** Deletes my story. One still waiting in the queue is just taken back out of it. */
    fun delete(context: Context, story: Story) {
        LocalDb.delete(LocalDb.STORIES, "id = ?", arrayOf(story.id))
        if (isLocal(story.id)) {
            LocalDb.removePending(story.id.removePrefix("local-"))
            return
        }
        Outbox.send(context, "delete_story", "stories/delete.php", JSONObject().put("id", story.id.toLong()))
    }

    /** "Seen": the author sees me in Seen by (not for my own stories, not twice). */
    fun markViewed(context: Context, story: Story) {
        if (story.authorId == me || story.viewers.containsKey(me) || isLocal(story.id)) return
        editStory(story.id) { json ->
            val viewers = json.optJSONObject("viewers") ?: JSONObject().also { json.put("viewers", it) }
            viewers.put(me, now())
        }
        Outbox.send(context, "story_view", "stories/view.php", JSONObject().put("story_id", story.id.toLong()))
    }

    /** Reacts to someone's story; the server notifies the author (the first time). */
    fun react(context: Context, story: Story, reaction: Reaction) {
        if (story.authorId == me || isLocal(story.id)) return
        editStory(story.id) { json ->
            val reactions = json.optJSONObject("reactions") ?: JSONObject().also { json.put("reactions", it) }
            reactions.put(me, reaction.key)
        }
        val body = JSONObject().put("story_id", story.id.toLong()).put("type", reaction.key)
        Outbox.send(context, "story_react", "stories/react.php", body)
    }

    private fun editStory(id: String, change: (JSONObject) -> Unit) {
        val json = LocalDb.get(LocalDb.STORIES, "id", id) ?: return
        change(json)
        Json.saveStory(json)
    }
}
