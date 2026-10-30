package com.user.aplaunch

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Procedurally painted mountain scenes, split into two layers so the clock can sit BETWEEN them
 * (sky + sun behind the clock, mountains in front of it) like the iPhone depth effect.
 *   back  = sky, stars, aurora, sun/moon, water
 *   front = all mountain ridges on a transparent background
 *   crest = where the highest mountains sit behind the clock position (0..1 of height)
 */
object Scenes {

    class Layers(val back: Bitmap, val front: Bitmap, val crest: Float)

    private class Ridge(
        val base: Float, val amp: Float, val freq: Float,
        val top: Int, val bottom: Int, val seed: Int, val haze: Int,
        val trees: Boolean = false
    )

    private class Spec(
        val name: String,
        val sky: IntArray, val stops: FloatArray,
        val sun: FloatArray?, val sunColor: Int,
        val ridges: List<Ridge>,
        val stars: Boolean = false, val aurora: Boolean = false,
        val horizon: Float = 0f
    )

    private fun c(s: String) = Color.parseColor(s)
    private fun withAlpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a shl 24)

    private val specs: List<Spec> by lazy {
        listOf(
            Spec(
                "Alpine Dawn",
                intArrayOf(c("#1B2A4A"), c("#6E5A8E"), c("#E59A8F"), c("#FFD2A1")),
                floatArrayOf(0f, .45f, .8f, 1f),
                floatArrayOf(.68f, .60f, .05f), c("#FFE6C0"),
                listOf(
                    Ridge(.62f, .22f, 2.2f, c("#6B6290"), c("#9A82A8"), 11, c("#66F0B8A8")),
                    Ridge(.72f, .17f, 2.8f, c("#4A5278"), c("#6E6A94"), 23, c("#55D89AA0")),
                    Ridge(.82f, .14f, 3.2f, c("#2E3659"), c("#444C73"), 29, c("#44B07A90")),
                    Ridge(.93f, .10f, 3.8f, c("#171E36"), c("#222A47"), 31, c("#00000000"))
                )
            ),
            Spec(
                "Aurora Lake",
                intArrayOf(c("#02050F"), c("#06182B"), c("#0C2D3B")),
                floatArrayOf(0f, .6f, 1f),
                floatArrayOf(.82f, .16f, .026f), c("#EAF4FF"),
                listOf(
                    Ridge(.60f, .18f, 2.0f, c("#0B1A22"), c("#0E232C"), 5, c("#330C2D3B")),
                    Ridge(.63f, .09f, 3.2f, c("#050C10"), c("#071217"), 9, c("#00000000")),
                    Ridge(.645f, .04f, 6.0f, c("#020608"), c("#03090C"), 13, c("#00000000"), true)
                ),
                stars = true, aurora = true, horizon = .65f
            ),
            Spec(
                "Dune Gold",
                intArrayOf(c("#3A2A5C"), c("#B5527F"), c("#F59A5E"), c("#FFD99A")),
                floatArrayOf(0f, .4f, .75f, 1f),
                floatArrayOf(.30f, .58f, .06f), c("#FFF2C8"),
                listOf(
                    Ridge(.62f, .14f, 1.3f, c("#E8905A"), c("#C46A48"), 31, c("#44FFC898")),
                    Ridge(.74f, .12f, 1.1f, c("#C56A45"), c("#9A4A3B"), 37, c("#33E89060")),
                    Ridge(.86f, .13f, 0.9f, c("#8E3F36"), c("#5E2A2E"), 41, c("#00000000"))
                )
            ),
            Spec(
                "Pine Mist",
                intArrayOf(c("#9DB6C4"), c("#D2DEE2"), c("#F0F2EE")),
                floatArrayOf(0f, .55f, 1f),
                floatArrayOf(.35f, .30f, .07f), c("#FFFFFF"),
                listOf(
                    Ridge(.56f, .16f, 2.5f, c("#A9BEC2"), c("#C3D2D2"), 51, c("#99EEF3F0")),
                    Ridge(.63f, .12f, 2.8f, c("#8CA6AB"), c("#A9BEC2"), 53, c("#88EEF3F0"), true),
                    Ridge(.73f, .14f, 3.0f, c("#6A878B"), c("#86A0A3"), 57, c("#77EEF3F0"), true),
                    Ridge(.83f, .14f, 3.4f, c("#43605F"), c("#587674"), 59, c("#55EEF3F0"), true),
                    Ridge(.94f, .12f, 3.8f, c("#1F3633"), c("#2A4742"), 61, c("#00000000"), true)
                )
            ),
            Spec(
                "Moonlit Sea",
                intArrayOf(c("#040A1B"), c("#12274A"), c("#2E4D79")),
                floatArrayOf(0f, .6f, 1f),
                floatArrayOf(.64f, .24f, .045f), c("#EEF4FF"),
                listOf(
                    Ridge(.60f, .12f, 2.5f, c("#0C1730"), c("#101D3A"), 71, c("#00000000"))
                ),
                stars = true, horizon = .62f
            )
        )
    }

    val count: Int get() = specs.size
    val names: List<String> get() = specs.map { it.name }

    // ------------------------------------------------------------------ noise

    private fun hash(i: Int, seed: Int): Float {
        var n = i * 374761393 + seed * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFFFF) / 16777215f
    }

    private fun noise(t: Float, seed: Int): Float {
        val i = floor(t).toInt()
        val f = t - i
        val s = f * f * (3f - 2f * f)
        val a = hash(i, seed)
        val b = hash(i + 1, seed)
        return a + (b - a) * s
    }

    private fun fbm(t: Float, seed: Int): Float {
        var v = 0f
        var amp = 1f
        var fr = 1f
        for (o in 0 until 5) {
            v += noise(t * fr, seed + o * 17) * amp
            amp *= 0.5f
            fr *= 2f
        }
        return (v / 1.9375f).pow(1.25f)
    }

    private fun ridgeY(r: Ridge, xFrac: Float) = r.base - r.amp * fbm(xFrac * r.freq * 3f, r.seed)

    // ------------------------------------------------------------------ render

    fun render(index: Int, w: Int, h: Int): Layers {
        val s = specs[index % specs.size]
        val back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val bc = Canvas(back)
        val fc = Canvas(front)

        drawSky(bc, s, w, h)
        drawRidges(fc, s, w, h)
        if (s.horizon > 0f) drawWater(back, front, s, w, h)
        vignette(bc, w, h, null)
        vignette(fc, w, h, PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP))
        grain(bc, w, h)

        // crest height behind the clock: a high-ish percentile of the farthest ridge around the centre
        val r0 = s.ridges[0]
        val ys = ArrayList<Float>()
        var x = 0.30f
        while (x <= 0.70f) { ys.add(ridgeY(r0, x)); x += 0.01f }
        ys.sort()
        val crest = ys[(ys.size * 0.3f).toInt()]
        return Layers(back, front, crest)
    }

    private fun drawSky(cv: Canvas, s: Spec, w: Int, h: Int) {
        val k = h / 900f
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val skyBottom = if (s.horizon > 0f) s.horizon * hf else hf
        p.shader = LinearGradient(0f, 0f, 0f, skyBottom, s.sky, s.stops, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, wf, hf, p)
        p.shader = null

        if (s.stars) {
            val r = Random(7)
            p.color = Color.WHITE
            for (i in 0 until 260) {
                p.alpha = 40 + r.nextInt(180)
                cv.drawCircle(r.nextFloat() * wf, r.nextFloat() * skyBottom * 0.8f, (0.6f + r.nextFloat() * 1.3f) * k, p)
            }
            p.alpha = 255
        }

        if (s.aurora) {
            val ap = Paint(Paint.ANTI_ALIAS_FLAG)
            ap.maskFilter = BlurMaskFilter(hf * 0.012f, BlurMaskFilter.Blur.NORMAL)
            val cols = intArrayOf(c("#27F5A8"), c("#2AD0E0"), c("#35F2B0"), c("#9A6BFF"))
            val step = 8f
            for (kk in 0 until 4) {
                val baseY = skyBottom * (0.24f + 0.07f * kk)
                val ph = kk * 1.7f
                val tops = ArrayList<Float>()
                val bots = ArrayList<Float>()
                var x = 0f
                while (x <= wf + step) {
                    val u = x / wf
                    val yb = baseY + sin(u * 6.0f + ph) * hf * 0.05f + sin(u * 13f + ph * 2f) * hf * 0.015f
                    tops.add(yb - hf * (0.12f + 0.05f * sin(u * 4f + ph)))
                    bots.add(yb)
                    x += step
                }
                val path = Path()
                path.moveTo(0f, tops[0])
                for (i in 1 until tops.size) path.lineTo(i * step, tops[i])
                for (i in bots.indices.reversed()) path.lineTo(i * step, bots[i])
                path.close()
                ap.shader = LinearGradient(
                    0f, baseY - hf * 0.18f, 0f, baseY + hf * 0.06f,
                    intArrayOf(withAlpha(cols[kk], 0), withAlpha(cols[kk], 120), withAlpha(cols[kk], 0)),
                    floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP
                )
                cv.drawPath(path, ap)
            }
        }

        s.sun?.let { sn ->
            val sx = sn[0] * wf; val sy = sn[1] * hf; val sr = sn[2] * hf
            p.shader = RadialGradient(
                sx, sy, sr * 9f,
                intArrayOf(withAlpha(s.sunColor, 150), withAlpha(s.sunColor, 40), 0),
                floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP
            )
            cv.drawCircle(sx, sy, sr * 9f, p)
            p.shader = null
            p.color = s.sunColor
            cv.drawCircle(sx, sy, sr, p)
        }
    }

    private fun drawRidges(cv: Canvas, s: Spec, w: Int, h: Int) {
        val k = h / 900f
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (s.horizon > 0f) cv.clipRect(0f, 0f, wf, s.horizon * hf)
        for (r in s.ridges) {
            val yBase = r.base * hf
            val rnd = Random(r.seed.toLong())
            val path = Path()
            path.moveTo(0f, hf)
            var x = 0
            while (x <= w + 8) {
                var y = ridgeY(r, x.toFloat() / wf) * hf
                if (r.trees) y -= if ((x / 6) % 2 == 0) (10f + rnd.nextFloat() * 16f) * k else 0f
                path.lineTo(x.toFloat(), y)
                x += 4
            }
            path.lineTo(wf, hf)
            path.close()
            p.shader = LinearGradient(0f, yBase - r.amp * hf, 0f, min(hf, yBase + 0.12f * hf), r.top, r.bottom, Shader.TileMode.CLAMP)
            cv.drawPath(path, p)
            p.shader = LinearGradient(0f, yBase - r.amp * hf, 0f, hf, 0, r.haze, Shader.TileMode.CLAMP)
            cv.drawRect(0f, yBase - r.amp * hf, wf, hf, p)
            p.shader = null
        }
    }

    private fun drawWater(back: Bitmap, front: Bitmap, s: Spec, w: Int, h: Int) {
        val k = h / 900f
        val wf = w.toFloat(); val hf = h.toFloat()
        val cv = Canvas(back)
        val y0 = (s.horizon * hf).toInt()
        val rh = h - y0
        val tmp = back.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(tmp).drawBitmap(front, 0f, 0f, null)
        val sub = Bitmap.createBitmap(tmp, 0, y0 - rh, w, rh)
        tmp.recycle()
        val m = Matrix()
        m.setScale(1f, -1f)
        m.postTranslate(0f, (y0 + rh).toFloat())
        val rp = Paint(Paint.FILTER_BITMAP_FLAG)
        rp.alpha = 150
        cv.drawBitmap(sub, m, rp)
        sub.recycle()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, y0.toFloat(), 0f, hf, 0, withAlpha(s.sky[0], 190), Shader.TileMode.CLAMP)
        cv.drawRect(0f, y0.toFloat(), wf, hf, p)
        p.shader = null
        val r = Random(99)
        p.color = Color.WHITE
        p.strokeWidth = 1.6f * k
        for (i in 0 until 220) {
            val y = y0 + r.nextFloat().pow(1.6f) * rh
            val len = (20f + r.nextFloat() * 120f) * k
            val x = r.nextFloat() * wf
            p.alpha = 10 + r.nextInt(16)
            cv.drawLine(x, y, x + len, y, p)
        }
        s.sun?.let { sn ->
            val sx = sn[0] * wf
            p.color = s.sunColor
            for (i in 0 until 70) {
                val y = y0 + r.nextFloat() * rh
                val spread = (10f + (y - y0) * 0.25f) * k
                val x = sx + (r.nextFloat() - 0.5f) * 2f * spread
                p.alpha = 30 + r.nextInt(70)
                cv.drawLine(x - 20f * k, y, x + 20f * k + r.nextFloat() * 50f * k, y, p)
            }
        }
    }

    private fun vignette(cv: Canvas, w: Int, h: Int, mode: PorterDuffXfermode?) {
        val wf = w.toFloat(); val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            wf / 2f, hf * 0.45f, max(wf, hf) * 0.75f,
            intArrayOf(0, withAlpha(Color.BLACK, 110)), floatArrayOf(.55f, 1f), Shader.TileMode.CLAMP
        )
        if (mode != null) p.xfermode = mode
        cv.drawRect(0f, 0f, wf, hf, p)
    }

    /** Fine grain: hides gradient banding on 8-bit panels. */
    private fun grain(cv: Canvas, w: Int, h: Int) {
        val tile = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val nr = Random(3)
        for (yy in 0 until 128) for (xx in 0 until 128) {
            val v = nr.nextInt(256)
            tile.setPixel(xx, yy, Color.argb(10, v, v, v))
        }
        val p = Paint()
        p.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        tile.recycle()
    }
}
