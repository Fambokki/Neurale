package it.fambokki.neurale

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** Pagina con zoom: due dita o doppio tocco; trascina quando è ingrandita. */
class ZoomImageView(ctx: Context) : AppCompatImageView(ctx) {

    var onTap: (() -> Unit)? = null
    private val m = Matrix()
    private val base = Matrix()
    private var scale = 1f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, 5f)
            val f = ns / scale
            scale = ns
            m.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) { m.set(base); scale = 1f; imageMatrix = m }
            else { scale = 2.5f; m.postScale(2.5f, 2.5f, e.x, e.y); clamp() }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed()) return false
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun zoomed() = scale > 1.01f

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val d = drawable
        if (d == null || width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val s = min(width / dw, height / dh)
        base.reset()
        base.postScale(s, s)
        base.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
        m.set(base)
        scale = 1f
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        // finché la pagina è ingrandita il trascinamento muove la pagina, non sfoglia
        parent?.requestDisallowInterceptTouchEvent(zoomed() || ev.pointerCount > 1)
        return true
    }
}
