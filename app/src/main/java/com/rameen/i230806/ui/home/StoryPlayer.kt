package com.rameen.i230806.ui.home

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.VideoView
import com.rameen.i230806.R
import com.rameen.i230806.data.MediaType
import com.rameen.i230806.data.Story
import com.rameen.i230806.util.Images

/**
 * Plays a list of stories one after another, like Instagram / Facebook. Shared by the Story
 * viewer (11) and Your story (12).
 *
 *  - Progress bar: one segment per story (item_story_segment.xml). The current one fills up
 *    over the story's length: 5 seconds for a photo, the video's own length for a video.
 *  - A Handler "ticks" every 50 ms to grow the fill; at the end it moves to the next story.
 *  - Touch: a quick tap on the left third goes back, anywhere else goes forward.
 *    Holding a finger down pauses (the tick stops counting and the video pauses).
 *  - [onShow] is called for every story shown, [onFinished] after the last one.
 */
class StoryPlayer(
    private val activity: Activity,
    private val progress: LinearLayout,
    private val photo: ImageView,
    private val video: VideoView,
    touchArea: View,
    private val onShow: (Story) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var stories: List<Story> = emptyList()
    private var index = 0
    private val fills = mutableListOf<View>()

    private val handler = Handler(Looper.getMainLooper())
    private var durationMs = PHOTO_MS
    private var elapsedMs = 0L
    private var lastTick = 0L
    private var paused = false
    private var loading = false
    private var finished = false

    val current: Story? get() = stories.getOrNull(index)

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (!paused && !loading) elapsedMs += now - lastTick
            lastTick = now
            fills.getOrNull(index)?.scaleX = (elapsedMs.toFloat() / durationMs).coerceAtMost(1f)
            if (elapsedMs >= durationMs) next() else handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        setupTouch(touchArea)
    }

    @SuppressLint("ClickableViewAccessibility")   // taps here only move between stories
    private fun setupTouch(area: View) {
        var downAt = 0L
        area.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = event.eventTime
                    pause()
                }
                MotionEvent.ACTION_UP -> {
                    resume()
                    if (event.eventTime - downAt < TAP_MS) {
                        if (event.x < view.width / 3f) previous() else next()
                    }
                }
                MotionEvent.ACTION_CANCEL -> resume()
            }
            true
        }
    }

    /** Starts playing [list] from [startIndex]. */
    fun start(list: List<Story>, startIndex: Int = 0) {
        stories = list
        finished = false
        buildSegments()
        show(startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
    }

    /** One progress segment per story, with a small gap between them. */
    private fun buildSegments() {
        progress.removeAllViews()
        fills.clear()
        val gap = activity.resources.getDimensionPixelSize(R.dimen.story_progress_gap)
        stories.forEachIndexed { i, _ ->
            val segment = LayoutInflater.from(activity).inflate(R.layout.item_story_segment, progress, false)
            if (i > 0) (segment.layoutParams as ViewGroup.MarginLayoutParams).marginStart = gap
            val fill = segment.findViewById<View>(R.id.v_fill)
            fill.pivotX = 0f   // grow from the left edge
            fills.add(fill)
            progress.addView(segment)
        }
    }

    private fun show(i: Int) {
        if (stories.isEmpty()) return
        index = i
        handler.removeCallbacks(tick)
        video.stopPlayback()
        fills.forEachIndexed { j, fill -> fill.scaleX = if (j < i) 1f else 0f }
        elapsedMs = 0
        durationMs = PHOTO_MS

        val story = stories[i]
        if (story.mediaType == MediaType.VIDEO) {
            // Wait for the video to download and get ready; its length is the story length.
            loading = true
            photo.visibility = View.INVISIBLE
            video.visibility = View.VISIBLE
            Images.loadVideo(activity, story.mediaId) { file ->
                if (activity.isDestroyed || index != i) return@loadVideo
                if (file == null) {
                    loading = false
                    return@loadVideo
                }
                video.setVideoPath(file.path)
                video.setOnPreparedListener { player ->
                    durationMs = player.duration.toLong().coerceAtLeast(1000)
                    loading = false
                    if (!paused) video.start()
                }
            }
        } else {
            loading = false
            video.visibility = View.GONE
            photo.visibility = View.VISIBLE
            Images.load(photo, story.mediaId)
        }
        onShow(story)
        lastTick = SystemClock.uptimeMillis()
        handler.post(tick)
    }

    fun next() {
        if (index + 1 < stories.size) {
            show(index + 1)
        } else if (!finished) {
            finished = true
            handler.removeCallbacks(tick)
            onFinished()
        }
    }

    fun previous() = show((index - 1).coerceAtLeast(0))

    fun pause() {
        paused = true
        if (video.isPlaying) video.pause()
    }

    fun resume() {
        paused = false
        lastTick = SystemClock.uptimeMillis()
        if (video.visibility == View.VISIBLE && !loading) video.start()
    }

    /** Your story: after deleting the current story, keep playing the rest. */
    fun removeCurrent() {
        val left = stories.filterIndexed { i, _ -> i != index }
        if (left.isEmpty()) {
            release()
            onFinished()
        } else {
            start(left, index.coerceAtMost(left.size - 1))
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        video.stopPlayback()
    }

    private companion object {
        const val PHOTO_MS = 5000L
        const val TICK_MS = 50L
        const val TAP_MS = 250L
    }
}