package com.user.aplaunch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun Context.dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number): Int = dp(v).toInt()

fun Context.tv(s: String, size: Float, alpha: Float, family: String): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = size
    t.setTextColor(Color.WHITE)
    t.alpha = alpha
    t.typeface = Typeface.create(family, Typeface.NORMAL)
    return t
}

fun withAlpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a shl 24)

// ---------------------------------------------------------------- settings storage

object K {
    const val CLOCK_MODE = "clock_mode"
    const val CLOCK_SIZE = "clock_size"
    const val CLOCK_WEIGHT = "clock_weight"
    const val CLOCK_FMT = "clock_fmt"
    const val CLOCK_DATE = "clock_date"
    const val SCENE = "scene"
    const val ROTATE = "rotate"
    const val MOTION = "motion"
    const val GLOW = "glow"
    const val GLOW_STR = "glow_str"
    const val SHEEN = "sheen"
    const val ANIM = "anim"
    const val PERF = "perf"
    const val AOD = "aod"
    const val AOD_DELAY = "aod_delay"
    const val AOD_DIM = "aod_dim"
    const val KEEP_ON = "keep_on"
    const val ALL_APPS_TILE = "all_apps_tile"
    const val SHOW_MISSING = "show_missing"

    val DEF: Map<String, Int> = mapOf(
        CLOCK_MODE to 0, CLOCK_SIZE to 1, CLOCK_WEIGHT to 2, CLOCK_FMT to 0, CLOCK_DATE to 1,
        SCENE to 0, ROTATE to 1, MOTION to 1,
        GLOW to 1, GLOW_STR to 1, SHEEN to 1, ANIM to 0, PERF to 0,
        AOD to 1, AOD_DELAY to 1, AOD_DIM to 1, KEEP_ON to 1,
        ALL_APPS_TILE to 1, SHOW_MISSING to 1
    )
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("home_v2", Context.MODE_PRIVATE)
    fun i(k: String): Int = p.getInt(k, K.DEF[k] ?: 0)
    fun on(k: String): Boolean = i(k) == 1
    fun put(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun clear() = p.edit().clear().apply()
}

// ---------------------------------------------------------------- views

/** iPhone-style time: huge digits, drawn BETWEEN the sky layer and the mountain layer. */
class ClockView(ctx: Context) : View(ctx) {
    var crest = 0.5f
        set(v) { field = v; invalidate() }
    var sizeFrac = 0.21f
    var weight = 2
    var format = 0
    var showDate = true
    var behind = true

    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dpaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private var time = ""
    private var date = ""

    fun tick() {
        val now = Date()
        val h24 = when (format) {
            1 -> false
            2 -> true
            else -> android.text.format.DateFormat.is24HourFormat(context)
        }
        time = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(now)
        date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (height == 0 || time.isEmpty()) return
        val hf = height.toFloat()
        val face = when (weight) {
            0 -> "sans-serif-light"
            1 -> "sans-serif-medium"
            else -> "sans-serif-black"
        }
        tp.typeface = Typeface.create(face, Typeface.NORMAL)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 100f
        tp.getTextBounds("8", 0, 1, bounds)
        val unit = max(1f, -bounds.top.toFloat())
        val dH = sizeFrac * hf
        tp.textSize = 100f * dH / unit

        val top = if (behind) max(0.07f * hf, crest * hf - 0.62f * dH) else 0.12f * hf
        val base = top + dH
        tp.shader = LinearGradient(0f, top, 0f, base, 0xF5FFFFFF.toInt(), 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP)
        tp.setShadowLayer(dH * 0.12f, 0f, dH * 0.02f, 0x55000000)
        c.drawText(time, width / 2f, base, tp)

        if (showDate) {
            dpaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            dpaint.textAlign = Paint.Align.CENTER
            dpaint.color = 0xE6FFFFFF.toInt()
            dpaint.textSize = dH * 0.16f
            dpaint.setShadowLayer(dH * 0.05f, 0f, 0f, 0x66000000)
            c.drawText(date, width / 2f, top - dH * 0.14f, dpaint)
        }
    }
}

class GearView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.strokeWidth = s * 0.11f
        c.drawCircle(cx, cy, s * 0.2f, p)
        p.strokeWidth = s * 0.14f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.33f, cy + sa * s * 0.33f, cx + ca * s * 0.43f, cy + sa * s * 0.43f, p)
        }
    }
}

/** 3x3 grid glyph for the "All Apps" tile. */
class DotsView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cell = s / 3f
        val sq = cell * 0.62f
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        for (r in 0 until 3) for (q in 0 until 3) {
            val x = ox + q * cell + (cell - sq) / 2f
            val y = oy + r * cell + (cell - sq) / 2f
            c.drawRoundRect(x, y, x + sq, y + sq, sq * 0.28f, sq * 0.28f, p)
        }
    }
}
