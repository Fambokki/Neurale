package it.fambokki.neurale

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity() {

    private lateinit var ed: Edition
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var pager: ViewPager2
    private lateinit var top: View
    private lateinit var counter: TextView
    private var pageCount = 0
    private var barsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val found = id?.let { Library.get(this, it) }
        if (found == null) { finish(); return }
        ed = found
        try {
            pfd = ParcelFileDescriptor.open(ed.file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile aprire l'edizione", Toast.LENGTH_LONG).show()
            finish(); return
        }
        State.setRead(this, ed.id)

        val mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF050506.toInt()) }
        counter = TextView(this).apply {
            typeface = mono; setTextColor(C.LIME); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        pager = ViewPager2(this).apply {
            adapter = PageAdapter()
            offscreenPageLimit = 1
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    counter.text = "${position + 1}/$pageCount"
                    State.setLastPage(this@ReaderActivity, ed.id, position)
                }
            })
        }
        root.addView(pager, FrameLayout.LayoutParams(-1, -1))

        // barra in alto
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            setBackgroundColor(0xE60B0C0E.toInt())
        }
        val close = TextView(this).apply {
            text = "✕"; setTextColor(C.FG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(C.BG2); setStroke(dp(1), C.LINE); shape = GradientDrawable.OVAL }
            setOnClickListener { finish() }
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(TextView(this@ReaderActivity).apply {
                text = "NEURALE" + (ed.numero?.let { " · N. $it" } ?: "")
                setTextColor(C.FG); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(TextView(this@ReaderActivity).apply {
                text = ed.dataLunga; typeface = mono; setTextColor(C.MUT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
        }
        (top as LinearLayout).apply {
            addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(counter)
        }
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)
        // Android 15: la barra in alto scende sotto la barra di stato del telefono
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.setPadding(dp(10) + b.left, dp(8) + b.top, dp(14) + b.right, dp(8))
            pager.setPadding(b.left, 0, b.right, b.bottom)
            insets
        }

        val start = State.lastPage(this, ed.id).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        counter.text = "${start + 1}/$pageCount"
        pager.setCurrentItem(start, false)
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        top.animate().alpha(if (barsVisible) 1f else 0f).setDuration(150).withEndAction {
            top.visibility = if (barsVisible) View.VISIBLE else View.INVISIBLE
        }.start()
        if (barsVisible) top.visibility = View.VISIBLE
    }

    private fun renderPage(i: Int, targetW: Int): Bitmap? = synchronized(lock) {
        val r = renderer ?: return null
        try {
            val p = r.openPage(i)
            val w = targetW.coerceIn(400, 1800)
            val h = (w * p.height / p.width.toFloat()).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            bmp
        } catch (e: Exception) { null }
    }

    inner class PageVH(val iv: ZoomImageView) : RecyclerView.ViewHolder(iv) {
        var pos = -1
    }

    inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = pageCount
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val iv = ZoomImageView(this@ReaderActivity).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                onTap = { toggleBars() }
            }
            return PageVH(iv)
        }

        override fun onBindViewHolder(h: PageVH, position: Int) {
            h.pos = position
            h.iv.setImageBitmap(null)
            val target = (resources.displayMetrics.widthPixels * 1.6f).toInt()
            io.execute {
                val bmp = renderPage(position, target)
                main.post { if (h.pos == position && bmp != null) h.iv.setImageBitmap(bmp) }
            }
        }

        override fun onViewRecycled(h: PageVH) {
            h.pos = -1
            h.iv.setImageBitmap(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        synchronized(lock) {
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
            renderer = null
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
