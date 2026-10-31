package com.monkeybot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- helpers

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

enum class Mode { GAME, CLAIM, CONT, REWARD, UNKNOWN }

// ---------------------------------------------------------------- main screen

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        val title = TextView(this).apply { text = "Monkey Bot"; textSize = 26f }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 20) }
        val help = TextView(this).apply {
            textSize = 14f
            text = "Upar se 1, 2, 3 order mein dabao. Phir game kholo aur side mein dikhne wala START dabao."
            setPadding(0, 0, 0, 20)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(help)
        root.addView(btn("1) Overlay permission do") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2) Accessibility ON karo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("3) Bot shuru karo") { startBot() })
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val acc = BotAccessibilityService.instance != null
        status.text = "Overlay: " + (if (ov) "OK" else "NAHI") +
            "\nAccessibility: " + (if (acc) "OK" else "NAHI")
    }

    private fun startBot() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Pehle step 1 karo", Toast.LENGTH_LONG).show()
            return
        }
        if (BotAccessibilityService.instance == null) {
            Toast.makeText(this, "Pehle step 2 karo (Monkey Bot ko ON karo)", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Ab game kholo aur START dabao", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }
    }
}

// ---------------------------------------------------------------- taps and drags

class BotAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
    }

    private var stroke: GestureDescription.StrokeDescription? = null
    private var endX = 0f
    private var endY = 0f

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private suspend fun send(g: GestureDescription): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { c ->
            val started = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (c.isActive) c.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (c.isActive) c.resume(false)
                }
            }, null)
            if (!started && c.isActive) c.resume(false)
        }
    }

    /** Ek simple tap. */
    suspend fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        send(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build())
    }

    /** Ungli screen par rakhe rakhe x tak khiskao (bandar ko chalane ke liye). */
    suspend fun moveHold(x: Float, y: Float, dur: Long) {
        val s = stroke
        val p = Path()
        val newStroke: GestureDescription.StrokeDescription
        val newEnd: Float
        if (s == null) {
            p.moveTo(x, y)
            p.lineTo(x + 2f, y)
            newEnd = x + 2f
            newStroke = GestureDescription.StrokeDescription(p, 0, dur, true)
        } else {
            val toX = if (abs(x - endX) < 1f) x + 2f else x
            p.moveTo(endX, endY)
            p.lineTo(toX, y)
            newEnd = toX
            newStroke = s.continueStroke(p, 0, dur, true)
        }
        val ok = send(GestureDescription.Builder().addStroke(newStroke).build())
        if (ok) {
            stroke = newStroke
            endX = newEnd
            endY = y
        } else {
            stroke = null
        }
    }

    /** Ungli uthao. */
    suspend fun releaseHold() {
        val s = stroke ?: return
        stroke = null
        val p = Path()
        p.moveTo(endX, endY)
        p.lineTo(endX + 1f, endY)
        send(GestureDescription.Builder().addStroke(s.continueStroke(p, 0, 30, false)).build())
    }
}

// ---------------------------------------------------------------- vision

object Vision {
    class Hit(val text: String, val box: Rect)

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val hudRe = Regex("\\d+\\s*/\\s*\\d+")
    private val claimRe = Regex("\\bclaim\\b")

    suspend fun ocr(bmp: Bitmap): List<Hit> {
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).awaitResult()
        val out = ArrayList<Hit>()
        for (b in res.textBlocks) {
            for (l in b.lines) {
                val r = l.boundingBox ?: continue
                out.add(Hit(l.text, r))
            }
        }
        return out
    }

    /** Screen kaunsi hai: game, claim popup, continue popup, ya ad (reward granted). */
    fun classify(hits: List<Hit>, h: Int): Pair<Mode, Rect?> {
        val reward = hits.firstOrNull { it.text.lowercase().contains("granted") }
        if (reward != null) return Pair(Mode.REWARD, reward.box)

        val claim = hits.firstOrNull { claimRe.containsMatchIn(it.text.lowercase()) }
        val cancel = hits.any { it.text.lowercase().contains("cancel") }
        if (claim != null && cancel) return Pair(Mode.CLAIM, claim.box)

        val cont = hits.firstOrNull { it.text.lowercase().trim() == "continue" }
        val extra = hits.any {
            val t = it.text.lowercase()
            t.contains("claimed") || t.contains("no thanks")
        }
        if (cont != null && extra) return Pair(Mode.CONT, cont.box)

        if (hits.any { it.box.centerY() < h * 0.18f && hudRe.containsMatchIn(it.text) }) {
            return Pair(Mode.GAME, null)
        }
        return Pair(Mode.UNKNOWN, null)
    }
}

/** Girti hui cheezein pakadta hai (do frames ka farq). Kaali cheez = bomb, baaki = fruit. */
class MotionDetector(private val w: Int, private val h: Int) {
    class Blob(val cx: Float, val cy: Float, val dark: Boolean)

    private val step = 6
    private val gw = w / step
    private val gh = h / step
    private var prev: IntArray? = null
    private val px = IntArray(w * h)

    fun reset() {
        prev = null
    }

    fun analyze(bmp: Bitmap): List<Blob> {
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(gw * gh)
        val darkMap = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val c = px[(gy * step + step / 2) * w + gx * step + step / 2]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                gray[gy * gw + gx] = (r * 30 + g * 59 + b * 11) / 100
                darkMap[gy * gw + gx] = max(r, max(g, b)) < 75
            }
        }
        val p = prev
        prev = gray
        if (p == null) return emptyList()

        val y0 = (gh * 0.12f).toInt()
        val y1 = (gh * 0.72f).toInt()
        val mv = BooleanArray(gw * gh)
        var total = 0
        for (gy in y0 until y1) {
            for (gx in 0 until gw) {
                val i = gy * gw + gx
                if (abs(gray[i] - p[i]) > 30) {
                    mv[i] = true
                    total++
                }
            }
        }
        if (total > gw * (y1 - y0) * 0.35f) return emptyList()

        val dil = BooleanArray(gw * gh)
        for (gy in y0 until y1) {
            for (gx in 0 until gw) {
                if (!mv[gy * gw + gx]) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val yy = gy + dy
                        val xx = gx + dx
                        if (yy in y0 until y1 && xx in 0 until gw) dil[yy * gw + xx] = true
                    }
                }
            }
        }

        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val out = ArrayList<Blob>()
        for (s in dil.indices) {
            if (!dil[s] || seen[s]) continue
            var sp = 0
            stack[sp++] = s
            seen[s] = true
            var minX = gw
            var maxX = -1
            var minY = gh
            var maxY = -1
            var cells = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % gw
                val y = i / gw
                cells++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= gw || ny < y0 || ny >= y1) continue
                        val j = ny * gw + nx
                        if (dil[j] && !seen[j]) {
                            seen[j] = true
                            stack[sp++] = j
                        }
                    }
                }
            }
            if (cells < 12) continue
            var d = 0
            var n = 0
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    n++
                    if (darkMap[y * gw + x]) d++
                }
            }
            val dark = d.toFloat() / n > 0.22f
            out.add(Blob(((minX + maxX + 1) / 2f) * step, (maxY + 1f) * step, dark))
        }
        return out
    }
}

// ---------------------------------------------------------------- the bot

class BotService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: View? = null
    private var wm: WindowManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var paused = true
    private var realW = 0
    private var realH = 0
    private var capW = 0
    private var capH = 0
    private var k = 2f
    private var lastFrame: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        setupCapture(proj)
        showOverlay()
        scope.launch { runLoop() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("bot", "Monkey Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot")
            .setContentTitle("Monkey Bot chal raha hai")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupCapture(proj: MediaProjection) {
        val dm = DisplayMetrics()
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        @Suppress("DEPRECATION")
        w.defaultDisplay.getRealMetrics(dm)
        realW = dm.widthPixels
        realH = dm.heightPixels
        capW = realW / 2
        capH = realH / 2
        k = realW.toFloat() / capW
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "cap", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return lastFrame
        try {
            val p = img.planes[0]
            val rowPixels = p.rowStride / p.pixelStride
            val full = Bitmap.createBitmap(rowPixels, capH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val bmp = if (rowPixels != capW) Bitmap.createBitmap(full, 0, 0, capW, capH) else full
            lastFrame = bmp
            return bmp
        } finally {
            img.close()
        }
    }

    private fun showOverlay() {
        val d = resources.displayMetrics.density
        fun pill(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 40 * d
        }
        val green = Color.parseColor("#E62E7D32")
        val orange = Color.parseColor("#E6E65100")
        val red = Color.parseColor("#E6B71C1C")
        val padH = (14 * d).toInt()
        val padV = (8 * d).toInt()

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val toggle = TextView(this).apply {
            text = "\u25B6 START"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            background = pill(green)
        }
        val quit = TextView(this).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            background = pill(red)
        }
        toggle.setOnClickListener {
            paused = !paused
            toggle.text = if (paused) "\u25B6 START" else "\u25A0 STOP"
            toggle.background = pill(if (paused) green else orange)
            if (paused) {
                scope.launch { BotAccessibilityService.instance?.releaseHold() }
            }
        }
        quit.setOnClickListener { stopSelf() }
        row.addView(toggle)
        row.addView(quit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = (8 * d).toInt() })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = (realH * 0.35f).toInt()
        wm?.addView(row, lp)
        overlay = row
    }

    private suspend fun runLoop() {
        val det = MotionDetector(capW, capH)
        var mode = Mode.GAME
        var box: Rect? = null
        var lastOcr = 0L
        var lastGameSeen = SystemClock.elapsedRealtime()
        var unknownSince = 0L
        var lastClaim = 0L
        var lastTap = 0L
        var tries = 0
        var curX = capW / 2f
        var wasPaused = true
        var lastPlayed: Bitmap? = null

        while (currentCoroutineContext().isActive) {
            if (paused) {
                grab()
                wasPaused = true
                delay(200)
                continue
            }
            val acc = BotAccessibilityService.instance
            if (acc == null) {
                delay(300)
                continue
            }
            val bmp = grab()
            if (bmp == null) {
                delay(50)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (wasPaused) {
                wasPaused = false
                mode = Mode.GAME
                lastGameSeen = now
                lastOcr = now
                unknownSince = 0L
                det.reset()
                curX = capW / 2f
            }

            var fresh = false
            if (now - lastOcr > (if (mode == Mode.GAME) 1500L else 900L)) {
                lastOcr = now
                val hits = try {
                    Vision.ocr(bmp)
                } catch (e: Exception) {
                    emptyList()
                }
                val r = Vision.classify(hits, capH)
                mode = r.first
                box = r.second
                fresh = true
            }

            when (mode) {
                Mode.CLAIM -> if (fresh && box != null) {
                    acc.releaseHold()
                    acc.tap(box.centerX() * k, box.centerY() * k)
                    lastClaim = now
                    lastTap = now
                    mode = Mode.UNKNOWN
                    unknownSince = now
                }

                Mode.CONT -> if (fresh && box != null) {
                    acc.releaseHold()
                    acc.tap(box.centerX() * k, box.centerY() * k)
                    lastTap = now
                }

                Mode.REWARD -> if (fresh && box != null && now - lastTap > 1200) {
                    acc.releaseHold()
                    val rx = if (tries % 2 == 0) {
                        min(box.right + capW * 0.045f, capW * 0.945f)
                    } else {
                        max(box.left - capW * 0.045f, capW * 0.055f)
                    }
                    acc.tap(rx * k, box.centerY() * k)
                    tries++
                    lastTap = now
                }

                Mode.GAME -> {
                    lastGameSeen = now
                    unknownSince = 0L
                    if (bmp !== lastPlayed) {
                        lastPlayed = bmp
                        curX = playStep(acc, bmp, det, curX)
                    }
                }

                Mode.UNKNOWN -> {
                    if (unknownSince == 0L) unknownSince = now
                    val inAd = now - lastClaim < 120_000L
                    if (inAd) {
                        if (fresh && now - unknownSince > 12_000L && now - lastTap > 3_000L) {
                            val cx = if (tries % 2 == 0) realW * 0.94f else realW * 0.06f
                            acc.tap(cx, realH * 0.055f)
                            tries++
                            lastTap = now
                        }
                    } else if (now - lastGameSeen < 6_000L && bmp !== lastPlayed) {
                        lastPlayed = bmp
                        curX = playStep(acc, bmp, det, curX)
                    }
                }
            }
            delay(if (mode == Mode.GAME) 20L else 100L)
        }
    }

    private suspend fun playStep(
        acc: BotAccessibilityService, bmp: Bitmap, det: MotionDetector, curX: Float
    ): Float {
        val blobs = det.analyze(bmp)
        val bombs = blobs.filter { it.dark }
        val fruits = blobs.filter { !it.dark && it.cy > capH * 0.20f }.sortedByDescending { it.cy }
        val safe = capW * 0.16f

        fun threat(x: Float, minY: Float) = bombs.any { it.cy >= minY && abs(it.cx - x) < safe }

        var target = curX
        val pick = fruits.firstOrNull { !threat(it.cx, it.cy - capH * 0.05f) }
        if (pick != null) {
            target = pick.cx
        } else if (threat(curX, capH * 0.25f)) {
            val xs = (1..9).map { capW * it / 10f }.sortedBy { abs(it - curX) }
            target = xs.firstOrNull { !threat(it, capH * 0.25f) } ?: curX
        }
        target = max(capW * 0.05f, min(capW * 0.95f, target))

        val dist = abs(target - curX)
        val dur = (50 + dist / capW * 150).toLong()
        acc.moveHold(target * k, realH * 0.80f, dur)
        return target
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { overlay?.let { wm?.removeView(it) } }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        CoroutineScope(Dispatchers.Default).launch { BotAccessibilityService.instance?.releaseHold() }
        super.onDestroy()
    }
}
