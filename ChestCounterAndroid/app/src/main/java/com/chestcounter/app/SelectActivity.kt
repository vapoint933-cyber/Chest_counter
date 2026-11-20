package com.chestcounter.app

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Показывает снятый кадр: обведи надпись "+600 ОП / СУНДУК" (обе строки). */
class SelectActivity : Activity() {

    private lateinit var bmp: Bitmap
    private lateinit var sel: SelectView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val loaded = BitmapFactory.decodeFile(File(cacheDir, "frame.png").absolutePath)
        if (loaded == null) {
            Toast.makeText(this, "Кадр не найден", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        bmp = loaded

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val hint = TextView(this)
        hint.text = "Обведи пальцем надпись «+600 ОП / СУНДУК» (обе строки), потом «Сохранить»"
        hint.setTextColor(Color.YELLOW)
        hint.textSize = 14f
        hint.setPadding(16, 8, 16, 8)
        root.addView(hint)

        sel = SelectView(this, bmp)
        root.addView(sel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val save = Button(this)
        save.text = "Сохранить"
        save.setOnClickListener { saveTemplate() }
        val cancel = Button(this)
        cancel.text = "Отмена"
        cancel.setOnClickListener { finish() }
        row.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        setContentView(root)
    }

    private fun saveTemplate() {
        val r = sel.selectionInBitmap()
        if (r == null) {
            Toast.makeText(this, "Сначала обведи надпись", Toast.LENGTH_SHORT).show()
            return
        }
        val mask = Matcher.maskFromBitmap(bmp)
        val t = Template.fromMask(mask, bmp.width, r.left, r.top, r.right, r.bottom)
        if (t == null) {
            Toast.makeText(this, "В рамке нет белого текста, попробуй ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        t.save(File(filesDir, "template.bin"))
        Toast.makeText(this, "Шаблон сохранён ✔", Toast.LENGTH_LONG).show()
        finish()
    }
}

class SelectView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f
    private var has = false
    private val rectPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f

    override fun onDraw(c: Canvas) {
        scale = Math.min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        ox = (width - bmp.width * scale) / 2f
        oy = (height - bmp.height * scale) / 2f
        val dst = RectF(ox, oy, ox + bmp.width * scale, oy + bmp.height * scale)
        c.drawBitmap(bmp, null, dst, null)
        if (has) c.drawRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), rectPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y; has = true }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { x1 = e.x; y1 = e.y }
        }
        invalidate()
        return true
    }

    fun selectionInBitmap(): Rect? {
        if (!has) return null
        fun bx(v: Float) = Math.max(0, Math.min(bmp.width, ((v - ox) / scale).toInt()))
        fun by(v: Float) = Math.max(0, Math.min(bmp.height, ((v - oy) / scale).toInt()))
        val l = bx(Math.min(x0, x1)); val r = bx(Math.max(x0, x1))
        val t = by(Math.min(y0, y1)); val b = by(Math.max(y0, y1))
        return if (r - l > 8 && b - t > 8) Rect(l, t, r, b) else null
    }
}
