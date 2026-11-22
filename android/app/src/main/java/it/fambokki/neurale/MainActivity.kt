package it.fambokki.neurale

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.format.TextStyle
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: RecyclerView
    private lateinit var count: TextView
    private lateinit var empty: View
    private val adapter = EdAdapter()
    private val covers = LruCache<String, android.graphics.Bitmap>(40)

    private lateinit var anton: Typeface
    private lateinit var mono: Typeface

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importa(uris, openSingle = uris.size == 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        anton = ResourcesCompat.getFont(this, R.font.anton) ?: Typeface.DEFAULT_BOLD
        mono = ResourcesCompat.getFont(this, R.font.jetbrains_mono) ?: Typeface.MONOSPACE

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // Testata
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(12))
        }
        val logo = TextView(this).apply {
            val s = SpannableString("NEURALE.")
            s.setSpan(ForegroundColorSpan(C.LIME), 7, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = s
            typeface = anton
            setTextColor(C.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        }
        count = TextView(this).apply {
            typeface = mono
            setTextColor(C.MUT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), 0, 0, 0)
        }
        val spacer = View(this)
        val add = pill("+ Aggiungi", C.LIME, C.BG).apply { setOnClickListener { picker.launch(arrayOf("application/pdf")) } }
        bar.addView(logo)
        bar.addView(count)
        bar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(add)
        root.addView(bar)
        root.addView(View(this).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, dp(1)))

        val content = FrameLayout(this)
        list = RecyclerView(this).apply {
            clipToPadding = false
            setPadding(dp(14), dp(8), dp(14), dp(40))
        }
        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 2
        val glm = GridLayoutManager(this, span)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.items[position] is Item.Card) 1 else span
        }
        list.layoutManager = glm
        list.adapter = adapter
        content.addView(list, FrameLayout.LayoutParams(-1, -1))

        empty = emptyView()
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // Android 15: l'app va a tutto schermo, quindi lascio spazio a barra di stato e barra di navigazione
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, 0)
            list.setPadding(dp(14), dp(8), dp(14), dp(40) + b.bottom)
            insets
        }

        refresh()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { uris += it }
            Intent.ACTION_SEND -> streamExtra(i)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(i)
        }
        if (uris.isEmpty()) return
        i.action = Intent.ACTION_MAIN // evita di reimportare alla rotazione
        importa(uris, openSingle = uris.size == 1)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun importa(uris: List<Uri>, openSingle: Boolean) {
        Toast.makeText(this, if (uris.size == 1) "Aggiungo l'edizione…" else "Aggiungo ${uris.size} edizioni…", Toast.LENGTH_SHORT).show()
        io.execute {
            val ok = uris.mapNotNull { Library.import(this, it) }
            val bad = uris.size - ok.size
            main.post {
                covers.evictAll()
                refresh()
                val msg = when {
                    ok.isEmpty() -> "Il file non è un PDF valido"
                    ok.size == 1 -> "Aggiunta: ${ok[0].etichetta}" + if (bad > 0) " ($bad non validi)" else ""
                    else -> "Aggiunte ${ok.size} edizioni" + if (bad > 0) " ($bad non validi)" else ""
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (openSingle && ok.size == 1) apri(ok[0])
            }
        }
    }

    private fun refresh() {
        val eds = Library.list(this)
        count.text = if (eds.isEmpty()) "" else "${eds.size} ${if (eds.size == 1) "edizione" else "edizioni"}"
        empty.visibility = if (eds.isEmpty()) View.VISIBLE else View.GONE
        val items = mutableListOf<Item>()
        if (eds.isNotEmpty()) {
            items += Item.Hero(eds[0])
            var lastMonth = ""
            for (e in eds) {
                val m = e.date?.let { it.month.getDisplayName(TextStyle.FULL, Edition.IT) + " " + it.year } ?: "Altre edizioni"
                if (m != lastMonth) { items += Item.Month(m); lastMonth = m }
                items += Item.Card(e)
            }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
    }

    private fun apri(e: Edition) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("id", e.id))
    }

    private fun chiediElimina(e: Edition) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare l'edizione?")
            .setMessage("${e.etichetta} verrà tolta dall'archivio.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ -> Library.delete(this, e); covers.remove(e.id); refresh() }
            .show()
    }

    // ---------- elenco ----------

    sealed class Item {
        data class Hero(val e: Edition) : Item()
        data class Month(val label: String) : Item()
        data class Card(val e: Edition) : Item()
    }

    inner class EdAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Item> = emptyList()
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when (items[position]) {
            is Item.Hero -> 0; is Item.Month -> 1; is Item.Card -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> HeroVH(heroView())
            1 -> MonthVH(TextView(this@MainActivity).apply {
                typeface = mono; setTextColor(C.MUT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isAllCaps = true; letterSpacing = 0.06f
                setPadding(dp(4), dp(22), dp(4), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            })
            else -> CardVH(cardView())
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Hero -> (h as HeroVH).bind(item.e)
                is Item.Month -> (h as MonthVH).tv.text = item.label
                is Item.Card -> (h as CardVH).bind(item.e)
            }
        }
    }

    inner class MonthVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        private val img: ImageView = v.findViewWithTag("img")
        private val badge: TextView = v.findViewWithTag("badge")
        private val line1: TextView = v.findViewWithTag("l1")
        private val line2: TextView = v.findViewWithTag("l2")
        fun bind(e: Edition) {
            loadCover(e, img)
            badge.visibility = if (State.isRead(this@MainActivity, e.id)) View.GONE else View.VISIBLE
            line1.text = e.etichetta
            line2.text = e.date?.let { it.dayOfWeek.getDisplayName(TextStyle.FULL, Edition.IT) } ?: "importata"
            itemView.setOnClickListener { apri(e) }
            itemView.setOnLongClickListener { chiediElimina(e); true }
        }
    }

    inner class HeroVH(v: View) : RecyclerView.ViewHolder(v) {
        private val img: ImageView = v.findViewWithTag("img")
        private val kicker: TextView = v.findViewWithTag("kicker")
        private val title: TextView = v.findViewWithTag("title")
        private val btn: TextView = v.findViewWithTag("btn")
        fun bind(e: Edition) {
            loadCover(e, img)
            kicker.text = "ULTIMA EDIZIONE"
            title.text = e.dataLunga.uppercase(Edition.IT) + (e.numero?.let { "\nN. $it" } ?: "")
            val p = State.lastPage(this@MainActivity, e.id)
            btn.text = if (p > 0) "Riprendi da pag. ${p + 1}  →" else "Leggi l'edizione  →"
            btn.setOnClickListener { apri(e) }
            img.setOnClickListener { apri(e) }
            img.setOnLongClickListener { chiediElimina(e); true }
        }
    }

    private fun loadCover(e: Edition, iv: ImageView) {
        iv.setTag(R.id.cover_id, e.id)
        iv.setImageDrawable(null)
        covers.get(e.id)?.let { iv.setImageBitmap(it); return }
        io.execute {
            if (!e.cover.exists()) Library.renderCover(e.file, e.cover)
            val bmp = BitmapFactory.decodeFile(e.cover.absolutePath)
            main.post {
                if (bmp != null) covers.put(e.id, bmp)
                if (iv.getTag(R.id.cover_id) == e.id) iv.setImageBitmap(bmp)
            }
        }
    }

    // ---------- viste ----------

    private fun coverFrame(): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply { setColor(C.BG3); cornerRadius = dp(5).toFloat(); setStroke(dp(1), C.LINE) }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        elevation = dp(6).toFloat()
    }

    private fun cardView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(10))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            isClickable = true
            background = selectable()
        }
        val frame = coverFrame()
        val img = AspectImageView(this).apply { tag = "img"; scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, FrameLayout.LayoutParams(-1, -2))
        val badge = TextView(this).apply {
            tag = "badge"; text = "NUOVO"; typeface = mono
            setTextColor(C.BG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply { setColor(C.LIME); cornerRadius = dp(3).toFloat() }
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), dp(8), 0, 0) })
        col.addView(frame, LinearLayout.LayoutParams(-1, -2))
        col.addView(TextView(this).apply {
            tag = "l1"; typeface = mono; setTextColor(C.FG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(9), 0, 0)
        })
        col.addView(TextView(this).apply {
            tag = "l2"; setTextColor(C.MUT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        return col
    }

    private fun heroView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(16), dp(6), dp(8))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
        val frame = coverFrame()
        val img = AspectImageView(this).apply { tag = "img"; scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, FrameLayout.LayoutParams(-1, -2))
        val w = (resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtMost(dp(360))
        col.addView(frame, LinearLayout.LayoutParams(w, -2))
        col.addView(TextView(this).apply {
            tag = "kicker"; typeface = mono; setTextColor(C.LIME); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            letterSpacing = 0.08f; setPadding(0, dp(18), 0, dp(4)); gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            tag = "title"; typeface = anton; setTextColor(C.FG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.0f)
        })
        col.addView(pill("Leggi l'edizione  →", C.LIME, C.BG).apply {
            tag = "btn"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(22), dp(12), dp(22), dp(12))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        return col
    }

    private fun emptyView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(32), dp(32), dp(60))
        addView(TextView(this@MainActivity).apply {
            text = "NEURALE"; typeface = anton; setTextColor(C.BG3); setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = "L'edicola è vuota"
            setTextColor(C.FG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(10))
        })
        addView(TextView(this@MainActivity).apply {
            text = "Quando arriva la rivista, apri il PDF e scegli Condividi → NEURALE (oppure Apri con → NEURALE).\n\nPer le edizioni già scaricate tocca “+ Aggiungi” e selezionale anche tutte insieme dalla cartella Download."
            setTextColor(C.MUT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        })
    }

    private fun pill(t: String, bg: Int, fg: Int) = TextView(this).apply {
        text = t; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(999).toFloat() }
        isClickable = true
    }

    private fun selectable() = TypedValue().let { tv ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        ResourcesCompat.getDrawable(resources, tv.resourceId, theme)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** ImageView con proporzioni di una pagina della rivista (8:11). */
class AspectImageView(ctx: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 11f / 8f).toInt()
        setMeasuredDimension(w, h)
    }
}

object C {
    const val BG = 0xFF0B0C0E.toInt()
    const val BG2 = 0xFF141519.toInt()
    const val BG3 = 0xFF1D1F24.toInt()
    const val LINE = 0xFF2A2C33.toInt()
    const val FG = 0xFFF4F4F0.toInt()
    const val MUT = 0xFF9A9CA3.toInt()
    const val LIME = 0xFFC6F432.toInt()
    const val ORANGE = 0xFFFF5A1F.toInt()
}
