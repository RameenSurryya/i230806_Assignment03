package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import com.google.firebase.database.ServerValue
import com.rameen.i230806.util.Images

/**
 * Everything that changes posts and comments (used by Create post, Home, the Reaction picker,
 * Comments and Share). How it is stored (see Models.kt):
 *
 *   posts/{postId}                     Post, with reactions/{uid} = "like" | "love" ...
 *   comments/{postId}/{commentId}      Comment, with likes/{uid} = true
 *   media/{mediaId}                    the pictures, as Base64 text (Images.kt)
 *
 * Counters (commentCount, shareCount) use ServerValue.increment: Firebase adds 1 on the
 * server, so two people commenting at the same moment both count.
 */
object PostRepo {

    private val me: String get() = Db.uid ?: ""

    // ---------------------------------------------------------------------------------------
    // Posts
    // ---------------------------------------------------------------------------------------

    /**
     * Uploads the pictures one after another (each becomes media/{id}), then saves the post.
     * [onDone] gets true when everything was saved.
     */
    fun create(context: Context, text: String, pictures: List<Uri>, onDone: (Boolean) -> Unit) {
        uploadAll(context, pictures, 0, mutableListOf()) { imageIds ->
            if (imageIds == null) {
                onDone(false)
                return@uploadAll
            }
            val ref = Db.posts().push()           // push() makes a new unique, time-ordered id
            val post = Post(
                id = ref.key ?: "",
                authorId = me,
                text = text,
                imageIds = imageIds,
                timestamp = Db.now(),
            )
            ref.setValue(post).addOnCompleteListener { onDone(it.isSuccessful) }
        }
    }

    /** Uploads pictures[index], then the next one, collecting their media ids in order. */
    private fun uploadAll(
        context: Context,
        pictures: List<Uri>,
        index: Int,
        ids: MutableList<String>,
        onDone: (List<String>?) -> Unit,
    ) {
        if (index == pictures.size) {
            onDone(ids)
            return
        }
        Images.uploadImage(context, pictures[index]) { mediaId ->
            if (mediaId == null) {
                onDone(null)
            } else {
                ids.add(mediaId)
                uploadAll(context, pictures, index + 1, ids, onDone)
            }
        }
    }

    /** Deletes my post, its comments and its pictures in one write. */
    fun delete(post: Post) {
        val paths = HashMap<String, Any?>()
        paths["posts/${post.id}"] = null
        paths["comments/${post.id}"] = null
        post.imageIds.forEach { paths["media/$it"] = null }
        Db.root.updateChildren(paths)
    }

    // ---------------------------------------------------------------------------------------
    // Reactions
    // ---------------------------------------------------------------------------------------

    /** My reaction on [post], or null. */
    fun myReaction(post: Post): Reaction? = Reaction.of(post.reactions[me])

    /**
     * Sets my reaction (or removes it when [reaction] is null). Choosing the reaction I already
     * have removes it, like tapping Like twice. The author is notified the first time I react.
     */
    fun react(post: Post, reaction: Reaction?) {
        val ref = Db.post(post.id).child("reactions").child(me)
        val current = myReaction(post)
        if (reaction == null || reaction == current) {
            ref.removeValue()
        } else {
            ref.setValue(reaction.key)
            if (current == null) Db.notify(post.authorId, NotifType.POST_REACTION, post.id, reaction.key)
        }
    }

    /** Tapping Like: like, or undo whatever reaction I had. */
    fun toggleLike(post: Post) {
        react(post, if (myReaction(post) == null) Reaction.LIKE else null)
    }

    /** Counts one more share (Share to chat). */
    fun countShare(postId: String) {
        Db.post(postId).child("shareCount").setValue(ServerValue.increment(1))
    }

    // ---------------------------------------------------------------------------------------
    // Comments
    // ---------------------------------------------------------------------------------------

    /**
     * Adds a comment (or a reply when [parent] is given) with optional picture [imageId].
     * The post's author gets a COMMENT notification; when replying, the person replied to
     * gets a REPLY notification.
     */
    fun addComment(post: Post, text: String, imageId: String, parent: Comment?) {
        val ref = Db.comments(post.id).push()
        val comment = Comment(
            id = ref.key ?: "",
            postId = post.id,
            authorId = me,
            text = text,
            imageId = imageId,
            parentId = parent?.let { it.parentId.ifEmpty { it.id } } ?: "",   // replies stay one level deep
            timestamp = Db.now(),
        )
        ref.setValue(comment)
        Db.post(post.id).child("commentCount").setValue(ServerValue.increment(1))

        if (parent == null) {
            Db.notify(post.authorId, NotifType.COMMENT, post.id, text)
        } else {
            Db.notify(parent.authorId, NotifType.REPLY, post.id, text)
            if (parent.authorId != post.authorId) Db.notify(post.authorId, NotifType.COMMENT, post.id, text)
        }
    }

    /** Likes or un-likes a comment. Its author is notified when it is liked. */
    fun toggleCommentLike(comment: Comment) {
        val ref = Db.comments(comment.postId).child(comment.id).child("likes").child(me)
        if (comment.likes.containsKey(me)) {
            ref.removeValue()
        } else {
            ref.setValue(true)
            Db.notify(comment.authorId, NotifType.COMMENT_LIKE, comment.postId, comment.text)
        }
    }

    /** Deletes my comment (and its picture) and lowers the post's comment count. */
    fun deleteComment(comment: Comment) {
        val paths = HashMap<String, Any?>()
        paths["comments/${comment.postId}/${comment.id}"] = null
        if (comment.imageId.isNotEmpty()) paths["media/${comment.imageId}"] = null
        Db.root.updateChildren(paths)
        Db.post(comment.postId).child("commentCount").setValue(ServerValue.increment(-1))
    }
}