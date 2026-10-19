package com.yusuf.ytklip

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.method.DigitsKeyListener
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    @Volatile
    private var ready = false

    @Volatile
    private var busy = false

    private lateinit var urlInput: EditText
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var exactCut: CheckBox
    private lateinit var nightly: CheckBox
    private lateinit var downloadBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
        initEngine()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun makeButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = roundBg(color, 12)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    private fun makeEdit(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#777777"))
        setTextColor(Color.WHITE)
        setSingleLine(true)
        background = roundBg(Color.parseColor("#1E1E1E"), 10, Color.parseColor("#333333"))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun makeLabel(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#AAAAAA"))
        textSize = 12f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun rowParams(weight: Float, endMarginDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginEnd = dp(endMarginDp)
        }

    private fun buildUi() {
        val accent = Color.parseColor("#3D7BFF")
        val danger = Color.parseColor("#8A3030")
        val neutral = Color.parseColor("#2A2A2A")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "YtKlip"
            setTextColor(Color.WHITE)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Videodan sadece istediğin kısmı indir"
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
        })

        // Link
        root.addView(makeLabel("Video linki"))
        urlInput = makeEdit("https://youtube.com/...")
        val pasteBtn = makeButton("Yapıştır", neutral)
        pasteBtn.setOnClickListener { pasteFromClipboard() }
        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlRow.addView(urlInput, rowParams(1f, 8))
        urlRow.addView(pasteBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(urlRow)

        // Zaman aralığı
        root.addView(makeLabel("Kesit (boş bırakırsan tüm video) — örn. 2:38:40"))
        startInput = makeEdit("Başlangıç")
        endInput = makeEdit("Bitiş")
        for (e in listOf(startInput, endInput)) {
            e.keyListener = DigitsKeyListener.getInstance("0123456789:.")
        }
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeRow.addView(startInput, rowParams(1f, 8))
        timeRow.addView(endInput, rowParams(1f))
        root.addView(timeRow)

        // Kalite
        root.addView(makeLabel("Kalite"))
        qualitySpinner = Spinner(this)
        qualitySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, QUALITIES
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        root.addView(qualitySpinner)

        exactCut = CheckBox(this).apply {
            text = "Tam kare kesim (daha yavaş, başlangıç daha doğru)"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        root.addView(exactCut)

        // İndir / İptal
        downloadBtn = makeButton("İndir", accent)
        cancelBtn = makeButton("İptal", danger)
        downloadBtn.setOnClickListener { startDownload() }
        cancelBtn.setOnClickListener { cancelDownload() }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(8))
        }
        actionRow.addView(downloadBtn, rowParams(2f, 8))
        actionRow.addView(cancelBtn, rowParams(1f))
        root.addView(actionRow)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progressBar)

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusView)

        // Güncelleme
        root.addView(makeLabel("yt-dlp (YouTube 403 verirse güncelle)"))
        nightly = CheckBox(this).apply {
            text = "Nightly sürümü (daha taze, daha riskli)"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        root.addView(nightly)
        updateBtn = makeButton("yt-dlp'yi güncelle", neutral)
        updateBtn.setOnClickListener { updateEngine() }
        root.addView(updateBtn)

        setBusy(false)
        downloadBtn.isEnabled = false
        updateBtn.isEnabled = false
    }

    private fun setStatus(s: String) {
        statusView.text = if (s.length > 700) s.takeLast(700) else s
    }

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    private fun setBusy(b: Boolean) {
        downloadBtn.isEnabled = !b && ready
        updateBtn.isEnabled = !b && ready
        cancelBtn.isEnabled = b
        if (b) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ----------------------------------------------------------- girdiler

    private fun extractUrl(s: String): String? = Regex("https?://\\S+").find(s)?.value

    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text) ?: return
        urlInput.setText(url)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        urlInput.setText(extractUrl(text) ?: text)
    }

    // ------------------------------------------------------------- motor

    private fun initEngine() {
        setStatus("Motor hazırlanıyor… (ilk açılışta biraz sürer)")
        io.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                ui {
                    setBusy(false)
                    setStatus("Hazır.")
                }
            } catch (e: Throwable) {
                ui { setStatus("Başlatma hatası: ${e.message}") }
            }
        }
    }

    private fun updateEngine() {
        if (!ready || busy) return
        busy = true
        setBusy(true)
        setStatus("yt-dlp güncelleniyor…")
        val channel: YoutubeDL.UpdateChannel =
            if (nightly.isChecked) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        io.execute {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, channel)
                ui { setStatus("Güncelleme sonucu: ${status ?: "bilinmiyor"}") }
            } catch (e: Throwable) {
                ui { setStatus("Güncelleme hatası: ${e.message}") }
            } finally {
                busy = false
                ui { setBusy(false) }
            }
        }
    }

    private fun cancelDownload() {
        Thread {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (e: Exception) {
                // yok say
            }
        }.start()
        setStatus("İptal ediliyor…")
    }

    private fun startDownload() {
        if (!ready || busy) return

        val url = extractUrl(urlInput.text.toString().trim())
        if (url == null) {
            setStatus("Geçerli bir link gir.")
            return
        }

var start = startInput.text.toString().trim().replace('.', ':')
var end = endInput.text.toString().trim().replace('.', ':')
        if (start.isNotEmpty() && !TIME_REGEX.matches(start)) {
            setStatus("Başlangıç zamanı hatalı. Örnek: 2:38:40 veya 90")
            return
        }
        if (end.isNotEmpty() && !TIME_REGEX.matches(end)) {
            setStatus("Bitiş zamanı hatalı. Örnek: 2:39:00 veya 120")
            return
        }
        val useSection = start.isNotEmpty() || end.isNotEmpty()
        if (useSection) {
            if (start.isEmpty()) start = "0"
            if (end.isEmpty()) end = "inf"
        }

        val qIndex = qualitySpinner.selectedItemPosition
        val height = HEIGHTS[qIndex]
        val audioOnly = height == 0
        val exact = exactCut.isChecked

        val workDir = File(cacheDir, "dl/${System.currentTimeMillis()}").also { it.mkdirs() }

        busy = true
        setBusy(true)
        progressBar.progress = 0
        setStatus("Başlıyor…")

        io.execute {
            try {
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
                    if (exact) request.addOption("--force-keyframes-at-cuts")
                }

                val callback: (Float, Long, String) -> Unit = { p, _, line ->
                    ui {
                        progressBar.progress = p.toInt().coerceIn(0, 100)
                        setStatus(line.trim())
                    }
                }
                YoutubeDL.getInstance().execute(request, PROCESS_ID, callback)

                val files = workDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in OUT_EXT }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (files.isEmpty()) {
                    throw IllegalStateException("Çıktı dosyası oluşmadı.")
                }
                for (f in files) saveToDownloads(f)

                ui {
                    progressBar.progress = 100
                    setStatus("Bitti ✓\nİndirilenler/YtKlip/\n" + files.joinToString("\n") { it.name })
                }
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                val hint = if (msg.contains("403")) {
                    "\n\nİpucu: aşağıdan yt-dlp'yi güncelle, olmazsa Nightly'yi dene."
                } else ""
                ui { setStatus("Hata: " + msg.takeLast(500) + hint) }
            } finally {
                workDir.deleteRecursively()
                busy = false
                ui { setBusy(false) }
            }
        }
    }

    private fun videoFormat(h: Int): String =
        "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b"

    // Dosyayı herkese açık İndirilenler/YtKlip klasörüne kopyalar (izin gerekmez, Android 10+).
    private fun saveToDownloads(file: File) {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
put(MediaStore.MediaColumns.RELATIVE_PATH, (if (mime.startsWith("video")) Environment.DIRECTORY_MOVIES else if (mime.startsWith("audio")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS) + "/YtKlip")            
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
val uri = resolver.insert(if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else if (mime.startsWith("audio")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)            
        ?: throw IllegalStateException("İndirilenler'de dosya oluşturulamadı.")
        val out = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Dosya yazılamadı.")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    companion object {
        private const val PROCESS_ID = "ytklip-download"
        private val QUALITIES = listOf("720p", "1080p", "480p", "360p", "Sadece ses (m4a)")
        private val HEIGHTS = listOf(720, 1080, 480, 360, 0)
        private val OUT_EXT = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "mov", "aac", "ogg")
        private val TIME_REGEX = Regex("^\\d+(:\\d{1,2}){0,2}(\\.\\d+)?\$")
    }
}
