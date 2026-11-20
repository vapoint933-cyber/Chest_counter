package com.chestcounter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class OverlayService : Service() {

    companion object {
        @Volatile var instance: OverlayService? = null
        const val ACTION_START = "cc.start"
        const val ACTION_PLUS = "cc.plus"
        const val ACTION_MINUS = "cc.minus"
        const val ACTION_STOP = "cc.stop"
        private const val CHANNEL = "cc_channel"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var hud: HudView? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var bubble: View? = null

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var bg: Handler? = null

    @Volatile private var captureNext = false
    private var template: Template? = null
    private var templateStamp = 0L
    private var lastProc = 0L
    private var seen = 0
    private var latched = false
    private var lastVisible = 0L
    private var lastCount = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chest Counter", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLUS -> { Store.add(1); refresh(); return START_NOT_STICKY }
            ACTION_MINUS -> { Store.add(-1); refresh(); return START_NOT_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        // обычный старт
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        val withProjection = data != null

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (withProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        Store.resetSession()
        if (hud == null) addOverlay()
        if (withProjection && projection == null) startCapture(code, data!!)
        ui.removeCallbacks(ticker)
        ui.post(ticker)
        return START_NOT_STICKY
    }

    // ---------------- уведомление (кнопки +1 / -1 работают поверх игры) ----------------
    private fun pi(action: String, code: Int): PendingIntent {
        val i = Intent(this, OverlayService::class.java).setAction(action)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(): Notification {
        val icon = Icon.createWithResource(this, android.R.drawable.btn_star_big_on)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.btn_star_big_on)
            .setContentTitle("Chest Counter")
            .setContentText("Всего: ${Store.total}  ·  Сессия: ${Store.session}")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "+1", pi(ACTION_PLUS, 1)).build())
            .addAction(Notification.Action.Builder(icon, "-1", pi(ACTION_MINUS, 2)).build())
            .addAction(Notification.Action.Builder(icon, "Стоп", pi(ACTION_STOP, 3)).build())
            .build()
    }

    fun refresh() {
        hud?.invalidate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    // ---------------- оверлей ----------------
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun addOverlay() {
        val v = HudView(this)
        val p = WindowManager.LayoutParams(
            100, 100,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        hud = v
        hudParams = p
        wm.addView(v, p)
        applyLayout()
    }

    /** Размер и позиция HUD по настройкам. */
    fun applyLayout() {
        val v = hud ?: return
        val p = hudParams ?: return
        val dm = realMetrics()
        var w = (dm.widthPixels * Store.sizePct / 100f).toInt()
        if (w < 100) w = 100
        val h = (w * 500f / 1090f).toInt()
        p.width = w
        p.height = h
        p.x = ((dm.widthPixels - w) * Store.posX / 100f).toInt()
        p.y = ((dm.heightPixels - h) * Store.posY / 100f).toInt()
        v.scale = w / 1090f
        wm.updateViewLayout(v, p)
        v.invalidate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    // ---------------- захват экрана и детект ----------------
    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data) ?: return
        projection = proj
        val t = HandlerThread("cc-capture")
        t.start()
        thread = t
        val h = Handler(t.looper)
        bg = h
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                ui.post { stopCapture() }
            }
        }, h)

        val dm = realMetrics()
        val longSide = Math.max(dm.widthPixels, dm.heightPixels)
        val shortSide = Math.min(dm.widthPixels, dm.heightPixels)
        val cw = (longSide / 2) and 1.inv()
        val ch = (shortSide / 2) and 1.inv()
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = proj.createVirtualDisplay(
            "cc", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
        )
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) {
                try { process(img) } catch (e: Exception) { /* пропускаем кадр */ } finally { img.close() }
            }
        }, h)
    }

    private fun stopCapture() {
        try { reader?.setOnImageAvailableListener(null, null) } catch (e: Exception) {}
        try { vd?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        val p = projection
        projection = null
        vd = null
        reader = null
        try { p?.stop() } catch (e: Exception) {}
        thread?.quitSafely()
        thread = null
    }

    private fun reloadTemplate() {
        val f = File(filesDir, "template.bin")
        if (f.exists() && f.lastModified() != templateStamp) {
            templateStamp = f.lastModified()
            template = Template.load(f)
        }
    }

    private fun process(img: Image) {
        val now = SystemClock.uptimeMillis()
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val w = img.width
        val h = img.height

        if (captureNext) {
            captureNext = false
            saveFrame(buf, rs, w, h)
            return
        }
        if (now - lastProc < 120) return
        lastProc = now
        reloadTemplate()
        val t = template ?: return

        val mask = ByteArray(w * h)
        val thr = Matcher.WHITE_MIN
        for (y in 0 until h) {
            val rowBase = y * rs
            for (x in 0 until w) {
                val i = rowBase + x * 4
                val r = buf.get(i).toInt() and 255
                if (r < thr) continue
                val g = buf.get(i + 1).toInt() and 255
                if (g < thr) continue
                val b = buf.get(i + 2).toInt() and 255
                if (b >= thr) mask[y * w + x] = 1
            }
        }
        val score = Matcher.find(mask, w, h, t)
        if (score >= Store.threshold / 100f) {
            lastVisible = now
            seen++
            if (seen >= 2 && !latched && now - lastCount > 1000) {
                latched = true
                lastCount = now
                Store.add(1)
                ui.post { refresh() }
            }
        } else if (now - lastVisible > 500) {
            seen = 0
            latched = false
        }
    }

    // ---------------- калибровка ----------------
    fun startCalibration() {
        if (projection == null) {
            Toast.makeText(this, "Нужен захват экрана: останови и запусти заново, разреши запись экрана", Toast.LENGTH_LONG).show()
            return
        }
        if (bubble != null) return
        val dm = realMetrics()
        val tv = TextView(this)
        tv.text = "📷"
        tv.textSize = 26f
        tv.gravity = Gravity.CENTER
        val bgShape = GradientDrawable()
        bgShape.shape = GradientDrawable.OVAL
        bgShape.setColor(Color.parseColor("#FFB800"))
        tv.background = bgShape
        val size = (64 * dm.density).toInt()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dm.widthPixels - size - (24 * dm.density).toInt()
        p.y = dm.heightPixels / 2
        tv.setOnClickListener {
            tv.visibility = View.INVISIBLE
            ui.postDelayed({ captureNext = true }, 350)
        }
        bubble = tv
        wm.addView(tv, p)
        Toast.makeText(this, "Открой сундук и нажми 📷, пока видна надпись «+600 ОП / СУНДУК»", Toast.LENGTH_LONG).show()
    }

    private fun saveFrame(buf: java.nio.ByteBuffer, rs: Int, w: Int, h: Int) {
        buf.rewind()
        val full = Bitmap.createBitmap(rs / 4, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buf)
        val crop = Bitmap.createBitmap(full, 0, 0, w, h)
        FileOutputStream(File(cacheDir, "frame.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.post {
            bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
            bubble = null
            val i = Intent(this, SelectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        hud?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        hud = null
        bubble = null
        stopCapture()
        instance = null
        super.onDestroy()
    }
}
