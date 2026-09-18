package com.tapgem.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tapgem.app.core.model.Widget
import com.tapgem.app.core.model.WidgetType
import com.tapgem.app.core.store.Bookmarks

/**
 * The bookmarks drawer under the strip: saved windows as thumbnails with their
 * name, a "save the active window" tile up front, a ✕ on each to forget it.
 * Everything is a plain clickable view so the trackpad cursor's synthetic tap
 * drives it like any strip button. Dark glass, rounded, accent from the theme.
 */
class BookmarkPanel(context: Context) : FrameLayout(context) {

    var onOpen: ((Bookmarks.Bookmark) -> Unit)? = null
    var onDelete: ((Bookmarks.Bookmark) -> Unit)? = null
    var onSaveActive: (() -> Unit)? = null
    var onSaveWallpaper: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var accent = 0xFF64D2FF.toInt()
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = TextView(context)
    private val grid = GridLayout(context).apply { columnCount = COLS; useDefaultMargins = false }
    private val empty = TextView(context)

    init {
        elevation = 11f   // just under the notice line, so "Bookmarked …" still shows above the drawer
        background = GradientDrawable().apply { cornerRadius = 12f; setColor(0xEE0E141B.toInt()); setStroke(1, 0x33FFFFFF) }
        minimumWidth = TILE_W * 2 + 40
        setPadding(12, 10, 12, 12)
        isClickable = false
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.apply { setTextColor(0xFFE0F4FF.toInt()); textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.04f }
        top.addView(header, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = "✕"; setTextColor(0xFFB7CEDE.toInt()); textSize = 13f; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; contentDescription = "Close bookmarks"
            setOnClickListener { onClose?.invoke() }
        }, LinearLayout.LayoutParams(28, 28))
        column.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        empty.apply { setTextColor(0xFFB7CEDE.toInt()); textSize = 11.5f; setPadding(2, 8, 2, 2); maxWidth = TILE_W * 2 + 16 }
        column.addView(empty)
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
        addView(column)
        visibility = GONE
    }

    fun setAccent(color: Int) { accent = color }

    /**
     * Rebuild from the store. [active] = the window a "+" tile would save (null = none);
     * [wallpaperThumb] = the current desktop's wallpaper for a "keep wallpaper" tile (null = none / already kept).
     */
    fun refresh(list: List<Bookmarks.Bookmark>, active: Widget?, wallpaperThumb: android.graphics.Bitmap? = null) {
        header.text = if (list.isEmpty()) "Bookmarks" else "Bookmarks · ${list.size}"
        grid.removeAllViews()
        if (active != null) grid.addView(saveTile(active), cell())
        if (wallpaperThumb != null) grid.addView(saveWallpaperTile(wallpaperThumb), cell())
        list.take(MAX_TILES).forEach { b -> grid.addView(tile(b), cell()) }
        empty.visibility = if (list.isEmpty()) VISIBLE else GONE
        empty.text = if (active != null) "Nothing saved yet. Tap + to keep “${active.title}” — or this wallpaper — for later, or say “bookmark this window”."
            else "Nothing saved yet. Focus a window and tap + here, or say “bookmark this window”. Bookmarks open on any desktop."
        if (list.size > MAX_TILES) {
            column.findViewWithTag<TextView>("more")?.let { column.removeView(it) }
            column.addView(TextView(context).apply { tag = "more"; text = "+${list.size - MAX_TILES} more — say its name"; setTextColor(0xFF8FA6B8.toInt()); textSize = 10.5f; setPadding(2, 6, 2, 0) })
        } else column.findViewWithTag<TextView>("more")?.let { column.removeView(it) }
    }

    private fun cell() = GridLayout.LayoutParams().apply { width = TILE_W; height = TILE_H; setMargins(4, 4, 4, 4) }

    private fun tile(b: Bookmarks.Bookmark): View {
        val f = FrameLayout(context).apply {
            isClickable = true; isFocusable = true; contentDescription = b.title
            background = GradientDrawable().apply { cornerRadius = 8f; setColor(0xFF161E27.toInt()); setStroke(1, 0x40FFFFFF) }
            setOnClickListener { onOpen?.invoke(b) }
        }
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(8f, 8f, 8f, 8f, 0f, 0f, 0f, 0f); setColor(0xFF0B1016.toInt()) }
            val bmp = b.thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
            if (bmp != null) setImageBitmap(bmp) else { setImageDrawable(null) }
        }
        f.addView(img, LayoutParams(LayoutParams.MATCH_PARENT, THUMB_H))
        if (b.thumb == null) f.addView(TextView(context).apply {
            text = glyph(b); textSize = 22f; setTextColor(accent); gravity = Gravity.CENTER
        }, LayoutParams(LayoutParams.MATCH_PARENT, THUMB_H))
        // Type glyph badge + title strip along the bottom.
        f.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6, 0, 4, 0)
            addView(TextView(context).apply { text = glyph(b); textSize = 10f; setTextColor(accent) })
            addView(TextView(context).apply {
                text = b.title; textSize = 10.5f; setTextColor(0xFFE0F4FF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(4, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, TILE_H - THUMB_H, Gravity.BOTTOM))
        // Forget button, top-right.
        f.addView(TextView(context).apply {
            text = "✕"; textSize = 9.5f; gravity = Gravity.CENTER; setTextColor(0xFFE0F4FF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3000000.toInt()) }
            isClickable = true; isFocusable = true; contentDescription = "Forget ${b.title}"
            setOnClickListener { onDelete?.invoke(b) }
        }, LayoutParams(18, 18, Gravity.TOP or Gravity.END).apply { topMargin = 3; marginEnd = 3 })
        return f
    }

    private fun saveTile(active: Widget): View = FrameLayout(context).apply {
        isClickable = true; isFocusable = true; contentDescription = "Bookmark ${active.title}"
        background = GradientDrawable().apply { cornerRadius = 8f; setColor((accent and 0x00FFFFFF) or 0x22000000); setStroke(1, accent, 6f, 4f) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(context).apply { text = "+"; textSize = 24f; setTextColor(accent); gravity = Gravity.CENTER; includeFontPadding = false })
            addView(TextView(context).apply {
                text = "Save “${active.title}”"; textSize = 10f; setTextColor(0xFFE0F4FF.toInt()); gravity = Gravity.CENTER
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(6, 2, 6, 0)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { onSaveActive?.invoke() }
    }

    private fun saveWallpaperTile(thumb: android.graphics.Bitmap): View = FrameLayout(context).apply {
        isClickable = true; isFocusable = true; contentDescription = "Keep this wallpaper"
        background = GradientDrawable().apply { cornerRadius = 8f; setColor(0xFF161E27.toInt()); setStroke(1, accent, 6f, 4f) }
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); alpha = 0.55f; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 8f; setColor(0xFF0B1016.toInt()) } }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(context).apply { text = "+"; textSize = 24f; setTextColor(accent); gravity = Gravity.CENTER; includeFontPadding = false; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()) })
            addView(TextView(context).apply { text = "Keep wallpaper"; textSize = 10f; setTextColor(0xFFE0F4FF.toInt()); gravity = Gravity.CENTER; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { onSaveWallpaper?.invoke() }
    }

    private fun glyph(b: Bookmarks.Bookmark): String = if (b.isWallpaper) "▦" else glyph(b.type!!)

    private fun glyph(t: WidgetType): String = when (t) {
        WidgetType.APP -> "◈"; WidgetType.WEB -> "◎"; WidgetType.MAP -> "⌖"; WidgetType.VIDEO -> "▶"; WidgetType.AUDIO -> "♪"
        WidgetType.PDF, WidgetType.EPUB -> "❡"; WidgetType.IMAGE -> "▣"; WidgetType.TICKER -> "≋"; WidgetType.LIVE -> "◉"
        WidgetType.CLOCK -> "◷"; WidgetType.TEXT -> "¶"; WidgetType.MODEL3D -> "◆"
    }

    companion object {
        const val COLS = 4
        const val TILE_W = 116
        const val TILE_H = 96
        const val THUMB_H = 74
        const val MAX_TILES = 11   // + the save tile = three full rows
    }
}
