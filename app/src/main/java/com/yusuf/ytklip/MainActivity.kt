package com.yusuf.ytklip

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.DigitsKeyListener
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var ready = false
    @Volatile private var updating = false   // yt-dlp güncellenirken true

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

    // Uygulamaya dönünce servisin son durumunu göster, ayrılırken dinlemeyi bırak.
    // (İndirme servis üzerinde devam ettiği için ekran kapansa da durmaz.)
    override fun onStart() {
        super.onStart()
        DownloadState.listener = { s -> runOnUiThread { applySnapshot(s) } }
        DownloadState.snapshot?.let { applySnapshot(it) }
    }

    override fun onStop() {
        DownloadState.listener = null
        super.onStop()
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

        // Başlık (logo + isim)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoId = resources.getIdentifier("logo_mark", "drawable", packageName)
        if (logoId != 0) {
            titleRow.addView(
                ImageView(this).apply {
                    setImageResource(logoId)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
                ).apply { marginEnd = dp(10) }
            )
        }
        titleRow.addView(TextView(this).apply {
            text = SpannableString("YtKlip").apply {
                setSpan(ForegroundColorSpan(cAccent2), 2, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextColor(cText)
            textSize = 34f
            typeface = fBlack
            letterSpacing = -0.02f
        })
        root.addView(titleRow)
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

        refreshButtons()
    }

    // ------------------------------------------------ ekran güncelleme

    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    private fun refreshButtons() {
        val downloading = DownloadState.busy
        enable(downloadBtn, ready && !downloading && !updating)
        enable(updateBtn, ready && !downloading && !updating)
        enable(cancelBtn, downloading)
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

    private fun toneColor(t: Tone): Int = when (t) {
        Tone.OK -> cOk
        Tone.ERROR -> cDanger
        else -> cMuted
    }

    // Servisten gelen durumu ekrana yansıtır
    private fun applySnapshot(s: DlSnapshot) {
        setStage(s.stage, toneColor(s.stageTone))
        if (s.indeterminate) setIndeterminate() else setPercent(s.percent)
        statsView.text = s.stats
        showMessage(s.message, toneColor(s.messageTone))
        refreshButtons()
    }

    private fun showIdleOrSnapshot() {
        val s = DownloadState.snapshot
        if (s != null) {
            applySnapshot(s)
        } else {
            setStage("Hazır")
            setPercent(0.0)
            statsView.text = ""
            showMessage("")
            refreshButtons()
        }
    }

    // ============================================================ girdiler

    private fun extractUrl(s: String): String? = Regex("https?://\\S+").find(s)?.value

    // 2.38.20 / 2,38,20 / 2 38 20 -> 2:38:20
    private fun normalizeTime(s: String): String =
        s.trim().replace('.', ':').replace(',', ':').replace(' ', ':')

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
        // Ekran yeniden oluşturulduysa (döndürme vb.) motor zaten hazırdır
        if (engineReady) {
            ready = true
            showIdleOrSnapshot()
            return
        }
        setStage("Motor hazırlanıyor…")
        setIndeterminate()
        showMessage("İlk açılışta biraz sürebilir.")
        io.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                engineReady = true
                ready = true
                ui { showIdleOrSnapshot() }
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
        if (!ready || updating || DownloadState.busy) return
        updating = true
        refreshButtons()
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
                updating = false
                ui {
                    setPercent(0.0)
                    setStage("Hazır")
                    refreshButtons()
                }
            }
        }
    }

    // ============================================================ indirme (servis üzerinden)

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    private fun startDownload() {
        if (!ready || updating || DownloadState.busy) return

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
            clipSec = timeToSeconds(end) - timeToSeconds(start)
            if (clipSec <= 0) {
                showMessage("Bitiş zamanı başlangıçtan sonra olmalı.", cDanger)
                return
            }
        }

        // Bildirim izni (Android 13+). Verilmese de indirme çalışır, sadece bildirim görünmez.
        askNotificationPermission()

        // Ekran hemen tepki versin, çift dokunuş da engellensin
        DownloadState.update(DlSnapshot(busy = true, stage = "Başlıyor…", indeterminate = true))

        val i = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_USE_SECTION, useSection)
            putExtra(DownloadService.EXTRA_START, start)
            putExtra(DownloadService.EXTRA_END, end)
            putExtra(DownloadService.EXTRA_CLIP_SEC, clipSec)
            putExtra(DownloadService.EXTRA_HEIGHT, HEIGHTS[qualityIndex])
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            DownloadState.update(
                DlSnapshot(
                    busy = false,
                    stage = "Hata",
                    stageTone = Tone.ERROR,
                    message = e.message ?: e.toString(),
                    messageTone = Tone.ERROR
                )
            )
        }
    }

    private fun cancelDownload() {
        startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
    }

    companion object {
        // Ekran yeniden oluşturulsa bile motoru tekrar başlatmamak için
        @Volatile private var engineReady = false

        private const val BTN_PRIMARY = 0
        private const val BTN_NEUTRAL = 1
        private const val BTN_DANGER = 2
        private val QUALITIES = listOf("360p", "480p", "720p", "1080p", "Ses")
        private val HEIGHTS = listOf(360, 480, 720, 1080, 0)
        private val TIME_REGEX = Regex("^\\d+(:\\d{1,2}){0,2}(\\.\\d+)?\$")
    }
}
