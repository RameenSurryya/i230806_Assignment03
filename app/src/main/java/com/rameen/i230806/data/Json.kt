package com.rameen.i230806.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the PHP API's JSON into the app's model classes (Assignment 3).
 *
 * The screens from Assignment 2 already work with User, Post, Message ... so the server's
 * answers are converted into those same classes. Ids become text ("12"), and wherever a model
 * kept a Firebase media id (photoId, imageIds, mediaId) it now keeps the file's URL on our
 * server; Images.load() shows URLs with Glide.
 */
object Json {

    /** Like optString, but a JSON null becomes "" (Android's optString would give "null"). */
    fun str(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key, "")

    /** An array of strings ("image_urls": [...]) as a list. */
    fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }

    /** The objects of an array, e.g. json.optJSONArray("posts"). */
    fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }

    // ---------------------------------------------------------------------------------------
    // Users
    // ---------------------------------------------------------------------------------------

    fun user(j: JSONObject): User = User(
        uid = j.optLong("id").toString(),
        name = str(j, "name"),
        nameLower = str(j, "name").lowercase(),
        username = str(j, "username"),
        usernameLower = str(j, "username"),
        email = str(j, "email"),
        bio = str(j, "bio"),
        website = str(j, "website"),
        phone = str(j, "phone"),
        gender = str(j, "gender"),
        birthday = str(j, "birthday"),
        work = str(j, "work"),
        study = str(j, "study"),
        city = str(j, "city"),
        photoId = str(j, "photo_url"),     // a URL now
        coverId = str(j, "cover_url"),
        setupDone = j.optBoolean("setup_done", true),
        online = j.optBoolean("online"),
        lastSeen = j.optLong("last_seen"),
        createdAt = j.optLong("created_at"),
    )

    /** Converts a user from the server AND keeps a copy in SQLite (for offline) and in memory. */
    fun saveUser(j: JSONObject): User {
        val user = user(j)
        LocalDb.put(LocalDb.USERS, "id", user.uid, j, mapOf("name" to user.name, "username" to user.username))
        Db.remember(user)
        return user
    }

    /** A user from the phone's SQLite copy, or null if we've never seen them. */
    fun cachedUser(id: String): User? = LocalDb.get(LocalDb.USERS, "id", id)?.let { user(it) }

    // ---------------------------------------------------------------------------------------
    // Posts and comments
    // ---------------------------------------------------------------------------------------

    /** A post from the server (or a pending local one: id "local-<uuid>"). */
    fun post(j: JSONObject): Post {
        val reactions = HashMap<String, String>()
        j.optJSONObject("reactions")?.let { r -> r.keys().forEach { reactions[it] = r.optString(it) } }
        return Post(
            id = str(j, "id"),
            authorId = j.optLong("author_id").toString(),
            text = str(j, "text"),
            imageIds = strings(j.optJSONArray("image_urls")),   // URLs now (content:// while pending)
            timestamp = j.optLong("created_at"),
            reactions = reactions,
            commentCount = j.optLong("comment_count"),
            shareCount = j.optLong("share_count"),
            pending = j.optBoolean("pending"),
        )
    }

    /** Keeps a post (and its author) in SQLite. The columns are always taken from the JSON. */
    fun savePost(j: JSONObject): Post {
        j.optJSONObject("author")?.let { saveUser(it) }
        LocalDb.put(
            LocalDb.POSTS, "id", str(j, "id"), j,
            mapOf("author_id" to j.optLong("author_id").toString(), "created_at" to j.optLong("created_at"),
                "pending" to j.optBoolean("pending")),
        )
        return post(j)
    }

    /** A comment from the server (or a pending local one). */
    fun comment(j: JSONObject): Comment {
        val likes = HashMap<String, Boolean>()
        strings(j.optJSONArray("likes")).forEach { likes[it] = true }
        return Comment(
            id = str(j, "id"),
            postId = str(j, "post_id"),
            authorId = j.optLong("author_id").toString(),
            text = str(j, "text"),
            imageId = str(j, "image_url"),
            parentId = if (j.isNull("parent_id")) "" else str(j, "parent_id"),
            timestamp = j.optLong("created_at"),
            likes = likes,
            pending = j.optBoolean("pending"),
        )
    }

    fun saveComment(j: JSONObject): Comment {
        j.optJSONObject("author")?.let { saveUser(it) }
        LocalDb.put(
            LocalDb.COMMENTS, "id", str(j, "id"), j,
            mapOf("post_id" to str(j, "post_id"), "created_at" to j.optLong("created_at"),
                "pending" to j.optBoolean("pending")),
        )
        return comment(j)
    }

    // ---------------------------------------------------------------------------------------
    // Stories (step 6)
    // ---------------------------------------------------------------------------------------

    /** A story from the server (or a pending local one, whose media is still a content:// address). */
    fun story(j: JSONObject): Story {
        val viewers = HashMap<String, Long>()
        j.optJSONObject("viewers")?.let { v -> v.keys().forEach { viewers[it] = v.optLong(it) } }
        val reactions = HashMap<String, String>()
        j.optJSONObject("reactions")?.let { r -> r.keys().forEach { reactions[it] = r.optString(it) } }
        return Story(
            id = str(j, "id"),
            authorId = j.optLong("author_id").toString(),
            mediaId = str(j, "media_url"),          // a URL on our server
            mediaType = str(j, "media_type").ifEmpty { MediaType.IMAGE },
            label = str(j, "label"),
            audience = str(j, "audience").ifEmpty { Audience.PUBLIC },
            timestamp = j.optLong("created_at"),
            expiresAt = j.optLong("expires_at"),
            viewers = viewers,
            reactions = reactions,
        )
    }

    /** Keeps a story (and its author) in SQLite; expires_at lets the phone drop it after 24 h. */
    fun saveStory(j: JSONObject): Story {
        j.optJSONObject("author")?.let { saveUser(it) }
        LocalDb.put(
            LocalDb.STORIES, "id", str(j, "id"), j,
            mapOf("user_id" to j.optLong("author_id").toString(), "created_at" to j.optLong("created_at"),
                "expires_at" to j.optLong("expires_at")),
        )
        return story(j)
    }

    // ---------------------------------------------------------------------------------------
    // Chat messages (step 7)
    // ---------------------------------------------------------------------------------------

    fun message(j: JSONObject): Message {
        val reactions = HashMap<String, String>()
        j.optJSONObject("reactions")?.let { r -> r.keys().forEach { reactions[it] = r.optString(it) } }
        return Message(
            id = str(j, "id"),
            senderId = j.optLong("sender_id").toString(),
            type = str(j, "type").ifEmpty { MsgType.TEXT },
            text = str(j, "text"),
            mediaId = str(j, "media_url"),
            fileName = str(j, "file_name"),
            fileSize = j.optLong("file_size"),
            postId = if (j.isNull("post_id")) "" else str(j, "post_id"),
            replyToId = if (j.isNull("reply_to_id")) "" else str(j, "reply_to_id"),
            replyToText = str(j, "reply_to_text"),
            replyToSenderId = if (j.isNull("reply_to_sender_id")) "" else str(j, "reply_to_sender_id"),
            replyToType = str(j, "reply_to_type"),
            edited = j.optBoolean("edited"),
            deleted = j.optBoolean("deleted"),
            timestamp = j.optLong("created_at"),
            reactions = reactions,
            pending = j.optBoolean("pending"),
        )
    }

    fun saveMessage(j: JSONObject): Message {
        LocalDb.put(
            LocalDb.MESSAGES, "id", str(j, "id"), j,
            mapOf("chat_id" to str(j, "chat_id"), "created_at" to j.optLong("created_at"),
                "pending" to j.optBoolean("pending")),
        )
        return message(j)
    }
}
