package it.fambokki.neurale

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Scarica da sola le nuove edizioni pubblicate nel repository Fambokki/Neurale.
 * Elenco: editions.json (le voci con "pdf" sono per l'app Android).
 */
object Sync {
    private const val BASE = "https://raw.githubusercontent.com/Fambokki/Neurale/main/"
    private const val CHANNEL = "edizioni"
    @Volatile private var running = false

    /** Restituisce le edizioni appena scaricate (vuota se non c'è niente di nuovo o manca la rete). */
    fun run(ctx: Context): List<Edition> {
        if (running) return emptyList()
        running = true
        try {
            val json = get(BASE + "editions.json?t=" + System.currentTimeMillis()) ?: return emptyList()
            val arr = JSONObject(String(json, Charsets.UTF_8)).optJSONArray("editions") ?: return emptyList()
            val have = Library.list(ctx).map { it.id }.toSet()
            val nuove = mutableListOf<Edition>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val pdf = o.optString("pdf")
                if (id.isEmpty() || pdf.isEmpty()) continue
                Meta.save(ctx, id, o.optString("titolo"), o.optString("sommario"), o.optString("stile"))
                if (id in have || Meta.isDeleted(ctx, id)) continue
                val bytes = get(BASE + pdf) ?: continue
                val tmp = File(ctx.cacheDir, "sync-$id.pdf")
                tmp.writeBytes(bytes)
                Library.importFile(ctx, tmp, id)?.let { nuove += it }
                tmp.delete()
            }
            return nuove
        } catch (e: Exception) {
            return emptyList()
        } finally {
            running = false
        }
    }

    private fun get(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
    } catch (e: Exception) { null }

    /** Controllo automatico ogni 3 ore quando c'è la rete. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("neurale-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun notify(ctx: Context, eds: List<Edition>) {
        if (eds.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nuove edizioni", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val e = eds.first()
        val open = Intent(ctx, ReaderActivity::class.java).putExtra("id", e.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, e.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val titolo = Meta.title(ctx, e.id)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle("NEURALE · " + e.etichetta)
            .setContentText(if (titolo.isNotEmpty()) titolo else "La nuova edizione è nell'edicola")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (titolo.isNotEmpty()) titolo else "La nuova edizione è nell'edicola"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(e.id.hashCode(), n) } catch (se: SecurityException) { }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val nuove = Sync.run(applicationContext)
        Sync.notify(applicationContext, nuove)
        return Result.success()
    }
}

/** Titolo, sommario e stile delle edizioni arrivate in automatico; edizioni eliminate dall'utente. */
object Meta {
    private fun p(ctx: Context) = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
    fun save(ctx: Context, id: String, titolo: String, sommario: String, stile: String) {
        p(ctx).edit().putString("t:$id", titolo).putString("s:$id", sommario).putString("y:$id", stile).apply()
    }
    fun title(ctx: Context, id: String) = p(ctx).getString("t:$id", "") ?: ""
    fun summary(ctx: Context, id: String) = p(ctx).getString("s:$id", "") ?: ""
    fun style(ctx: Context, id: String) = p(ctx).getString("y:$id", "") ?: ""
    fun markDeleted(ctx: Context, id: String) = p(ctx).edit().putBoolean("d:$id", true).apply()
    fun unmarkDeleted(ctx: Context, id: String) = p(ctx).edit().remove("d:$id").apply()
    fun isDeleted(ctx: Context, id: String) = p(ctx).getBoolean("d:$id", false)
}
