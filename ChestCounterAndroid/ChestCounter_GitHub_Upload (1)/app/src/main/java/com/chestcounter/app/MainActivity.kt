package com.chestcounter.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Главный экран: разрешение, запуск, ввод числа сундуков, размер/положение HUD, калибровка. */
class MainActivity : Activity() {

    private val REQ_CAPTURE = 100
    private lateinit var status: TextView
    private lateinit var totalEdit: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#FFD23A"))
        textSize = 15f
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun btn(t: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(if (color == "#FFB800") Color.parseColor("#1B1A20") else Color.WHITE)
        textSize = 15f
        val g = GradientDrawable()
        g.cornerRadius = dp(10).toFloat()
        g.setColor(Color.parseColor(color))
        background = g
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(8)
        }
    }

    private fun seek(label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val tv = TextView(this)
        tv.setTextColor(Color.parseColor("#E8ECFF"))
        tv.text = "$label: $value"
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = value - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                tv.text = "$label: $v"
                onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(sb)
        box.setPadding(0, dp(6), 0, dp(6))
        return box
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(28), dp(18), dp(28))
        col.setBackgroundColor(Color.parseColor("#121A3A"))

        val head = TextView(this)
        head.text = "CHEST COUNTER"
        head.setTextColor(Color.parseColor("#FFD23A"))
        head.textSize = 26f
        head.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC)
        col.addView(head)

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.setPadding(0, dp(6), 0, dp(6))
        col.addView(status)

        col.addView(btn("1. Разрешить «Поверх других приложений»", "#2A7FE8") { openOverlaySettings() })
        col.addView(btn("2. ▶ Запустить", "#FFB800") { startFlow() })
        col.addView(btn("■ Остановить", "#2A3566") {
            stopService(Intent(this, OverlayService::class.java))
            updateStatus()
        })

        col.addView(title("Сколько сундуков уже открыто"))
        totalEdit = EditText(this)
        totalEdit.inputType = InputType.TYPE_CLASS_NUMBER
        totalEdit.setTextColor(Color.WHITE)
        totalEdit.textSize = 22f
        totalEdit.setText(Store.total.toString())
        col.addView(totalEdit)
        col.addView(btn("Сохранить число", "#FFB800") {
            Store.total = totalEdit.text.toString().toIntOrNull() ?: 0
            OverlayService.instance?.refresh()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        })

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val plus = btn("+1", "#2A7FE8") { bump(1) }
        val minus = btn("−1", "#2A7FE8") { bump(-1) }
        val reset = btn("Сброс сессии", "#2A3566") {
            Store.resetSession()
            OverlayService.instance?.refresh()
        }
        for (b in listOf(plus, minus, reset)) {
            b.layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { topMargin = dp(8); marginEnd = dp(6) }
            row.addView(b)
        }
        col.addView(row)

        col.addView(title("Вид HUD"))
        col.addView(seek("Размер (% ширины экрана)", 10, 80, Store.sizePct) {
            Store.sizePct = it; OverlayService.instance?.applyLayout()
        })
        col.addView(seek("Позиция по горизонтали", 0, 100, Store.posX) {
            Store.posX = it; OverlayService.instance?.applyLayout()
        })
        col.addView(seek("Позиция по вертикали", 0, 100, Store.posY) {
            Store.posY = it; OverlayService.instance?.applyLayout()
        })

        col.addView(title("Автоподсчёт по надписи «+600 ОП / СУНДУК»"))
        col.addView(btn("Калибровка (один раз)", "#2A7FE8") {
            val s = OverlayService.instance
            if (s == null) {
                Toast.makeText(this, "Сначала нажми «Запустить»", Toast.LENGTH_SHORT).show()
            } else {
                s.startCalibration()
                moveTaskToBack(true)
            }
        })
        col.addView(seek("Чувствительность (выше = строже)", 50, 95, Store.threshold) { Store.threshold = it })

        val help = TextView(this)
        help.setTextColor(Color.parseColor("#9AA6D6"))
        help.textSize = 13f
        help.setPadding(0, dp(14), 0, 0)
        help.text = "Как пользоваться:\n" +
                "1) Выдай разрешение и нажми «Запустить» (разреши запись экрана, выбери «Весь экран»).\n" +
                "2) Калибровка: открой сундук в Fortnite, нажми круглую кнопку 📷, пока видна надпись, и обведи её.\n" +
                "3) Дальше сундуки считаются сами. Кнопки +1 / −1 есть в шторке уведомлений."
        col.addView(help)

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#121A3A"))
        sv.addView(col)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        totalEdit.setText(Store.total.toString())
        updateStatus()
    }

    private fun updateStatus() {
        status.text = when {
            !Settings.canDrawOverlays(this) -> "⚠ Нет разрешения «Поверх других приложений»"
            OverlayService.instance != null -> "✔ Работает"
            else -> "Остановлено"
        }
    }

    private fun bump(n: Int) {
        Store.add(n)
        totalEdit.setText(Store.total.toString())
        OverlayService.instance?.refresh()
    }

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала выдай разрешение «Поверх других приложений»", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val i = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        if (resultCode == RESULT_OK && data != null) {
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
        } else {
            Toast.makeText(this, "Без записи экрана автоподсчёт не работает, только кнопки +1/−1", Toast.LENGTH_LONG).show()
        }
        startForegroundService(i)
        window.decorView.postDelayed({ updateStatus() }, 600)
    }
}
