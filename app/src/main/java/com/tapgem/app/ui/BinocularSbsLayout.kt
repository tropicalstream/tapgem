package com.tapgem.app.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Side-by-side binocular compositor: the single child is measured to half the
 * physical width and drawn twice (left eye, right eye). Touches on the right
 * half are remapped into the logical viewport.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var remapCurrentTouchSequence = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        require(childCount == 1) { "BinocularSbsLayout expects exactly one logical viewport child." }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0) ?: return
        child.measure(
            MeasureSpec.makeMeasureSpec(logicalWidth(measuredWidth), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight.coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0) ?: return
        if (child.visibility == GONE) return
        val lw = logicalWidth(width)
        if (lw <= 0) return
        val t = drawingTime
        canvas.save(); canvas.clipRect(0, 0, lw, height); drawChild(canvas, child, t); canvas.restore()
        canvas.save(); canvas.translate(lw.toFloat(), 0f); canvas.clipRect(0, 0, lw, height); drawChild(canvas, child, t); canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val lw = logicalWidth(width)
        if (lw <= 0) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> remapCurrentTouchSequence = ev.getX(0) >= lw
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val remap = remapCurrentTouchSequence
                remapCurrentTouchSequence = false
                if (!remap) return super.dispatchTouchEvent(ev)
                val mapped = MotionEvent.obtain(ev); mapped.offsetLocation(-lw.toFloat(), 0f)
                val handled = super.dispatchTouchEvent(mapped); mapped.recycle(); return handled
            }
        }
        if (!remapCurrentTouchSequence) return super.dispatchTouchEvent(ev)
        val mapped = MotionEvent.obtain(ev); mapped.offsetLocation(-lw.toFloat(), 0f)
        val handled = super.dispatchTouchEvent(mapped); mapped.recycle(); return handled
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val lw = logicalWidth(width)
        if (lw <= 0 || event.getX(0) < lw) return super.dispatchGenericMotionEvent(event)
        val mapped = MotionEvent.obtain(event); mapped.offsetLocation(-lw.toFloat(), 0f)
        val handled = super.dispatchGenericMotionEvent(mapped); mapped.recycle(); return handled
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate()
    }

    private fun logicalWidth(total: Int) = (total / 2).coerceAtLeast(0)
}
