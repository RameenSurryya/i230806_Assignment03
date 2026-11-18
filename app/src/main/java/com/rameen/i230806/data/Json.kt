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
}
