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
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Listing
import com.rameen.i230806.databinding.ActivityMarketplaceBinding
import com.rameen.i230806.databinding.ItemListingBinding
import com.rameen.i230806.ui.BaseActivity

/**
 * 23 Marketplace: everything for sale, live from listings/, newest first, in two columns.
 *  - "JUST LISTED" on new items for their first 24 hours (and on two of the design's samples).
 *  - Sell: a small form (what, price, area) that adds a listing; everyone sees it at once.
 *  - The very first time the marketplace is empty, the four items from the design are added,
 *    so the screen is never blank on a fresh database.
 *  - Categories, the location and tapping a product are visual only.
 */
class MarketplaceActivity : BaseActivity() {

    private lateinit var binding: ActivityMarketplaceBinding
    private val listings = mutableListOf<Listing>()
    private val adapter = ListingAdapter()
    private var seeded = false

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

        // Oldest first from Firebase, so reversed for the screen.
        watch(Db.listings().orderByChild("timestamp")) { snapshot ->
            val list = snapshot.children
                .mapNotNull { child -> child.getValue(Listing::class.java)?.apply { id = child.key ?: id } }
                .reversed()
            if (list.isEmpty()) addSampleListings()
            listings.clear()
            listings.addAll(list)
            adapter.notifyDataSetChanged()
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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

    /** A new listing with a random placeholder photo (photos for listings aren't uploaded). */
    private fun addListing(title: String, price: String, place: String) {
        val ref = Db.listings().push()
        ref.setValue(
            Listing(
                id = ref.key ?: "",
                title = title,
                price = price,
                place = place,
                photo = PHOTOS.random(),
                justListed = true,
                timestamp = Db.now(),
            )
        )
    }

    /**
     * The four items from the design, added once when there is nothing for sale. Fixed ids,
     * so two phones doing this at the same time still make only four.
     */
    private fun addSampleListings() {
        if (seeded) return
        seeded = true
        val now = Db.now()
        val samples = listOf(
            Listing("sample_bike", getString(R.string.market_title_bike), getString(R.string.market_price_bike),
                getString(R.string.market_place_bike), "sage", true, now),
            Listing("sample_chair", getString(R.string.market_title_chair), getString(R.string.market_price_chair),
                getString(R.string.market_place_chair), "rose", false, now - 1),
            Listing("sample_desk", getString(R.string.market_title_desk), getString(R.string.market_price_desk),
                getString(R.string.market_place_desk), "sky", false, now - 2),
            Listing("sample_pots", getString(R.string.market_title_pots), getString(R.string.market_price_pots),
                getString(R.string.market_place_pots), "sand", true, now - 3),
        )
        samples.forEach { Db.listings().child(it.id).setValue(it) }
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
            // JUST LISTED: for a day after listing (the design's samples keep it).
            val showLabel = listing.justListed &&
                    (listing.id.startsWith("sample_") || Db.now() - listing.timestamp < DAY_MS)
            tile.tvJustListed.visibility = if (showLabel) View.VISIBLE else View.GONE
            tile.tvPrice.text = listing.price
            tile.tvTitle.text = listing.title
            tile.tvPlace.text = listing.place
            tile.root.contentDescription = getString(R.string.cd_listing, listing.price, listing.title, listing.place)
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val PHOTOS = listOf("sand", "teal", "lilac", "sky", "sage", "rose", "night")
    }
}