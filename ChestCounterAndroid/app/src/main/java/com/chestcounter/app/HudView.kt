package com.chestcounter.app

import android.content.Context
import android.graphics.*
import android.view.View
import java.util.Locale

/** HUD как на референсе: фон нарисован заранее (hud_base.png), числа рисуются поверх. */
class HudView(ctx: Context) : View(ctx) {
    var scale = 0.3f

    private val base: Bitmap
    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fNum: Typeface = loadFont(ctx, "Inter-BlackItalic.otf")
    private val fLabel: Typeface = loadFont(ctx, "Inter-ExtraBoldItalic.otf")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val o = BitmapFactory.Options()
        o.inScaled = false
        base = BitmapFactory.decodeResource(resources, R.drawable.hud_base, o)
        base.setHasMipMap(true)
    }

    private fun loadFont(ctx: Context, name: String): Typeface {
        return try {
            Typeface.createFromAsset(ctx.assets, "fonts/$name")
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

    private fun drawFit(
        c: Canvas, text: String, tf: Typeface, cap: Float, cx: Float, cy: Float, angle: Float,
        maxW: Float, xs: Float, top: Int, bottom: Int,
        strokeW: Float = 0f, strokeColor: Int = 0, glow: Int = 0
    ) {
        val size = cap / 0.727f
        fill.typeface = tf
        fill.textSize = size
        fill.textScaleX = 1f
        val nat = fill.measureText(text)
        val sx = Math.min(xs, maxW / nat)
        fill.textScaleX = sx
        val w = nat * sx
        val baseline = cy + cap / 2f
        val x = cx - w / 2f
        fill.shader = LinearGradient(0f, baseline - cap, 0f, baseline, top, bottom, Shader.TileMode.CLAMP)
        c.save()
        c.rotate(-angle, cx, cy)
        if (strokeW > 0f) {
            outline.typeface = tf
            outline.textSize = size
            outline.textScaleX = sx
            outline.style = Paint.Style.STROKE
            outline.strokeWidth = strokeW * 2f
            outline.strokeJoin = Paint.Join.ROUND
            outline.color = strokeColor
            if (glow != 0) outline.setShadowLayer(16f, 0f, 0f, glow) else outline.clearShadowLayer()
            c.drawText(text, x, baseline, outline)
        }
        c.drawText(text, x, baseline, fill)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.scale(scale, scale)
        c.drawBitmap(base, 0f, 0f, basePaint)

        val total = String.format(Locale.US, "%,d", Store.total)
        val session = Store.session.toString()
        val rate = String.format(Locale.US, "%.1f", Store.rate())
        val white = 0xFFFFFFFF.toInt()
        val soft = 0xFFE6EAFB.toInt()
        val lab1 = 0xFFE8ECFF.toInt()
        val lab2 = 0xFFC9D2F5.toInt()

        // координаты = координаты референса минус начало холста (40, 55)
        drawFit(c, "TOTAL CHESTS", fLabel, 46f, 318f, 300f, 4.4f, 380f, 0.95f, white, 0xFFDDE3F4.toInt())
        drawFit(c, total, fNum, 90f, 332f, 383f, 3.8f, 415f, 0.92f,
            0xFFFFF06E.toInt(), 0xFFFFB800.toInt(), 3f, 0xFFB25A00.toInt(), 0xFFFFB000.toInt())
        drawFit(c, "Session", fLabel, 28f, 801f, 164f, 0f, 150f, 0.95f, lab1, lab2)
        drawFit(c, session, fNum, 60f, 822f, 228f, 0f, 190f, 0.92f, white, soft)
        drawFit(c, "Chests per Min", fLabel, 27f, 897f, 339f, 0f, 235f, 0.95f, lab1, lab2)
        drawFit(c, rate, fNum, 62f, 910f, 402f, 0f, 210f, 0.92f, white, soft)
        c.restore()
    }
}
