package com.rameen.i230806.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.Db
import com.rameen.i230806.ui.chat.ChatsActivity
import com.rameen.i230806.ui.home.HomeActivity
import com.rameen.i230806.ui.home.SearchActivity
import com.rameen.i230806.ui.market.MarketplaceActivity
import com.rameen.i230806.ui.social.FriendsActivity
import com.rameen.i230806.ui.social.MenuActivity
import com.rameen.i230806.ui.social.NotificationsActivity

/**
 * Parent class of every Kinnect screen.
 *
 * It does six shared jobs so each screen stays short:
 *  1. Edge-to-edge system bars, so the screen's own background shows behind the status bar
 *     and the gesture bar (each layout root has android:fitsSystemWindows="true").
 *  2. The five top tabs (layout/include_top_tabs.xml): highlights the active tab and opens
 *     the others with FLAG_ACTIVITY_REORDER_TO_FRONT, so tab screens never pile up.
 *  3. The top bar (layout/include_top_bar.xml): page title (or the wordmark on Home via
 *     setupHomeTopBar()), search and messenger.
 *  4. Badges: the red number on messenger (chats with unread messages) and on the bell
 *     (unread notifications), asked from our server (badges.php) when the screen shows and
 *     every 20 seconds while it stays visible.
 *  5. Badges refresh only while the screen is visible (onStart / onStop).
 *  6. Small helpers: back arrows, opening screens, toasts.
 *
 * Usage in a tab screen:
 *   class FriendsActivity : BaseActivity() {
 *       override fun onCreate(savedInstanceState: Bundle?) {
 *           super.onCreate(savedInstanceState)
 *           setContentView(binding.root)
 *           setupTopBar(R.string.title_friends)
 *           setupTopTabs(Tab.FRIENDS)
 *       }
 *   }
 */
abstract class BaseActivity : AppCompatActivity() {

    /** Dark screens (camera, stories, voice call) return true to get white status bar icons. */
    protected open val darkSystemBars: Boolean = false

    /** The logged-in user's id ("" when nobody is logged in). */
    protected val myUid: String get() = Db.uid ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. Transparent status and navigation bars. "dark" = white icons, "light" = dark icons.
        val style = if (darkSystemBars) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    // ---------------------------------------------------------------------------------------
    // 2. Top tabs
    // ---------------------------------------------------------------------------------------

    /**
     * The five tabs. Each one knows its views in include_top_tabs.xml and the screen it opens.
     */
    protected enum class Tab(
        val frameId: Int,
        val iconId: Int,
        val indicatorId: Int,
        val screen: Class<out Activity>,
    ) {
        HOME(R.id.fl_tab_home, R.id.iv_tab_home, R.id.v_tab_home_indicator,
            HomeActivity::class.java),
        FRIENDS(R.id.fl_tab_friends, R.id.iv_tab_friends, R.id.v_tab_friends_indicator,
            FriendsActivity::class.java),
        MARKETPLACE(R.id.fl_tab_marketplace, R.id.iv_tab_marketplace, R.id.v_tab_marketplace_indicator,
            MarketplaceActivity::class.java),
        NOTIFICATIONS(R.id.fl_tab_notifications, R.id.iv_tab_notifications, R.id.v_tab_notifications_indicator,
            NotificationsActivity::class.java),
        MENU(R.id.fl_tab_menu, R.id.iv_tab_menu, R.id.v_tab_menu_indicator,
            MenuActivity::class.java),
    }

    /**
     * Highlights [active] (teal icon + teal underline), greys out the others and makes every
     * other tab open its screen. Also starts the live bell badge. Call after setContentView().
     */
    protected fun setupTopTabs(active: Tab) {
        val teal = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.teal))
        val grey = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_grey))

        for (tab in Tab.values()) {
            val isActive = tab == active

            // Icon colour: same as app:tint in the XML, changed from code.
            ImageViewCompat.setImageTintList(findViewById<ImageView>(tab.iconId), if (isActive) teal else grey)

            // Underline: only the active tab shows it (INVISIBLE keeps the layout the same).
            findViewById<View>(tab.indicatorId).visibility = if (isActive) View.VISIBLE else View.INVISIBLE

            val frame = findViewById<View>(tab.frameId)
            frame.isSelected = isActive // TalkBack reads "selected" on the current tab
            frame.setOnClickListener {
                // Tapping the tab you are on does nothing; the others come to the front.
                if (!isActive) openScreen(tab.screen, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        }
        watchNotificationBadge()
    }

    // ---------------------------------------------------------------------------------------
    // 3. Top bar
    // ---------------------------------------------------------------------------------------

    /** Tab screens: shows the page title, e.g. setupTopBar(R.string.title_friends). */
    protected fun setupTopBar(@StringRes title: Int) {
        findViewById<TextView>(R.id.tv_top_title).setText(title)
        wireTopBarButtons()
    }

    /** Home: shows the teal "kinnect" wordmark instead of a page title. */
    protected fun setupHomeTopBar() {
        findViewById<View>(R.id.tv_top_title).visibility = View.GONE
        findViewById<View>(R.id.tv_wordmark).visibility = View.VISIBLE
        wireTopBarButtons()
    }

    /** Search opens the Search screen, messenger opens the Chats list; starts the messenger badge. */
    private fun wireTopBarButtons() {
        findViewById<View>(R.id.fl_search).setOnClickListener {
            openScreen(SearchActivity::class.java)
        }
        findViewById<View>(R.id.fl_messenger).setOnClickListener {
            openScreen(ChatsActivity::class.java)
        }
        watchMessengerBadge()
    }

    // ---------------------------------------------------------------------------------------
    // 4. Live badges
    // ---------------------------------------------------------------------------------------

    private var messengerBadge: TextView? = null
    private var notificationBadge: TextView? = null
    private val badgeHandler = Handler(Looper.getMainLooper())
    private val badgeTick = object : Runnable {
        override fun run() {
            refreshBadges()
            badgeHandler.postDelayed(this, BADGE_REFRESH_MS)
        }
    }

    /** Messenger badge = number of conversations that have unread messages. */
    private fun watchMessengerBadge() {
        messengerBadge = findViewById<TextView>(R.id.tv_messenger_badge)?.also { showBadge(it, 0) }
    }

    /** Bell badge = number of notifications not opened yet. */
    private fun watchNotificationBadge() {
        notificationBadge = findViewById<TextView>(R.id.tv_tab_notifications_badge)?.also { showBadge(it, 0) }
    }

    /** Asks badges.php for both numbers. Notifications calls it after marking everything read. */
    protected fun refreshBadges() {
        if (myUid.isEmpty() || (messengerBadge == null && notificationBadge == null)) return
        Api.get("badges.php") { result ->
            val json = result.json ?: return@get
            if (!result.ok || isDestroyed) return@get
            messengerBadge?.let { showBadge(it, json.optInt("chats")) }
            notificationBadge?.let { showBadge(it, json.optInt("notifications")) }
        }
    }

    /** Hides the badge at 0, shows "1".."9", then "9+". */
    private fun showBadge(badge: TextView, count: Int) {
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
        badge.text = if (count > 9) getString(R.string.badge_many) else count.toString()
    }

    // ---------------------------------------------------------------------------------------
    // 5. Badges follow the screen's lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        badgeHandler.post(badgeTick)
    }

    override fun onStop() {
        badgeHandler.removeCallbacks(badgeTick)
        super.onStop()
    }

    // ---------------------------------------------------------------------------------------
    // 6. Back arrow, navigation and messages
    // ---------------------------------------------------------------------------------------

    /** Makes the view [buttonId] (back arrow, X, Cancel) close this screen and return to the previous one. */
    protected fun setupBackButton(buttonId: Int) {
        findViewById<View>(buttonId).setOnClickListener { finish() }
    }

    /**
     * Opens [screen] with an explicit Intent, plus optional flags and extras, e.g.
     * openScreen(OtherProfileActivity::class.java) { putExtra(Extra.UID, uid) }
     */
    protected fun openScreen(
        screen: Class<out Activity>,
        flags: Int = 0,
        extras: Intent.() -> Unit = {},
    ) {
        startActivity(Intent(this, screen).addFlags(flags).apply(extras))
    }

    /** Short message at the bottom of the screen. */
    protected fun toast(@StringRes message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    protected fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "Kinnect"
        const val BADGE_REFRESH_MS = 20_000L
    }
}
