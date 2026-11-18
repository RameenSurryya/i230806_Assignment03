package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about posts and comments (used by Create post, Home, the Reaction picker,
 * Comments and Share). Assignment 3: the data lives in MySQL behind our PHP web services and
 * in SQLite on the phone.
 *
 * READING: screens show what SQLite has straight away (cachedFeed, cachedPost, cachedComments),
 * then call a refresh function, which asks the server and saves the answer in SQLite.
 *
 * WRITING (create a post, react, comment, like, delete): the change is made in SQLite first,
 * so it shows at once, even offline; then it is sent through the Outbox. Offline, it waits in
 * the pending_actions queue with a UUID, and the sync worker sends it later, in order. Things
 * created offline get a local id ("local-<uuid>") and pending = true: the screen shows them
 * with "Waiting to send..." until the server's copy replaces them. The server uses the UUID
 * to ignore a repeat, so nothing is ever posted twice.
 *
 * Reactions and likes are SET (not toggled) on the server, so repeating one is harmless.
 */
object PostRepo {

    private val me: String get() = Db.uid ?: ""

    private fun isLocal(id: String) = id.startsWith("local-")

    /** The newest posts on the phone (mine and my friends'), newest first. Pending ones included. */
    fun cachedFeed(limit: Int = 50): List<Post> =
        LocalDb.list(LocalDb.POSTS, null, null, "created_at DESC", limit).map { Json.post(it) }

    /** Asks the server for the feed and saves it. Pending posts stay. [onDone] gets false if it failed. */
    fun refreshFeed(onDone: (Boolean) -> Unit) {
        Api.get("posts/feed.php") { result ->
            val json = result.json
            if (result.ok && json != null) {
                val posts = Json.objects(json.optJSONArray("posts"))
                LocalDb.replaceAll(LocalDb.POSTS, "pending = 0", null) { posts.forEach { Json.savePost(it) } }
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    fun cachedPost(id: String): Post? = LocalDb.get(LocalDb.POSTS, "id", id)?.let { Json.post(it) }

    /** Fresh copy of one post. [onDone] gets the post, or null; [gone] is true when it was deleted. */
    fun refreshPost(id: String, onDone: (post: Post?, gone: Boolean) -> Unit) {
        if (isLocal(id)) {
            onDone(cachedPost(id), false)
            return
        }
        Api.get("posts/get.php", mapOf("id" to id)) { result ->
            val json = result.json
            when {
                result.ok && json != null -> onDone(Json.savePost(json.getJSONObject("post")), false)
                result.status == 404 -> {
                    LocalDb.delete(LocalDb.POSTS, "id = ?", arrayOf(id))
                    onDone(null, true)
                }
                else -> onDone(null, false)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Posts
    // ---------------------------------------------------------------------------------------

    /**
     * Creates a post: shown at once as a pending post, then sent (photos shrunk and uploaded
     * together with the text in one request). [onDone] gets (true, null) when it was sent or
     * queued for later, (false, message) when the server refused it.
     */
    fun create(context: Context, text: String, pictures: List<Uri>, onDone: (Boolean, String?) -> Unit) {
        val uuid = Outbox.newUuid()
        val localId = Outbox.localId(uuid)
        Json.savePost(
            JSONObject()
                .put("id", localId)
                .put("author_id", me.toLong())
                .put("text", text)
                .put("image_urls", JSONArray(pictures.map { it.toString() }))
                .put("reactions", JSONObject())
                .put("comment_count", 0)
                .put("share_count", 0)
                .put("created_at", System.currentTimeMillis())
                .put("pending", true)
        )
        Outbox.send(
            context, "create_post", "posts/create.php", JSONObject().put("text", text),
            pictures.map { "images[]" to it }, uuid, localId,
        ) { result ->
            when {
                result == null -> onDone(true, null)   // queued: stays pending until the sync
                result.ok -> {
                    LocalDb.delete(LocalDb.POSTS, "id = ?", arrayOf(localId))
                    result.json?.getJSONObject("post")?.let { Json.savePost(it) }
                    onDone(true, null)
                }
                else -> {
                    LocalDb.delete(LocalDb.POSTS, "id = ?", arrayOf(localId))
                    onDone(false, result.error)
                }
            }
        }
    }

    /** Deletes my post. A post still waiting in the queue is simply taken back out of it. */
    fun delete(context: Context, post: Post) {
        LocalDb.delete(LocalDb.POSTS, "id = ?", arrayOf(post.id))
        LocalDb.delete(LocalDb.COMMENTS, "post_id = ?", arrayOf(post.id))
        if (isLocal(post.id)) {
            LocalDb.removePending(post.id.removePrefix("local-"))
            return
        }
        Outbox.send(context, "delete_post", "posts/delete.php", JSONObject().put("id", post.id.toLong()))
    }

    // ---------------------------------------------------------------------------------------
    // Reactions
    // ---------------------------------------------------------------------------------------

    /** My reaction on [post], or null. */
    fun myReaction(post: Post): Reaction? = Reaction.of(post.reactions[me])

    /**
     * Sets my reaction (or removes it when [reaction] is null). Choosing the reaction I already
     * have removes it, like tapping Like twice. SQLite changes first, so [onChanged] can redraw
     * at once; the server (which also notifies the author) is told through the Outbox.
     */
    fun react(context: Context, post: Post, reaction: Reaction?, onChanged: () -> Unit = {}) {
        if (isLocal(post.id)) return   // not on the server yet
        val target = if (reaction == null || reaction == myReaction(post)) null else reaction
        editPost(post.id) { json ->
            val reactions = json.optJSONObject("reactions") ?: JSONObject().also { json.put("reactions", it) }
            if (target == null) reactions.remove(me) else reactions.put(me, target.key)
        }
        onChanged()
        val body = JSONObject().put("post_id", post.id.toLong()).put("type", target?.key ?: "")
        Outbox.send(context, "react", "posts/react.php", body) { result ->
            val reactions = result?.json?.optJSONObject("reactions")
            if (result != null && result.ok && reactions != null) {
                editPost(post.id) { it.put("reactions", reactions) }
                onChanged()
            }
        }
    }

    /** Tapping Like: like, or undo whatever reaction I had. */
    fun toggleLike(context: Context, post: Post, onChanged: () -> Unit = {}) {
        react(context, post, if (myReaction(post) == null) Reaction.LIKE else null, onChanged)
    }

    /** Counts one more share (Share to chat). */
    fun countShare(postId: String) {
        if (isLocal(postId)) return
        Api.post("posts/share.php", JSONObject().put("id", postId.toLong()), Outbox.newUuid()) { }
    }

    /** Changes the saved JSON of one post (keeps its pending flag and other columns). */
    private fun editPost(id: String, change: (JSONObject) -> Unit) {
        val json = LocalDb.get(LocalDb.POSTS, "id", id) ?: return
        change(json)
        Json.savePost(json)
    }

    // ---------------------------------------------------------------------------------------
    // Comments
    // ---------------------------------------------------------------------------------------

    /** The comments of one post on the phone, oldest first (pending ones included). */
    fun cachedComments(postId: String): List<Comment> =
        LocalDb.list(LocalDb.COMMENTS, "post_id = ?", arrayOf(postId), "created_at").map { Json.comment(it) }

    /** Asks the server for the comments of [postId] and saves them (pending ones stay). */
    fun refreshComments(postId: String, onDone: (Boolean) -> Unit) {
        if (isLocal(postId)) {
            onDone(true)
            return
        }
        Api.get("comments/list.php", mapOf("post_id" to postId)) { result ->
            val json = result.json
            if (result.ok && json != null) {
                val list = Json.objects(json.optJSONArray("comments"))
                LocalDb.replaceAll(LocalDb.COMMENTS, "post_id = ? AND pending = 0", arrayOf(postId)) {
                    list.forEach { Json.saveComment(it) }
                }
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    /**
     * Adds a comment (or a reply when [parent] is given) with an optional photo. It shows at
     * once as a pending comment; the server notifies the post's author (and the person replied
     * to). [onDone] runs when it is saved in SQLite / sent, so the screen can redraw.
     */
    fun addComment(
        context: Context,
        post: Post,
        text: String,
        photo: Uri?,
        parent: Comment?,
        onDone: (Boolean, String?) -> Unit,
    ) {
        if (isLocal(post.id)) {
            onDone(false, null)
            return
        }
        val uuid = Outbox.newUuid()
        val localId = Outbox.localId(uuid)
        val parentId = parent?.let { it.parentId.ifEmpty { it.id } }   // replies stay one level deep
        val json = JSONObject()
            .put("id", localId)
            .put("post_id", post.id.toLong())
            .put("author_id", me.toLong())
            .put("parent_id", parentId?.toLongOrNull() ?: JSONObject.NULL)
            .put("text", text)
            .put("image_url", photo?.toString() ?: JSONObject.NULL)
            .put("likes", JSONArray())
            .put("created_at", System.currentTimeMillis())
            .put("pending", true)
        Json.saveComment(json)
        editPost(post.id) { it.put("comment_count", it.optLong("comment_count") + 1) }

        val body = JSONObject().put("post_id", post.id.toLong()).put("text", text)
        if (parentId != null) body.put("parent_id", parentId.toLong())
        Outbox.send(
            context, "add_comment", "comments/add.php", body,
            listOfNotNull(photo?.let { "image" to it }), uuid, localId,
        ) { result ->
            when {
                result == null -> onDone(true, null)
                result.ok -> {
                    LocalDb.delete(LocalDb.COMMENTS, "id = ?", arrayOf(localId))
                    result.json?.getJSONObject("comment")?.let { Json.saveComment(it) }
                    onDone(true, null)
                }
                else -> {
                    LocalDb.delete(LocalDb.COMMENTS, "id = ?", arrayOf(localId))
                    editPost(post.id) { it.put("comment_count", maxOf(0, it.optLong("comment_count") - 1)) }
                    onDone(false, result.error)
                }
            }
        }
    }

    /** Likes or un-likes a comment (shown at once). Its author is notified the first time. */
    fun toggleCommentLike(context: Context, comment: Comment, onChanged: () -> Unit = {}) {
        if (isLocal(comment.id)) return
        val like = !comment.likes.containsKey(me)
        editComment(comment.id) { json ->
            val likes = json.optJSONArray("likes") ?: JSONArray()
            val updated = JSONArray()
            for (i in 0 until likes.length()) if (likes.optString(i) != me) updated.put(likes.optString(i))
            if (like) updated.put(me)
            json.put("likes", updated)
        }
        onChanged()
        val body = JSONObject().put("comment_id", comment.id.toLong()).put("like", like)
        Outbox.send(context, "like_comment", "comments/like.php", body) { result ->
            val likes = result?.json?.optJSONArray("likes")
            if (result != null && result.ok && likes != null) {
                editComment(comment.id) { it.put("likes", likes) }
                onChanged()
            }
        }
    }

    /** Deletes my comment (and its replies) and lowers the post's comment count. */
    fun deleteComment(context: Context, comment: Comment) {
        val removed = LocalDb.list(
            LocalDb.COMMENTS, "id = ? OR json LIKE ?", arrayOf(comment.id, "%\"parent_id\":${comment.id}%"),
        ).size.coerceAtLeast(1)
        LocalDb.delete(LocalDb.COMMENTS, "id = ?", arrayOf(comment.id))
        LocalDb.delete(LocalDb.COMMENTS, "json LIKE ?", arrayOf("%\"parent_id\":${comment.id}%"))
        editPost(comment.postId) { it.put("comment_count", maxOf(0, it.optLong("comment_count") - removed)) }
        if (isLocal(comment.id)) {
            LocalDb.removePending(comment.id.removePrefix("local-"))
            return
        }
        Outbox.send(context, "delete_comment", "comments/delete.php", JSONObject().put("id", comment.id.toLong()))
    }

    private fun editComment(id: String, change: (JSONObject) -> Unit) {
        val json = LocalDb.get(LocalDb.COMMENTS, "id", id) ?: return
        change(json)
        Json.saveComment(json)
    }
}
