package com.yusuf.ytklip

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.method.DigitsKeyListener
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var ready = false
    @Volatile private var busy = false
    @Volatile private var cancelled = false

    // ---- renkler (siyah + mavi)
    private val cBg1 = Color.parseColor("#05080F")
    private val cBg2 = Color.parseColor("#0A1426")
    private val cCard = Color.parseColor("#0E1626")
    private val cStroke = Color.parseColor("#1B2A47")
    private val cInput = Color.parseColor("#091020")
    private val cAccent = Color.parseColor("#2F6BFF")
    private val cAccent2 = Color.parseColor("#4DA3FF")
    private val cText = Color.parseColor("#F1F5FF")
    private val cMuted = Color.parseColor("#8A9BBE")
    private val cHint = Color.parseColor("#55668A")
    private val cDanger = Color.parseColor("#E5566A")
    private val cOk = Color.parseColor("#3DDC97")

    // ---- yazı tipleri
    private val fBlack = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val fMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val fLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val fRegular = Typeface.create("sans-serif", Typeface.NORMAL)

    private lateinit var urlInput: EditText
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var exactCut: CheckBox
    private lateinit var nightly: CheckBox
    private lateinit var downloadBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var stageView: TextView
    private lateinit var percentView: TextView
    private lateinit var statsView: TextView
    private lateinit var messageView: TextView

    private val chips = mutableListOf<TextView>()
    private var qualityIndex = 2

    // ---- ilerleme durumu
    private var dlPasses = 1
    private var dlDestCount = 0
    private var dlClipSec = 0.0
    private var dlOverall = 0.0
    private var dlBytes = 0L
    private var dlSpeed: String? = null
    private var dlEta: String? = null
    private var pollDir: File? = null

    // inen dosyanın boyutunu düzenli ölçer (yüzde gelmese bile bir şeyler aktığını gösterir)
    private val poller = object : Runnable {
        override fun run() {
            val dir = pollDir
            if (dir == null || !busy) return
            dlBytes = dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            renderStats()
            mainHandler.postDelayed(this, 700)
        }
    }

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

    // ============================================================ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun gradientBg(radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccent2)).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun ripple(content: Drawable, radiusDp: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            content,
            roundBg(Color.WHITE, radiusDp)
        )

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(cCard, 18, cStroke)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun makeSectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(cAccent2)
        textSize = 11f
        typeface = fMedium
        letterSpacing = 0.14f
        setPadding(0, 0, 0, dp(10))
    }

    private fun makeNote(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(cMuted)
        textSize = 12.5f
        typeface = fRegular
        setPadding(0, 0, 0, dp(10))
    }

    private fun makeButton(label: String, kind: Int): Button {
        val (fg, bg) = when (kind) {
            BTN_PRIMARY -> Pair(Color.WHITE, gradientBg(14))
            BTN_DANGER -> Pair(cDanger, roundBg(Color.parseColor("#1A0F16"), 14, Color.parseColor("#5A2430")))
            else -> Pair(cText, roundBg(Color.parseColor("#14203A"), 14, cStroke))
        }
        return Button(this).apply {
            text = label
            isAllCaps = false
            typeface = fMedium
            textSize = 15f
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setTextColor(fg)
            background = ripple(bg, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
    }

    private fun makeEdit(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(cHint)
        setTextColor(cText)
        textSize = 16f
        typeface = fMedium
        setSingleLine(true)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), roundBg(cInput, 12, cAccent))
            addState(intArrayOf(), roundBg(cInput, 12, cStroke))
        }
        setPadding(dp(14), dp(13), dp(14), dp(13))
    }

    private fun makeCheck(label: String): CheckBox = CheckBox(this).apply {
        text = label
        setTextColor(cMuted)
        textSize = 13.5f
        typeface = fRegular
        buttonTintList = ColorStateList.valueOf(cAccent2)
        setPadding(dp(8), dp(6), 0, dp(6))
    }

    private fun rowParams(weight: Float, endMarginDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginEnd = dp(endMarginDp)
        }

    private fun buildChips(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        QUALITIES.forEachIndexed { i, label ->
            val chip = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13.5f
                typeface = fMedium
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    qualityIndex = i
                    refreshChips()
                }
            }
            chips.add(chip)
            row.addView(chip, rowParams(1f, if (i < QUALITIES.size - 1) 6 else 0))
        }
        refreshChips()
        return row
    }

    private fun refreshChips() {
        chips.forEachIndexed { i, c ->
            if (i == qualityIndex) {
                c.setTextColor(Color.WHITE)
                c.background = gradientBg(12)
            } else {
                c.setTextColor(cMuted)
                c.background = roundBg(cInput, 12, cStroke)
            }
        }
    }

    private fun buildUi() {
        window.statusBarColor = cBg1
        window.navigationBarColor = cBg1

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cBg2, cBg1))
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        setContentView(scroll)

        // Başlık
        root.addView(TextView(this).apply {
            text = SpannableString("YtKlip").apply {
                setSpan(ForegroundColorSpan(cAccent2), 2, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextColor(cText)
            textSize = 34f
            typeface = fBlack
            letterSpacing = -0.02f
        })
        root.addView(TextView(this).apply {
            text = "Videodan sadece istediğin kısmı indir"
            setTextColor(cMuted)
            textSize = 14f
            typeface = fLight
            setPadding(0, dp(2), 0, dp(18))
        })

        // Video linki
        val linkCard = makeCard()
        linkCard.addView(makeSectionTitle("VİDEO LİNKİ"))
        urlInput = makeEdit("https://youtube.com/...")
        val pasteBtn = makeButton("Yapıştır", BTN_NEUTRAL)
        pasteBtn.setOnClickListener { pasteFromClipboard() }
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlRow.addView(urlInput, rowParams(1f, 8))
        urlRow.addView(pasteBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        linkCard.addView(urlRow)
        root.addView(linkCard)

        // Kesit
        val cutCard = makeCard()
        cutCard.addView(makeSectionTitle("KESİT"))
        cutCard.addView(makeNote("Boş bırakırsan tüm video iner. Örnek: 2.38.20 veya 2:38:20"))
        startInput = makeEdit("Başlangıç")
        endInput = makeEdit("Bitiş")
        for (e in listOf(startInput, endInput)) {
            e.keyListener = DigitsKeyListener.getInstance("0123456789:., ")
        }
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeRow.addView(startInput, rowParams(1f, 8))
        timeRow.addView(endInput, rowParams(1f))
        cutCard.addView(timeRow)
        exactCut = makeCheck("Tam kare kesim (daha yavaş, başlangıç daha doğru)")
        cutCard.addView(exactCut)
        root.addView(cutCard)

        // Kalite
        val qualityCard = makeCard()
        qualityCard.addView(makeSectionTitle("KALİTE"))
        qualityCard.addView(buildChips())
        root.addView(qualityCard)

        // İndir / İptal
        downloadBtn = makeButton("İndir", BTN_PRIMARY)
        cancelBtn = makeButton("İptal", BTN_DANGER)
        downloadBtn.setOnClickListener { startDownload() }
        cancelBtn.setOnClickListener { cancelDownload() }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(14))
        }
        actionRow.addView(downloadBtn, rowParams(2f, 10))
        actionRow.addView(cancelBtn, rowParams(1f))
        root.addView(actionRow)

        // İlerleme
        val progressCard = makeCard()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stageView = TextView(this).apply {
            text = "Hazırlanıyor…"
            setTextColor(cMuted)
            textSize = 14f
            typeface = fMedium
        }
        percentView = TextView(this).apply {
            text = "0%"
            setTextColor(cText)
            textSize = 36f
            typeface = fBlack
        }
        head.addView(stageView, rowParams(1f))
        head.addView(percentView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progressCard.addView(head)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            val track = roundBg(Color.parseColor("#16213A"), 5)
            val fill = ClipDrawable(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccent2))
                    .apply { cornerRadius = dp(5).toFloat() },
                Gravity.START, ClipDrawable.HORIZONTAL
            )
            val layers = LayerDrawable(arrayOf<Drawable>(track, fill))
            layers.setId(0, android.R.id.background)
            layers.setId(1, android.R.id.progress)
            progressDrawable = layers
            indeterminateTintList = ColorStateList.valueOf(cAccent2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
            ).apply { topMargin = dp(8) }
        }
        progressCard.addView(progressBar)

        statsView = TextView(this).apply {
            setTextColor(cMuted)
            textSize = 12.5f
            typeface = fMedium
            setPadding(0, dp(10), 0, 0)
        }
        progressCard.addView(statsView)

        messageView = TextView(this).apply {
            setTextColor(cMuted)
            textSize = 12.5f
            typeface = fRegular
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        progressCard.addView(messageView)
        root.addView(progressCard)

        // yt-dlp güncelleme
        val updateCard = makeCard()
        updateCard.addView(makeSectionTitle("YT-DLP MOTORU"))
        updateCard.addView(makeNote("YouTube 403 hatası verirse güncelle."))
        nightly = makeCheck("Nightly sürümü (daha taze, daha riskli)")
        updateCard.addView(nightly)
        updateBtn = makeButton("yt-dlp'yi güncelle", BTN_NEUTRAL)
        updateBtn.setOnClickListener { updateEngine() }
        updateCard.addView(updateBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        root.addView(updateCard)

        setBusy(false)
    }

    // ------------------------------------------------ ekran güncelleme

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    private fun setBusy(b: Boolean) {
        enable(downloadBtn, !b && ready)
        enable(updateBtn, !b && ready)
        enable(cancelBtn, b)
        if (b) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setStage(s: String, color: Int = cMuted) {
        stageView.text = s
        stageView.setTextColor(color)
    }

    private fun setPercent(p: Double) {
        val v = p.coerceIn(0.0, 100.0)
        progressBar.isIndeterminate = false
        progressBar.progress = (v * 10).toInt()
        percentView.text = "${v.toInt()}%"
    }

    private fun setIndeterminate() {
        progressBar.isIndeterminate = true
        percentView.text = "…"
    }

    private fun showMessage(text: String, color: Int = cMuted) {
        messageView.setTextColor(color)
        messageView.text = text
        messageView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderStats() {
        val parts = mutableListOf<String>()
        if (dlBytes > 0) parts.add(formatBytes(dlBytes))
        dlSpeed?.let { parts.add(it) }
        dlEta?.let { if (it != "Unknown") parts.add("kalan $it") }
        statsView.text = parts.joinToString("   ·   ")
    }

    private fun formatBytes(b: Long): String = when {
        b >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= (1L shl 10) -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    // ============================================================ girdiler

    private fun extractUrl(s: String): String? = Regex("https?://\\S+").find(s)?.value

    // 2.38.20 / 2,38,20 / 2 38 20 -> 2:38:20
    private fun normalizeTime(s: String): String =
        s.trim().replace('.', ':').replace(',', ':').replace(' ', ':')

    private fun toSeconds(t: String): Double {
        var total = 0.0
        for (part in t.split(":")) total = total * 60 + (part.toDoubleOrNull() ?: 0.0)
        return total
    }

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

    // ============================================================ motor

    private fun initEngine() {
        setStage("Motor hazırlanıyor…")
        setIndeterminate()
        showMessage("İlk açılışta biraz sürebilir.")
        io.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                ui {
                    setBusy(false)
                    setStage("Hazır")
                    setPercent(0.0)
                    showMessage("")
                }
            } catch (e: Throwable) {
                ui {
                    setStage("Başlatma hatası", cDanger)
                    progressBar.isIndeterminate = false
                    showMessage(e.message ?: e.toString(), cDanger)
                }
            }
        }
    }

    private fun updateEngine() {
        if (!ready || busy) return
        busy = true
        setBusy(true)
        setStage("yt-dlp güncelleniyor…")
        setIndeterminate()
        statsView.text = ""
        showMessage("")
        val channel: YoutubeDL.UpdateChannel =
            if (nightly.isChecked) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
        io.execute {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, channel)
                ui { showMessage("Güncelleme sonucu: ${status ?: "bilinmiyor"}", cOk) }
            } catch (e: Throwable) {
                ui { showMessage("Güncelleme hatası: ${e.message}", cDanger) }
            } finally {
                busy = false
                ui {
                    setPercent(0.0)
                    setStage("Hazır")
                    setBusy(false)
                }
            }
        }
    }

    private fun cancelDownload() {
        cancelled = true
        Thread {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (e: Exception) {
                // yok say
            }
        }.start()
        setStage("İptal ediliyor…")
    }

    // yt-dlp / ffmpeg çıktısındaki her satırı yorumlar
    private fun handleLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || !busy) return

        FORMATS_RE.find(line)?.let {
            dlPasses = it.groupValues[1].split("+").size.coerceAtLeast(1)
        }
        if (line.contains("Destination:")) dlDestCount++

        if (MERGE_MARKERS.any { line.contains(it) }) {
            setStage("Birleştiriliyor…")
            if (dlOverall < 99.0) dlOverall = 99.0
            setPercent(dlOverall)
            return
        }

        var frac = -1.0
        if (line.startsWith("[download]")) {
            DL_PERCENT.find(line)?.let { frac = it.groupValues[1].toDouble() / 100.0 }
            DL_SPEED.find(line)?.let { dlSpeed = it.groupValues[1] }
            DL_ETA.find(line)?.let { dlEta = it.groupValues[1] }
        } else if (dlClipSec > 0) {
            OUT_TIME.find(line)?.let {
                frac = (toSeconds(it.groupValues[1]) / dlClipSec).coerceIn(0.0, 1.0)
            }
        }

        if (frac >= 0) {
            val idx = (dlDestCount - 1).coerceIn(0, dlPasses - 1)
            val overall = (idx + frac) / dlPasses * 100.0
            if (overall > dlOverall) dlOverall = overall
            setStage("İndiriliyor")
            setPercent(dlOverall)
        }
        renderStats()
    }

    private fun startDownload() {
        if (!ready || busy) return

        val url = extractUrl(urlInput.text.toString().trim())
        if (url == null) {
            showMessage("Geçerli bir link gir.", cDanger)
            return
        }

        var start = normalizeTime(startInput.text.toString())
        var end = normalizeTime(endInput.text.toString())
        if (start.isNotEmpty() && !TIME_REGEX.matches(start)) {
            showMessage("Başlangıç zamanı hatalı. Örnek: 2.38.20 veya 90", cDanger)
            return
        }
        if (end.isNotEmpty() && !TIME_REGEX.matches(end)) {
            showMessage("Bitiş zamanı hatalı. Örnek: 2.39.20 veya 120", cDanger)
            return
        }
        val useSection = start.isNotEmpty() || end.isNotEmpty()
        if (useSection) {
            if (start.isEmpty()) start = "0"
            if (end.isEmpty()) end = "inf"
        }

        var clipSec = 0.0
        if (useSection && end != "inf") {
            clipSec = toSeconds(end) - toSeconds(start)
            if (clipSec <= 0) {
                showMessage("Bitiş zamanı başlangıçtan sonra olmalı.", cDanger)
                return
            }
        }

        val height = HEIGHTS[qualityIndex]
        val audioOnly = height == 0
        val exact = exactCut.isChecked
        val sStart = start
        val sEnd = end

        val workDir = File(cacheDir, "dl/${System.currentTimeMillis()}").also { it.mkdirs() }

        dlPasses = 1
        dlDestCount = 0
        dlClipSec = clipSec
        dlOverall = 0.0
        dlBytes = 0L
        dlSpeed = null
        dlEta = null
        cancelled = false

        busy = true
        setBusy(true)
        setStage("Başlıyor…")
        setIndeterminate()
        statsView.text = ""
        showMessage("")
        pollDir = workDir
        mainHandler.removeCallbacks(poller)
        mainHandler.post(poller)

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
                    request.addOption("--download-sections", "*$sStart-$sEnd")
                    // ffmpeg'in ilerleme bilgisini yt-dlp çıktısına geçirir
                    request.addOption("--downloader-args", "ffmpeg:-progress pipe:1")
                    if (exact) request.addOption("--force-keyframes-at-cuts")
                }

                val callback: (Float, Long, String) -> Unit = { _, _, line ->
                    ui { handleLine(line) }
                }
                YoutubeDL.getInstance().execute(request, PROCESS_ID, callback)

                val files = workDir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in OUT_EXT }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                if (files.isEmpty()) {
                    throw IllegalStateException("Çıktı dosyası oluşmadı.")
                }
                ui { setStage("Kaydediliyor…") }
                val folders = files.map { saveToMedia(it) }.distinct()

                ui {
                    dlOverall = 100.0
                    setPercent(100.0)
                    setStage("Tamamlandı", cOk)
                    statsView.text = ""
                    showMessage(
                        "Kaydedildi: " + folders.joinToString(", ") + "\n" +
                            files.joinToString("\n") { it.name },
                        cOk
                    )
                }
            } catch (e: Throwable) {
                if (cancelled) {
                    ui {
                        progressBar.isIndeterminate = false
                        setStage("İptal edildi")
                        showMessage("İndirme iptal edildi.")
                    }
                } else {
                    val msg = e.message ?: e.toString()
                    val hint = if (msg.contains("403")) {
                        "\n\nİpucu: aşağıdan yt-dlp'yi güncelle, olmazsa Nightly'yi dene."
                    } else ""
                    ui {
                        progressBar.isIndeterminate = false
                        setStage("Hata", cDanger)
                        showMessage(msg.takeLast(500) + hint, cDanger)
                    }
                }
            } finally {
                workDir.deleteRecursively()
                busy = false
                ui {
                    pollDir = null
                    mainHandler.removeCallbacks(poller)
                    setBusy(false)
                }
            }
        }
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

    companion object {
        private const val PROCESS_ID = "ytklip-download"
        private const val BTN_PRIMARY = 0
        private const val BTN_NEUTRAL = 1
        private const val BTN_DANGER = 2
        private val QUALITIES = listOf("360p", "480p", "720p", "1080p", "Ses")
        private val HEIGHTS = listOf(360, 480, 720, 1080, 0)
        private val OUT_EXT = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "mov", "aac", "ogg")
        private val TIME_REGEX = Regex("^\\d+(:\\d{1,2}){0,2}(\\.\\d+)?\$")
        private val MERGE_MARKERS = listOf("[Merger]", "[VideoRemuxer]", "[ExtractAudio]", "[Fixup")
        private val DL_PERCENT = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
        private val DL_SPEED = Regex("""\bat\s+(\S+/s)""")
        private val DL_ETA = Regex("""ETA\s+(\S+)""")
        private val OUT_TIME = Regex("""^out_time=(\d+:\d+:\d+(?:\.\d+)?)""")
        private val FORMATS_RE = Regex("""Downloading \d+ format\(s\):\s*(\S+)""")
    }
}
