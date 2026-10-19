package com.yusuf.ytklip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

// "2:38:20" -> saniye
fun timeToSeconds(t: String): Double {
    var total = 0.0
    for (part in t.split(":")) total = total * 60 + (part.toDoubleOrNull() ?: 0.0)
    return total
}

enum class Tone { NORMAL, OK, ERROR }

// Ekranda gösterilecek anlık durum. Servis yazar, MainActivity okur.
data class DlSnapshot(
    val busy: Boolean,
    val stage: String,
    val stageTone: Tone = Tone.NORMAL,
    val percent: Double = 0.0,
    val indeterminate: Boolean = false,
    val stats: String = "",
    val message: String = "",
    val messageTone: Tone = Tone.NORMAL
)

// Servis ile ekran arasındaki köprü. Uygulama arka plandayken listener null olur,
// geri dönünce MainActivity son durumu buradan okur.
object DownloadState {
    @Volatile var snapshot: DlSnapshot? = null
    @Volatile var listener: ((DlSnapshot) -> Unit)? = null

    val busy: Boolean get() = snapshot?.busy == true

    fun update(s: DlSnapshot) {
        snapshot = s
        listener?.invoke(s)
    }
}

/**
 * İndirmeyi ön plan servisi (foreground service) olarak çalıştırır.
 * Böylece uygulamadan çıksan, ekranı kapatsan bile indirme devam eder;
 * ilerleme bildirimde görünür ve oradan iptal edilebilir.
 */
class DownloadService : Service() {

    private sealed class Outcome {
        class Done(val folders: List<String>, val names: List<String>) : Outcome()
        class Cancelled : Outcome()
        class Failed(val message: String) : Outcome()
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var cancelled = false

    // ---- ilerleme durumu (sadece ana thread'de değiştirilir)
    private var stage = ""
    private var indeterminate = true
    private var dlPasses = 1
    private var dlDestCount = 0
    private var dlClipSec = 0.0
    private var dlOverall = 0.0
    private var dlBytes = 0L
    private var dlSpeed: String? = null
    private var dlEta: String? = null
    private var pollDir: File? = null
    private var lastNotifAt = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // inen dosyanın boyutunu düzenli ölçer (yüzde gelmese bile bir şeyler aktığını gösterir)
    private val poller = object : Runnable {
        override fun run() {
            val dir = pollDir
            if (dir == null || !running) return
            dlBytes = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            publish()
            main.postDelayed(this, 700)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                ensureChannels()
                // startForegroundService() çağrısından sonra 5 sn içinde bu çağrı şart
                enterForeground(buildNotification(""))
                if (running) return START_NOT_STICKY

                val url = intent.getStringExtra(EXTRA_URL)
                if (url == null) {
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }
                running = true
                begin(
                    url = url,
                    useSection = intent.getBooleanExtra(EXTRA_USE_SECTION, false),
                    start = intent.getStringExtra(EXTRA_START) ?: "0",
                    end = intent.getStringExtra(EXTRA_END) ?: "inf",
                    clipSec = intent.getDoubleExtra(EXTRA_CLIP_SEC, 0.0),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 720)
                )
            }
            ACTION_CANCEL -> {
                if (running) cancelDownload() else stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sistem servisi bir şekilde öldürürse ekran "indiriliyor"da takılı kalmasın
        if (running) {
            running = false
            cancelled = true
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (e: Exception) {
                // yok say
            }
            DownloadState.update(
                DlSnapshot(
                    busy = false,
                    stage = "Durduruldu",
                    stageTone = Tone.ERROR,
                    message = "İndirme sistem tarafından durduruldu. Tekrar dene.",
                    messageTone = Tone.ERROR
                )
            )
        }
        releaseLocks()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ============================================================ indirme

    private fun begin(
        url: String,
        useSection: Boolean,
        start: String,
        end: String,
        clipSec: Double,
        height: Int
    ) {
        val audioOnly = height == 0
        val workDir = File(cacheDir, "dl/${System.currentTimeMillis()}").also { it.mkdirs() }

        dlPasses = 1
        dlDestCount = 0
        dlClipSec = clipSec
        dlOverall = 0.0
        dlBytes = 0L
        dlSpeed = null
        dlEta = null
        cancelled = false
        stage = "Başlıyor…"
        indeterminate = true
        pollDir = workDir
        lastNotifAt = 0L

        acquireLocks()
        publish(force = true)
        main.removeCallbacks(poller)
        main.post(poller)

        io.execute {
            val outcome: Outcome = try {
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-mtime")
                request.addOption("-o", workDir.absolutePath + "/%(title).60s [%(id)s].%(ext)s")
                if (audioOnly) {
                    request.addOption("-f", "ba[ext=m4a]/ba")
                } else {
                    request.addOption("-f", videoFormat(height))
                    request.addOption("--merge-output-format", "mp4/mkv")
                }
                if (useSection) {
                    request.addOption("--download-sections", "*$start-$end")
                    // ffmpeg'in ilerleme bilgisini yt-dlp çıktısına geçirir
                    request.addOption("--downloader-args", "ffmpeg:-progress pipe:1")
                }

                val callback: (Float, Long, String) -> Unit = { _, _, line ->
                    main.post { handleLine(line) }
                }
                YoutubeDL.getInstance().execute(request, PROCESS_ID, callback)

                val files = workDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in OUT_EXT }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (files.isEmpty()) {
                    throw IllegalStateException("Çıktı dosyası oluşmadı.")
                }
                main.post {
                    if (running) {
                        stage = "Kaydediliyor…"
                        publish(force = true)
                    }
                }
                val folders = files.map { saveToMedia(it) }.distinct()
                Outcome.Done(folders, files.map { it.name })
            } catch (e: Throwable) {
                if (cancelled) {
                    Outcome.Cancelled()
                } else {
                    val msg = e.message ?: e.toString()
                    val hint = if (msg.contains("403")) {
                        "\n\nİpucu: uygulamada yt-dlp'yi güncelle, olmazsa Nightly'yi dene."
                    } else ""
                    Outcome.Failed(msg.takeLast(500) + hint)
                }
            } finally {
                workDir.deleteRecursively()
            }
            main.post { finish(outcome) }
        }
    }

    private fun cancelDownload() {
        cancelled = true
        stage = "İptal ediliyor…"
        publish(force = true)
        Thread {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (e: Exception) {
                // yok say
            }
        }.start()
    }

    private fun finish(o: Outcome) {
        main.removeCallbacks(poller)
        pollDir = null
        running = false
        releaseLocks()

        when (o) {
            is Outcome.Done -> {
                DownloadState.update(
                    DlSnapshot(
                        busy = false,
                        stage = "Tamamlandı",
                        stageTone = Tone.OK,
                        percent = 100.0,
                        message = "Kaydedildi: " + o.folders.joinToString(", ") + "\n" +
                            o.names.joinToString("\n"),
                        messageTone = Tone.OK
                    )
                )
                stopForegroundCompat()
                notifyDone("İndirme tamamlandı", o.names.joinToString("\n"), true)
            }
            is Outcome.Cancelled -> {
                DownloadState.update(
                    DlSnapshot(
                        busy = false,
                        stage = "İptal edildi",
                        message = "İndirme iptal edildi."
                    )
                )
                stopForegroundCompat()
            }
            is Outcome.Failed -> {
                DownloadState.update(
                    DlSnapshot(
                        busy = false,
                        stage = "Hata",
                        stageTone = Tone.ERROR,
                        percent = dlOverall,
                        message = o.message,
                        messageTone = Tone.ERROR
                    )
                )
                stopForegroundCompat()
                notifyDone("İndirme başarısız", o.message.lines().firstOrNull() ?: "Hata", false)
            }
        }
        stopSelf()
    }

    // yt-dlp / ffmpeg çıktısındaki her satırı yorumlar
    private fun handleLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || !running || cancelled) return

        FORMATS_RE.find(line)?.let {
            dlPasses = it.groupValues[1].split("+").size.coerceAtLeast(1)
        }
        if (line.contains("Destination:")) dlDestCount++

        if (MERGE_MARKERS.any { line.contains(it) }) {
            stage = "Birleştiriliyor…"
            indeterminate = false
            if (dlOverall < 99.0) dlOverall = 99.0
            publish()
            return
        }

        var frac = -1.0
        if (line.startsWith("[download]")) {
            DL_PERCENT.find(line)?.let { frac = it.groupValues[1].toDouble() / 100.0 }
            DL_SPEED.find(line)?.let { dlSpeed = it.groupValues[1] }
            DL_ETA.find(line)?.let { dlEta = it.groupValues[1] }
        } else if (dlClipSec > 0) {
            OUT_TIME.find(line)?.let {
                frac = (timeToSeconds(it.groupValues[1]) / dlClipSec).coerceIn(0.0, 1.0)
            }
        }

        if (frac >= 0) {
            val idx = (dlDestCount - 1).coerceIn(0, dlPasses - 1)
            val overall = (idx + frac) / dlPasses * 100.0
            if (overall > dlOverall) dlOverall = overall
            stage = "İndiriliyor"
            indeterminate = false
        }
        publish()
    }

    private fun videoFormat(h: Int): String =
        "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b"

    // Videoyu Galeri'ye (Movies/YtKlip), sesi Müzik'e (Music/YtKlip) kaydeder. İzin gerekmez (Android 10+).
    private fun saveToMedia(file: File): String {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val (dir, collection) = when {
            mime.startsWith("video") ->
                Pair(Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            mime.startsWith("audio") ->
                Pair(Environment.DIRECTORY_MUSIC, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            else ->
                Pair(Environment.DIRECTORY_DOWNLOADS, MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
        val relative = "$dir/YtKlip"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Dosya oluşturulamadı.")
        val out = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Dosya yazılamadı.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return relative
    }

    // ============================================================ durum / bildirim

    private fun statsText(): String {
        val parts = mutableListOf<String>()
        if (dlBytes > 0) parts.add(formatBytes(dlBytes))
        dlSpeed?.let { parts.add(it) }
        dlEta?.let { if (it != "Unknown") parts.add("kalan $it") }
        return parts.joinToString("   ·   ")
    }

    private fun formatBytes(b: Long): String = when {
        b >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= (1L shl 10) -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    // Ekrana ve bildirime güncel durumu gönderir (bildirim en fazla saniyede bir yenilenir)
    private fun publish(force: Boolean = false) {
        val stats = statsText()
        DownloadState.update(
            DlSnapshot(
                busy = true,
                stage = stage,
                percent = dlOverall,
                indeterminate = indeterminate,
                stats = stats
            )
        )
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastNotifAt >= 1000) {
            lastNotifAt = now
            notificationManager().notify(NOTIF_ID, buildNotification(stats))
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "İndirme durumu", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "İndirme sonucu", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel)
        else Notification.Builder(this)

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelIntent(): PendingIntent {
        val i = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(stats: String): Notification {
        val text = buildString {
            append(if (stage.isEmpty()) "Başlıyor…" else stage)
            if (!indeterminate) append("  %").append(dlOverall.toInt())
            if (stats.isNotEmpty()) append("  ·  ").append(stats)
        }
        val b = builder(CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YtKlip")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, dlOverall.toInt().coerceIn(0, 100), indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "İptal", cancelIntent())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun notifyDone(title: String, text: String, ok: Boolean) {
        val n = builder(CHANNEL_DONE)
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(text.lines().firstOrNull() ?: text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager().notify(NOTIF_DONE_ID, n)
    }

    private fun enterForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    // Ekran kapanınca CPU ve Wi-Fi uyumasın, indirme yarıda kesilmesin
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YtKlip:download").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YtKlip:download").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // kilit alınamazsa indirme yine de devam eder
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_START = "com.yusuf.ytklip.action.START"
        const val ACTION_CANCEL = "com.yusuf.ytklip.action.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_USE_SECTION = "use_section"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_CLIP_SEC = "clip_sec"
        const val EXTRA_HEIGHT = "height"

        private const val PROCESS_ID = "ytklip-download"
        private const val CHANNEL_PROGRESS = "ytklip_progress"
        private const val CHANNEL_DONE = "ytklip_done"
        private const val NOTIF_ID = 1001
        private const val NOTIF_DONE_ID = 1002

        private val OUT_EXT = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "mov", "aac", "ogg")
        private val MERGE_MARKERS = listOf("[Merger]", "[VideoRemuxer]", "[ExtractAudio]", "[Fixup")
        private val DL_PERCENT = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
        private val DL_SPEED = Regex("""\bat\s+(\S+/s)""")
        private val DL_ETA = Regex("""ETA\s+(\S+)""")
        private val OUT_TIME = Regex("""^out_time=(\d+:\d+:\d+(?:\.\d+)?)""")
        private val FORMATS_RE = Regex("""Downloading \d+ format\(s\):\s*(\S+)""")
    }
}
