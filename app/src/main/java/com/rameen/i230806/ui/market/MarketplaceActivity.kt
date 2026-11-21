package com.rameen.i230806.ui.market

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.Cached
import com.rameen.i230806.data.Json
import com.rameen.i230806.data.Outbox
import org.json.JSONObject
import com.rameen.i230806.data.Listing
import com.rameen.i230806.databinding.ActivityMarketplaceBinding
import com.rameen.i230806.databinding.ItemListingBinding
import com.rameen.i230806.ui.BaseActivity

/**
 * 23 Marketplace: everything for sale from market/list.php (Assignment 3), newest first, in
 * two columns. The saved copy shows at once (works offline).
 *  - "JUST LISTED" on new items for their first 24 hours (and on two of the design's samples),
 *    decided by the server.
 *  - Sell: a small form (what, price, area) that sends market/create.php; offline it waits in
 *    the queue. It shows in the grid straight away.
 *  - The first time the marketplace is empty, the server adds the four items from the design.
 *  - Categories, the location and tapping a product are visual only.
 */
class MarketplaceActivity : BaseActivity() {

    private lateinit var binding: ActivityMarketplaceBinding
    private val listings = mutableListOf<Listing>()
    private val adapter = ListingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarketplaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar(R.string.title_marketplace)
        setupTopTabs(Tab.MARKETPLACE)

        binding.rvListings.layoutManager = GridLayoutManager(this, 2)
        binding.rvListings.adapter = adapter
        binding.rvListings.isNestedScrollingEnabled = false   // the page's ScrollView scrolls instead
        binding.llSell.setOnClickListener { showSellForm() }

    }

    override fun onStart() {
        super.onStart()
        load()
    }

    private fun load() {
        Cached.get("market/list.php") { json ->
            if (isDestroyed) return@get
            listings.clear()
            Json.objects(json.optJSONArray("listings")).mapTo(listings) { item ->
                Listing(
                    id = Json.str(item, "id"),
                    title = Json.str(item, "title"),
                    price = Json.str(item, "price"),
                    place = Json.str(item, "place"),
                    photo = Json.str(item, "photo"),
                    justListed = item.optBoolean("just_listed"),
                    timestamp = item.optLong("created_at"),
                )
            }
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (listings.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sell
    // ---------------------------------------------------------------------------------------

    /** Three fields in a dialog; "List it" saves the new listing. */
    private fun showSellForm() {
        val what = field(R.string.market_sell_what, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val price = field(R.string.market_sell_price, InputType.TYPE_CLASS_TEXT)
        val place = field(R.string.market_sell_place, InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val margin = resources.getDimensionPixelSize(R.dimen.screen_margin)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin / 2, margin, 0)
            addView(what)
            addView(price)
            addView(place)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.market_sell_title)
            .setView(form)
            .setPositiveButton(R.string.market_sell_post) { _, _ ->
                val title = what.text.toString().trim()
                val cost = price.text.toString().trim()
                if (title.isEmpty() || cost.isEmpty()) {
                    toast(R.string.market_sell_missing)
                } else {
                    addListing(title, cost, place.text.toString().trim())
                    toast(R.string.market_listed)
                }
            }
            .setNegativeButton(R.string.market_sell_cancel, null)
            .show()
    }

    private fun field(hint: Int, flags: Int) = EditText(this).apply {
        setHint(hint)
        inputType = InputType.TYPE_CLASS_TEXT or flags
        setSingleLine()
    }

    /**
     * A new listing with a random placeholder photo (photos for listings aren't uploaded).
     * Shown at the top at once; sent now, or later from the offline queue.
     */
    private fun addListing(title: String, price: String, place: String) {
        val photo = PHOTOS.random()
        listings.add(0, Listing("local", title, price, place, photo, true, System.currentTimeMillis()))
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = View.GONE
        val body = JSONObject().put("title", title).put("price", price).put("place", place).put("photo", photo)
        Outbox.send(this, "create_listing", "market/create.php", body) { result ->
            if (result != null && result.ok && !isDestroyed) load()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Grid
    // ---------------------------------------------------------------------------------------

    /** "sage" -> the ph_sage placeholder picture. */
    private fun photoFor(name: String): Int = when (name) {
        "sand" -> R.drawable.ph_sand
        "teal" -> R.drawable.ph_teal
        "lilac" -> R.drawable.ph_lilac
        "sky" -> R.drawable.ph_sky
        "rose" -> R.drawable.ph_rose
        "night" -> R.drawable.ph_night
        else -> R.drawable.ph_sage
    }

    private inner class Holder(val tile: ItemListingBinding) : RecyclerView.ViewHolder(tile.root)

    private inner class ListingAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = listings.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemListingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val listing = listings[position]
            val tile = holder.tile
            tile.ivPhoto.setImageResource(photoFor(listing.photo))
            tile.ivPhoto.clipToOutline = true            // 12dp rounded corners (bg_photo_rounded)
            // JUST LISTED: the server decides (a day after listing; two of the design's samples).
            tile.tvJustListed.visibility = if (listing.justListed) View.VISIBLE else View.GONE
            tile.tvPrice.text = listing.price
            tile.tvTitle.text = listing.title
            tile.tvPlace.text = listing.place
            tile.root.contentDescription = getString(R.string.cd_listing, listing.price, listing.title, listing.place)
        }
    }

    private companion object {
        val PHOTOS = listOf("sand", "teal", "lilac", "sky", "sage", "rose", "night")
    }
}