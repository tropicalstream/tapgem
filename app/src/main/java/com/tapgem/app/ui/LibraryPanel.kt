package com.tapgem.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.tapgem.app.core.model.Canvas
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The drawer behind each strip button — apps & widgets, bookmarks, wallpapers &
 * themes — one look for all three: dark glass, a header, titled sections of
 * tiles. A tile is a thumbnail or a colour swatch or a big glyph, a label, an
 * optional badge, an optional ✕. Everything is a plain clickable view, so the
 * trackpad cursor's synthetic tap drives it. Long sections show two rows and
 * say how many more there are.
 */
class LibraryPanel(context: Context) : FrameLayout(context) {

    class Tile(
        val key: String,
        val label: String,
        val glyph: String = "",
        val thumb: Bitmap? = null,
        /** 1–3 colours painted as the tile's face (a theme or gradient swatch). */
        val swatch: IntArray? = null,
        val selected: Boolean = false,
        val deletable: Boolean = false,
        /** Dashed accent border: an action ("save this", "none"). */
        val dashed: Boolean = false,
        val badge: String? = null
    )
    class Section(val title: String, val tiles: List<Tile>, val tileW: Int = TILE_W, val tileH: Int = TILE_H, val cols: Int = 4, val maxRows: Int = 2,
                  /** Lines a label may take. Two lets "Chiptune Player" be read instead of "Chiptune Pla…". */
                  val labelLines: Int = 1)

    var onTap: ((Tile) -> Unit)? = null
    var onDelete: ((Tile) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var accent = 0xFF64D2FF.toInt()
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** Only the sections scroll; the header and its ✕ stay where they are. */
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = android.widget.ScrollView(context).apply {
        isFillViewport = false; overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = true
    }
    private val header = TextView(context)
    private val hint = TextView(context)

    init {
        elevation = 11f   // just under the notice line, so a notice still shows above the drawer
        background = GradientDrawable().apply { cornerRadius = 12f; setColor(0xEE0E141B.toInt()); setStroke(1, 0x33FFFFFF) }
        setPadding(12, 10, 12, 12)
        // An empty or one-tile drawer still reads as a panel (hint on one or two lines). The minimum goes on the
        // column, not the frame: a FrameLayout only re-measures a lone match_parent child from its own size.
        column.minimumWidth = TILE_W * 3 + 20
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.apply { setTextColor(0xFFE0F4FF.toInt()); textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.04f }
        top.addView(header, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = "✕"; setTextColor(0xFFB7CEDE.toInt()); textSize = 13f; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; contentDescription = "Close"
            setOnClickListener { onClose?.invoke() }
        }, LinearLayout.LayoutParams(28, 28))
        column.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        hint.apply { setTextColor(0xFF8FA6B8.toInt()); textSize = 10.5f; setPadding(2, 2, 2, 0); maxWidth = TILE_W * 4 + 24; minWidth = TILE_W * 3; visibility = GONE }
        column.addView(hint)
        scroller.addView(body, android.widget.FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    /**
     * The drawer grew taller than the glasses: with three sections the last one ("Sites") fell off
     * the bottom with nothing to say it was there. Measuring the contents against the room actually
     * left below the strip lets the scroller take over once they no longer fit, and changes nothing
     * for a drawer that does.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxOf(120, Canvas.HEIGHT - TOP_MARGIN - 8)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }

    /** Scroll by a wheel/two-finger delta; true when it actually moved. */
    fun scrollByDelta(dy: Float): Boolean {
        val before = scroller.scrollY
        scroller.scrollBy(0, dy.toInt())
        return scroller.scrollY != before
    }

    /** Rebuild: [title], [sections] in order, an optional [hint] line under the header. */
    fun show(title: String, sections: List<Section>, accent: Int, hintText: String? = null) {
        this.accent = accent
        header.text = title
        hint.text = hintText ?: ""; hint.visibility = if (hintText.isNullOrBlank()) GONE else VISIBLE
        body.removeAllViews()
        scroller.scrollTo(0, 0)
        for (s in sections) {
            if (s.tiles.isEmpty()) continue
            body.addView(TextView(context).apply {
                text = s.title.uppercase(); setTextColor(0xFF8FA6B8.toInt()); textSize = 9.5f; letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(2, 8, 2, 2)
            })
            val grid = GridLayout(context).apply { columnCount = s.cols; useDefaultMargins = false }
            val shown = s.tiles.take(s.cols * s.maxRows)
            shown.forEach { t -> grid.addView(tile(t, s.tileW, s.tileH, s.labelLines), GridLayout.LayoutParams().apply { width = s.tileW; height = s.tileH; setMargins(4, 4, 4, 4) }) }
            body.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (s.tiles.size > shown.size) body.addView(TextView(context).apply {
                text = "+${s.tiles.size - shown.size} more — say its name"; setTextColor(0xFF8FA6B8.toInt()); textSize = 10f; setPadding(6, 0, 2, 2)
            })
        }
        visibility = VISIBLE
    }

    private fun tile(t: Tile, w: Int, h: Int, labelLines: Int = 1): View {
        val small = h < 70
        val labelH = if (labelLines > 1) LABEL_H + 12 * (labelLines - 1) else LABEL_H
        val f = FrameLayout(context).apply {
            isClickable = true; isFocusable = true; contentDescription = t.label
            background = GradientDrawable().apply {
                cornerRadius = 8f
                setColor(if (t.dashed) (accent and 0x00FFFFFF) or 0x22000000 else 0xFF161E27.toInt())
                // A selected tile always gets the solid accent ring, even an action tile ("None" when there is no wallpaper).
                if (t.selected) setStroke(2, accent) else if (t.dashed) setStroke(1, accent, 6f, 4f) else setStroke(1, 0x40FFFFFF)
            }
            setOnClickListener { onTap?.invoke(t) }
        }
        val faceH = if (small) h else h - labelH
        // Face: thumbnail, swatch, or glyph.
        if (t.thumb != null) f.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(t.thumb); clipToOutline = true
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(8f, 8f, 8f, 8f, 0f, 0f, 0f, 0f); setColor(0xFF0B1016.toInt()) }
            if (t.dashed) alpha = 0.55f
        }, LayoutParams(LayoutParams.MATCH_PARENT, faceH))
        else if (t.swatch != null) f.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (t.swatch.size == 1) intArrayOf(t.swatch[0], t.swatch[0]) else t.swatch).apply {
                cornerRadii = if (small) floatArrayOf(8f, 8f, 8f, 8f, 8f, 8f, 8f, 8f) else floatArrayOf(8f, 8f, 8f, 8f, 0f, 0f, 0f, 0f)
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, faceH))
        if (t.thumb == null && t.glyph.isNotEmpty()) f.addView(TextView(context).apply {
            text = t.glyph; textSize = if (small) 14f else 22f; setTextColor(if (t.swatch != null) 0xFFFFFFFF.toInt() else accent); gravity = Gravity.CENTER
            if (t.swatch != null) setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }, LayoutParams(LayoutParams.MATCH_PARENT, faceH))
        if (t.dashed && t.thumb != null) f.addView(TextView(context).apply {
            text = "+"; textSize = 24f; setTextColor(accent); gravity = Gravity.CENTER; includeFontPadding = false; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }, LayoutParams(LayoutParams.MATCH_PARENT, faceH))
        // Label strip (over the face on small tiles).
        f.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6, 0, 4, 0)
            if (small) background = GradientDrawable().apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 8f, 8f, 8f, 8f); setColor(0x99000000.toInt()) }
            addView(TextView(context).apply {
                text = t.label; textSize = 10.5f; setTextColor(0xFFE0F4FF.toInt()); maxLines = labelLines; ellipsize = TextUtils.TruncateAt.END
                if (labelLines > 1) { setLineSpacing(0f, 0.95f); gravity = Gravity.CENTER_VERTICAL }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // The badge earns its place over the title: it says why a tile cannot be removed.
            t.badge?.let { b -> addView(TextView(context).apply {
                text = b; textSize = 9f; setTextColor(accent); setPadding(4, 0, 0, 0)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, if (small) 18 else labelH, Gravity.BOTTOM))
        if (t.selected && small) f.addView(TextView(context).apply { text = "✓"; textSize = 10f; setTextColor(accent); setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt()) }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = 2; marginEnd = 5 })
        if (t.deletable) f.addView(TextView(context).apply {
            text = "✕"; textSize = 9.5f; gravity = Gravity.CENTER; setTextColor(0xFFE0F4FF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3000000.toInt()) }
            isClickable = true; isFocusable = true; contentDescription = "Forget ${t.label}"
            setOnClickListener { onDelete?.invoke(t) }
        }, LayoutParams(18, 18, Gravity.TOP or Gravity.END).apply { topMargin = 3; marginEnd = 3 })
        return f
    }

    companion object {
        /** Where setupDrawers pins the panel; the scroll cap is measured from it. */
        const val TOP_MARGIN = 70
        const val TILE_W = 116
        const val TILE_H = 96
        const val LABEL_H = 22
    }
}
