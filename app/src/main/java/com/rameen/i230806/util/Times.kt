package com.rameen.i230806.util

import android.content.Context
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Turns database timestamps (milliseconds) into the short texts the design uses.
 * "Now" is Firebase's clock (Db.now()), so every phone shows the same thing.
 */
object Times {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    /** "now", "5m", "2h", "Yesterday", "3d", "2w": posts, comments, notifications, requests. */
    fun ago(context: Context, timestamp: Long): String {
        val diff = (Db.now() - timestamp).coerceAtLeast(0)
        return when {
            diff < MINUTE -> context.getString(R.string.time_now)
            diff < HOUR -> context.getString(R.string.time_minutes, diff / MINUTE)
            diff < DAY -> context.getString(R.string.time_hours, diff / HOUR)
            diff < 2 * DAY -> context.getString(R.string.time_yesterday)
            diff < WEEK -> context.getString(R.string.time_days, diff / DAY)
            else -> context.getString(R.string.time_weeks, diff / WEEK)
        }
    }

    /** Chats list (20): "2m", "3h" today, then the weekday "Mon", then the date "12 Oct". */
    fun chatList(context: Context, timestamp: Long): String {
        val diff = Db.now() - timestamp
        return when {
            diff < DAY -> ago(context, timestamp)
            diff < WEEK -> format("EEE", timestamp)
            else -> format("d MMM", timestamp)
        }
    }

    /** "4:49 PM" */
    fun clock(timestamp: Long): String = format("h:mm a", timestamp)

    /** Time separator in a chat (21): "TODAY 4:49 PM", "YESTERDAY 9:02 AM", "MON 1:15 PM", "12 OCT 1:15 PM". */
    fun dayHeader(context: Context, timestamp: Long): String {
        val day = when {
            sameDay(timestamp, Db.now()) -> context.getString(R.string.time_today_caps)
            sameDay(timestamp, Db.now() - DAY) -> context.getString(R.string.time_yesterday_caps)
            Db.now() - timestamp < WEEK -> format("EEE", timestamp).uppercase(Locale.getDefault())
            else -> format("d MMM", timestamp).uppercase(Locale.getDefault())
        }
        return "$day ${clock(timestamp)}"
    }

    /** Profile details: "Joined March 2019". */
    fun joined(context: Context, timestamp: Long): String =
        context.getString(R.string.joined_format, format("MMMM yyyy", timestamp))

    /** Call timer: 192 seconds -> "03:12". */
    fun duration(seconds: Long): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private fun format(pattern: String, timestamp: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}