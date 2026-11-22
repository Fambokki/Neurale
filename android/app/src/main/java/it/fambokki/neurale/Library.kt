package it.fambokki.neurale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Un'edizione di NEURALE salvata nell'app. */
data class Edition(
    val id: String,
    val date: LocalDate?,
    val file: File,
    val cover: File,
    val importedAt: Long
) {
    /** Numero dell'edizione: 23.10.2026 = N. 296, poi +1 al giorno. */
    val numero: Int? get() = date?.let { 296 + ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 23), it).toInt() }

    val dataBreve: String
        get() = date?.let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "Senza data"

    val dataLunga: String
        get() = date?.let {
            val g = it.dayOfWeek.getDisplayName(TextStyle.FULL, IT)
            val m = it.month.getDisplayName(TextStyle.FULL, IT)
            "$g ${it.dayOfMonth} $m ${it.year}"
        } ?: "Edizione importata"

    val etichetta: String get() = (numero?.let { "N. $it · " } ?: "") + dataBreve

    companion object {
        val IT: Locale = Locale.ITALIAN
    }
}

object Library {
    private val DATE_RE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    private fun pdfDir(ctx: Context) = File(ctx.filesDir, "edizioni").apply { mkdirs() }
    private fun coverDir(ctx: Context) = File(ctx.filesDir, "copertine").apply { mkdirs() }

    fun list(ctx: Context): List<Edition> {
        val files = pdfDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".pdf") } ?: return emptyList()
        return files.map { f ->
            val id = f.name.removeSuffix(".pdf")
            Edition(id, parseDate(id), f, File(coverDir(ctx), "$id.jpg"), f.lastModified())
        }.sortedWith(
            compareByDescending<Edition> { it.date ?: LocalDate.MIN }.thenByDescending { it.importedAt }
        )
    }

    fun get(ctx: Context, id: String): Edition? = list(ctx).firstOrNull { it.id == id }

    private fun parseDate(s: String): LocalDate? {
        val m = DATE_RE.find(s) ?: return null
        return try {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        } catch (e: Exception) { null }
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment
    }

    /** Copia il PDF nell'archivio dell'app. Restituisce l'edizione o null se il file non è un PDF valido. */
    fun import(ctx: Context, uri: Uri): Edition? {
        val name = displayName(ctx, uri) ?: ""
        val date = parseDate(name)
        val id = date?.toString() ?: ("importata-" + System.currentTimeMillis())
        val tmp = File(ctx.cacheDir, "import-${System.nanoTime()}.pdf")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            Meta.unmarkDeleted(ctx, id)
            importFile(ctx, tmp, id)
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** Archivia un PDF già scaricato con l'id indicato (AAAA-MM-GG). */
    fun importFile(ctx: Context, src: File, id: String): Edition? {
        if (pageCount(src) <= 0) return null
        val dest = File(pdfDir(ctx), "$id.pdf")
        val part = File(pdfDir(ctx), "$id.part")
        src.copyTo(part, overwrite = true)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
        val cover = File(coverDir(ctx), "$id.jpg")
        renderCover(dest, cover)
        return Edition(id, parseDate(id), dest, cover, dest.lastModified())
    }

    fun pageCount(f: File): Int = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val r = PdfRenderer(pfd)
            val n = r.pageCount
            r.close()
            n
        }
    } catch (e: Exception) { -1 }

    fun renderCover(pdf: File, out: File, width: Int = 600) {
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val r = PdfRenderer(pfd)
                val p = r.openPage(0)
                val h = (width * p.height / p.width.toFloat()).toInt()
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                p.close()
                r.close()
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        } catch (e: Exception) { }
    }

    fun delete(ctx: Context, e: Edition) {
        e.file.delete()
        e.cover.delete()
        State.forget(ctx, e.id)
        Meta.markDeleted(ctx, e.id)
    }
}

/** Stato di lettura (ultima pagina, edizione aperta o no). */
object State {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lettura", Context.MODE_PRIVATE)
    fun lastPage(ctx: Context, id: String) = prefs(ctx).getInt("p:$id", 0)
    fun setLastPage(ctx: Context, id: String, p: Int) = prefs(ctx).edit().putInt("p:$id", p).apply()
    fun isRead(ctx: Context, id: String) = prefs(ctx).getBoolean("r:$id", false)
    fun setRead(ctx: Context, id: String) = prefs(ctx).edit().putBoolean("r:$id", true).apply()
    fun forget(ctx: Context, id: String) = prefs(ctx).edit().remove("p:$id").remove("r:$id").apply()
}
