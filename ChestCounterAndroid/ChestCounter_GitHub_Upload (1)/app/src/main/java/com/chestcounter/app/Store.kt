package com.chestcounter.app

import android.content.Context
import android.content.SharedPreferences

/** Настройки и счётчики. Общий счёт и настройки хранятся на диске, сессия - в памяти. */
object Store {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        if (!::p.isInitialized) {
            p = ctx.applicationContext.getSharedPreferences("cc", Context.MODE_PRIVATE)
        }
    }

    var total: Int
        get() = p.getInt("total", 0)
        set(v) { p.edit().putInt("total", if (v < 0) 0 else v).apply() }

    var sizePct: Int          // ширина HUD в % от ширины экрана
        get() = p.getInt("size", 38)
        set(v) { p.edit().putInt("size", v).apply() }

    var posX: Int             // положение по горизонтали, 0..100
        get() = p.getInt("posx", 2)
        set(v) { p.edit().putInt("posx", v).apply() }

    var posY: Int             // положение по вертикали, 0..100
        get() = p.getInt("posy", 3)
        set(v) { p.edit().putInt("posy", v).apply() }

    var threshold: Int        // чувствительность детекта, % (выше = строже)
        get() = p.getInt("thr", 75)
        set(v) { p.edit().putInt("thr", v).apply() }

    @Volatile var session: Int = 0
    private val stamps = ArrayList<Long>()
    private var startedAt = System.currentTimeMillis()

    @Synchronized
    fun add(n: Int) {
        total = total + n
        session = if (session + n < 0) 0 else session + n
        if (n > 0) stamps.add(System.currentTimeMillis())
    }

    @Synchronized
    fun resetSession() {
        session = 0
        stamps.clear()
        startedAt = System.currentTimeMillis()
    }

    /** Сундуков в минуту (скользящее окно 60 секунд). */
    @Synchronized
    fun rate(): Float {
        val now = System.currentTimeMillis()
        stamps.removeAll { now - it > 60000L }
        val elapsed = (now - startedAt) / 1000f
        val window = if (elapsed < 10f) 10f else if (elapsed > 60f) 60f else elapsed
        return stamps.size / window * 60f
    }
}
