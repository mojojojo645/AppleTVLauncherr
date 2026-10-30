package com.user.aplaunch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class AppEntry(val pkg: String, val label: String, val intent: Intent, val icon: Drawable, val banner: Drawable?)

/** One of the fixed front-row apps. Matched by package first, then by label. "=x" = exact label. */
class Fixed(val name: String, val pkgs: List<String>, val hints: List<String>, val color: Int)

class MainActivity : Activity() {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val FIXED = listOf(
            Fixed("Rad TV", emptyList(), listOf("radtv"), Color.parseColor("#FF2D55")),
            Fixed("YouTube", listOf("com.google.android.youtube.tv"), listOf("=youtube"), Color.parseColor("#E62117")),
            Fixed("Netflix", listOf("com.netflix.ninja"), listOf("=netflix"), Color.parseColor("#E50914")),
            Fixed("HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max"), listOf("hbomax", "=max"), Color.parseColor("#5B2DD0")),
            Fixed("Disney+", listOf("com.disney.disneyplus"), listOf("disney"), Color.parseColor("#113CCF")),
            Fixed("Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), listOf("primevideo"), Color.parseColor("#00A8E1")),
            Fixed("Crunchyroll", listOf("com.crunchyroll.crunchyroid"), listOf("crunchyroll"), Color.parseColor("#F47521")),
            Fixed("Stremio", listOf("com.stremio.one"), listOf("stremio"), Color.parseColor("#7B5BF5")),
            Fixed("7reels", emptyList(), listOf("7reels"), Color.parseColor("#00BFA5"))
        )
        val GLOW_A = floatArrayOf(0.55f, 0.85f, 1f)
        val DIM = floatArrayOf(0.6f, 0.4f, 0.25f)
        val AOD_MS = longArrayOf(60_000, 120_000, 300_000, 600_000, 1_800_000)
        val ROT_MS = longArrayOf(30_000, 60_000, 300_000, 900_000, 0)
        val CLOCK_SIZE = floatArrayOf(0.15f, 0.21f, 0.27f)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private lateinit var store: Store

    private lateinit var root: FrameLayout
    private lateinit var backHost: FrameLayout
    private lateinit var frontHost: FrameLayout
    private lateinit var clock: ClockView
    private lateinit var content: FrameLayout
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout
    private lateinit var backCur: ImageView
    private lateinit var backNext: ImageView
    private lateinit var frontCur: ImageView
    private lateinit var frontNext: ImageView

    private var overlay: View? = null
    private var apps: List<AppEntry> = emptyList()
    private var sig = ""
    private var wallIndex = 0
    private var wallBusy = false
    private var pendingIndex: Int? = null
    private var lastScene = -1
    private var drifts = ArrayList<ObjectAnimator>()
    private var idle = false
    private var dim = 0f
    private var dimAnim: ValueAnimator? = null

    private val rotate = Runnable { nextWallpaper(null); scheduleRotate() }
    private val idleRun = Runnable { enterIdle() }
    private val clockTick = object : Runnable {
        override fun run() {
            clock.tick()
            ui.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        root = FrameLayout(this)
        backHost = FrameLayout(this)
        frontHost = FrameLayout(this)
        backCur = wallView(); backNext = wallView()
        frontCur = wallView(); frontNext = wallView()
        backHost.addView(backCur, FrameLayout.LayoutParams(MATCH, MATCH))
        backHost.addView(backNext, FrameLayout.LayoutParams(MATCH, MATCH))
        frontHost.addView(frontCur, FrameLayout.LayoutParams(MATCH, MATCH))
        frontHost.addView(frontNext, FrameLayout.LayoutParams(MATCH, MATCH))
        clock = ClockView(this)

        // z-order: sky layer, clock, mountain layer, scrim, UI
        root.addView(backHost, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(clock, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(frontHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0x00000000, 0x00000000, 0xB0000000.toInt())
        )
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        content = FrameLayout(this)
        content.clipChildren = false
        buildGear()
        buildRow()
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        applySettings()
        refreshApps(true)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(clockTick)
        ui.post(clockTick)
        scheduleRotate()
        drifts.forEach { it.resume() }
        if (idle) wake(true)
        resetIdle()
        refreshApps(false)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(clockTick)
        ui.removeCallbacks(rotate)
        ui.removeCallbacks(idleRun)
        drifts.forEach { it.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        bg.shutdownNow()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (idle) wake(true)
        if (overlay != null) closeOverlay()
        rowScroll.smoothScrollTo(0, 0)
        firstCard()?.requestFocus()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (overlay != null) closeOverlay()
        // a launcher never goes "back"
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (idle) {
            if (e.action == KeyEvent.ACTION_DOWN) wake(false)
            return true
        }
        resetIdle()
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_MENU && overlay == null) {
            openSettings()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- settings -> live effects

    private fun d(ms: Long): Long {
        var f = when (store.i(K.ANIM)) { 0 -> 1.0; 1 -> 0.5; else -> 0.0 }
        if (store.on(K.PERF) && f > 0.5) f = 0.5
        return (ms * f).toLong()
    }

    private fun motionOn() = store.on(K.MOTION) && !store.on(K.PERF)

    private fun applySettings() {
        // clock
        clock.sizeFrac = CLOCK_SIZE[store.i(K.CLOCK_SIZE)]
        clock.weight = store.i(K.CLOCK_WEIGHT)
        clock.format = store.i(K.CLOCK_FMT)
        clock.showDate = store.on(K.CLOCK_DATE)
        val mode = store.i(K.CLOCK_MODE)
        clock.behind = mode == 0
        clock.visibility = if (mode == 2) View.GONE else View.VISIBLE
        val clockBelowFront = root.indexOfChild(clock) < root.indexOfChild(frontHost)
        if (mode == 1 && clockBelowFront) {
            root.removeView(clock)
            root.addView(clock, root.indexOfChild(frontHost) + 1, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (mode != 1 && !clockBelowFront) {
            root.removeView(clock)
            root.addView(clock, root.indexOfChild(frontHost), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        clock.tick()

        // keep display on
        if (store.on(K.KEEP_ON)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // wallpaper scene / rotation / motion
        val sc = store.i(K.SCENE)
        if (sc != lastScene) {
            lastScene = sc
            nextWallpaper(null)
        }
        scheduleRotate()
        if (!motionOn()) {
            stopDrifts()
            for (v in listOf(backCur, backNext, frontCur, frontNext)) { v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f }
        } else if (drifts.isEmpty()) {
            startDrifts(backCur, frontCur)
        }

        // always-on display
        if (!store.on(K.AOD) && idle) wake(true)
        resetIdle()
        applyDim()
    }

    private fun scheduleRotate() {
        ui.removeCallbacks(rotate)
        val ms = ROT_MS[store.i(K.ROTATE)]
        if (ms > 0 && store.i(K.SCENE) == 0) ui.postDelayed(rotate, ms)
    }

    // ---------------------------------------------------------------- always-on display

    private fun resetIdle() {
        ui.removeCallbacks(idleRun)
        if (store.on(K.AOD) && overlay == null) ui.postDelayed(idleRun, AOD_MS[store.i(K.AOD_DELAY)])
    }

    private fun enterIdle() {
        if (idle || overlay != null) return
        idle = true
        content.animate().alpha(0f).setDuration(d(700)).start()
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        dimTo(1f)
    }

    private fun wake(instant: Boolean) {
        idle = false
        content.animate().cancel()
        if (instant) content.alpha = 1f else content.animate().alpha(1f).setDuration(d(350)).start()
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (instant) { dimAnim?.cancel(); dim = 0f; applyDim() } else dimTo(0f)
        resetIdle()
        firstCard()?.requestFocus()
    }

    private fun dimTo(target: Float) {
        dimAnim?.cancel()
        val a = ValueAnimator.ofFloat(dim, target)
        a.duration = d(1200).coerceAtLeast(1)
        a.addUpdateListener { dim = it.animatedValue as Float; applyDim() }
        dimAnim = a
        a.start()
    }

    private fun applyDim() {
        val m = DIM[store.i(K.AOD_DIM)]
        val g = (255 * (1f - (1f - m) * dim)).toInt().coerceIn(0, 255)
        for (v in listOf(backCur, backNext, frontCur, frontNext)) {
            if (dim <= 0f) v.clearColorFilter() else v.colorFilter = LightingColorFilter(Color.rgb(g, g, g), 0)
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private fun wallView(): ImageView {
        val v = ImageView(this)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        return v
    }

    private fun nextWallpaper(index: Int?) {
        if (wallBusy) { if (index != null) pendingIndex = index; return }
        wallBusy = true
        val sc = store.i(K.SCENE)
        val i = index ?: if (sc > 0) sc - 1 else wallIndex++ % Scenes.count
        val big = !store.on(K.PERF)
        bg.execute {
            val layers = try {
                if (big) Scenes.render(i, 1600, 900) else Scenes.render(i, 1280, 720)
            } catch (e: Throwable) {
                null
            }
            ui.post {
                wallBusy = false
                if (!isDestroyed) {
                    if (layers != null) showWall(layers)
                    pendingIndex?.let { pendingIndex = null; nextWallpaper(it) }
                }
            }
        }
    }

    private fun driftAnim(v: View, from: Float, to: Float, dx: Float): ObjectAnimator {
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx)
        )
        a.duration = 90_000
        a.interpolator = LinearInterpolator()
        return a
    }

    private fun startDrifts(back: View, front: View) {
        // the near layer moves more than the far layer: that's the parallax
        drifts.add(driftAnim(back, 1.02f, 1.05f, -dp(14)))
        drifts.add(driftAnim(front, 1.03f, 1.08f, -dp(30)))
        drifts.forEach { if (!it.isStarted) it.start() }
    }

    private fun stopDrifts() {
        drifts.forEach { it.cancel() }
        drifts.clear()
    }

    private fun showWall(l: Scenes.Layers) {
        val inB = backNext; val inF = frontNext
        val outB = backCur; val outF = frontCur
        inB.setImageBitmap(l.back); inF.setImageBitmap(l.front)
        inB.alpha = 0f; inF.alpha = 0f
        backHost.bringChildToFront(inB)
        frontHost.bringChildToFront(inF)

        val old = ArrayList(drifts)
        drifts = ArrayList()
        if (motionOn()) {
            inB.scaleX = 1.02f; inB.scaleY = 1.02f; inB.translationX = 0f
            inF.scaleX = 1.03f; inF.scaleY = 1.03f; inF.translationX = 0f
            startDrifts(inB, inF)
        } else {
            for (v in listOf(inB, inF)) { v.scaleX = 1f; v.scaleY = 1f; v.translationX = 0f }
        }
        applyDim()

        val dur = d(2000)
        if (dur == 0L) {
            inB.alpha = 1f; inF.alpha = 1f
            finishSwap(old, inB, inF, outB, outF)
        } else {
            val fb = ObjectAnimator.ofFloat(inB, View.ALPHA, 0f, 1f)
            val ff = ObjectAnimator.ofFloat(inF, View.ALPHA, 0f, 1f)
            fb.duration = dur; ff.duration = dur
            ff.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishSwap(old, inB, inF, outB, outF)
            })
            fb.start(); ff.start()
        }

        // slide the clock to where the new mountains' crest is, so the overlap stays right
        val from = clock.crest
        if (from == l.crest || dur == 0L) {
            clock.crest = l.crest
        } else {
            val va = ValueAnimator.ofFloat(from, l.crest)
            va.duration = dur
            va.interpolator = ease
            va.addUpdateListener { clock.crest = it.animatedValue as Float }
            va.start()
        }
    }

    private fun finishSwap(old: List<ObjectAnimator>, inB: ImageView, inF: ImageView, outB: ImageView, outF: ImageView) {
        old.forEach { it.cancel() }
        outB.setImageDrawable(null); outF.setImageDrawable(null)
        backCur = inB; frontCur = inF
        backNext = outB; frontNext = outF
    }

    // ---------------------------------------------------------------- gear + row

    private fun buildGear() {
        val size = dpi(52)
        val btn = FrameLayout(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(0x33FFFFFF)
        btn.background = g
        btn.isFocusable = true
        btn.isClickable = true
        val m = dpi(13)
        btn.addView(GearView(this), FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(m, m, m, m) })
        btn.setOnFocusChangeListener { v, has ->
            g.setColor(if (has) 0x80FFFFFF.toInt() else 0x33FFFFFF)
            val s = if (has) 1.18f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(d(240)).setInterpolator(ease).start()
        }
        btn.setOnClickListener { openSettings() }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        lp.rightMargin = dpi(64); lp.topMargin = dpi(40)
        content.addView(btn, lp)
    }

    private fun buildRow() {
        rowScroll = HorizontalScrollView(this)
        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.overScrollMode = View.OVER_SCROLL_NEVER
        rowScroll.clipToPadding = false
        rowScroll.clipChildren = false
        rowScroll.setPadding(dpi(64), dpi(56), dpi(64), dpi(56))
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        rowScroll.addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        lp.bottomMargin = dpi(24)
        content.addView(rowScroll, lp)
    }

    private fun firstCard(): View? = row.getChildAt(0)?.tag as? View

    private fun populateRow(animateIn: Boolean) {
        row.removeAllViews()
        var i = 0
        for (f in FIXED) {
            val app = resolve(f)
            if (app == null && !store.on(K.SHOW_MISSING)) continue
            row.addView(makeTile(f, app, i++, animateIn))
        }
        if (store.on(K.ALL_APPS_TILE)) row.addView(makeAllTile(i, animateIn))
        rowScroll.post { firstCard()?.requestFocus() }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun resolve(f: Fixed): AppEntry? {
        apps.firstOrNull { it.pkg in f.pkgs }?.let { return it }
        return apps.firstOrNull { a ->
            val n = norm(a.label)
            f.hints.any { h -> if (h.startsWith("=")) n == h.substring(1) else n.contains(h) }
        }
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) }
    }

    private fun fresh(dr: Drawable): Drawable = dr.constantState?.newDrawable(resources)?.mutate() ?: dr

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = Color.red(a) + (Color.red(b) - Color.red(a)) * f
        val g = Color.green(a) + (Color.green(b) - Color.green(a)) * f
        val bl = Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f
        return Color.rgb(r.toInt(), g.toInt(), bl.toInt())
    }

    private class Parts(val cell: LinearLayout, val card: FrameLayout, val glow: View, val ring: View, val sheen: View, val label: TextView)

    /** Card + glow + ring + sheen + label, wired for the focus effect. */
    private fun shell(color: Int, labelText: String, radius: Float, onClick: (View) -> Unit): Parts {
        val w = dpi(208); val h = dpi(117)
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.clipChildren = false
        val clp = LinearLayout.LayoutParams(w, WRAP)
        clp.marginEnd = dpi(28)
        cell.layoutParams = clp

        val holder = FrameLayout(this)
        holder.clipChildren = false

        // glow: a big soft radial halo in the app's colour, squashed into an ellipse, sitting behind the card
        val gs = (w * 1.55f).toInt()
        val glow = View(this)
        val gd = GradientDrawable()
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        gd.setGradientRadius(gs / 2f)
        gd.setColors(intArrayOf(withAlpha(color, 215), withAlpha(color, 95), withAlpha(color, 0)))
        glow.background = gd
        glow.alpha = 0f
        glow.scaleX = 0.85f
        glow.scaleY = 0.5f
        holder.addView(glow, FrameLayout.LayoutParams(gs, gs, Gravity.CENTER))

        val card = FrameLayout(this)
        card.outlineProvider = roundOutline(radius)
        card.clipToOutline = true
        card.elevation = dp(3)
        holder.addView(card, FrameLayout.LayoutParams(w, h))

        val lab = tv(labelText, 15f, 0f, "sans-serif-medium")
        lab.gravity = Gravity.CENTER
        lab.maxLines = 1
        lab.ellipsize = TextUtils.TruncateAt.END
        lab.setPadding(0, dpi(14), 0, 0)
        lab.setShadowLayer(dp(8), 0f, dp(1), 0x99000000.toInt())

        cell.addView(holder, LinearLayout.LayoutParams(w, h))
        cell.addView(lab, LinearLayout.LayoutParams(w, WRAP))
        cell.tag = card
        return Parts(cell, card, glow, View(this), View(this), lab)
    }

    private fun finishCard(p: Parts, color: Int, radius: Float, w: Int, h: Int, onClick: (View) -> Unit): View {
        val card = p.card
        // glossy top highlight
        val gloss = View(this)
        gloss.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x2EFFFFFF, 0x00FFFFFF))
        card.addView(gloss, FrameLayout.LayoutParams(MATCH, h / 2))
        // sheen
        val sheen = View(this)
        sheen.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF))
        sheen.alpha = 0f
        card.addView(sheen, FrameLayout.LayoutParams(w, MATCH))
        // ring
        val ring = View(this)
        val rg = GradientDrawable()
        rg.setColor(Color.TRANSPARENT)
        rg.setStroke(dpi(2), Color.WHITE)
        rg.cornerRadius = radius
        ring.background = rg
        ring.alpha = 0f
        card.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))

        card.isFocusable = true
        card.isClickable = true
        card.setOnFocusChangeListener { _, has -> focusFx(card, p.glow, ring, sheen, p.label, has, w, h, p.cell) }
        card.setOnClickListener { onClick(it) }
        return p.cell
    }

    private fun focusFx(card: View, glow: View, ring: View, sheen: View, label: TextView, has: Boolean, w: Int, h: Int, cell: View) {
        card.animate().scaleX(if (has) 1.16f else 1f).scaleY(if (has) 1.16f else 1f)
            .translationZ(if (has) dp(26) else 0f).setDuration(d(300)).setInterpolator(ease).start()
        val glowOn = store.on(K.GLOW)
        glow.animate().alpha(if (has && glowOn) GLOW_A[store.i(K.GLOW_STR)] else 0f)
            .scaleX(if (has) 1.1f else 0.85f).scaleY(if (has) 0.66f else 0.5f)
            .setDuration(d(380)).setInterpolator(ease).start()
        ring.animate().alpha(if (has) 0.95f else 0f).setDuration(d(220)).start()
        label.animate().alpha(if (has) 1f else 0f).translationY(if (has) h * 0.08f else 0f)
            .setDuration(d(260)).setInterpolator(ease).start()
        sheen.animate().cancel()
        if (has) {
            if (store.on(K.SHEEN) && !store.on(K.PERF)) {
                sheen.translationX = -w.toFloat()
                sheen.alpha = 1f
                sheen.animate().translationX(w.toFloat()).setDuration(850).setInterpolator(LinearInterpolator())
                    .withEndAction { sheen.alpha = 0f }.start()
            }
            val target = rowScroll.paddingLeft + cell.left + cell.width / 2 - rowScroll.width / 2
            rowScroll.smoothScrollTo(maxOf(0, target), 0)
        } else {
            sheen.alpha = 0f
        }
    }

    private fun enter(cell: View, index: Int, animateIn: Boolean) {
        if (!animateIn || d(1) == 0L) return
        cell.alpha = 0f
        cell.translationY = dp(36)
        cell.animate().alpha(1f).translationY(0f).setStartDelay(150L + index * 70L)
            .setDuration(d(650)).setInterpolator(ease).start()
    }

    private fun makeTile(f: Fixed, app: AppEntry?, index: Int, animateIn: Boolean): View {
        val w = dpi(208); val h = dpi(117); val r = dp(18)
        val click: (View) -> Unit = { v -> if (app != null) launch(app, v) else openStore(f) }
        val p = shell(f.color, f.name, r, click)
        val bgd = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(f.color, Color.WHITE, .10f), mix(f.color, Color.BLACK, .45f))
        )
        bgd.cornerRadius = r
        p.card.background = bgd

        val banner = app?.banner
        if (app != null && banner != null) {
            val img = ImageView(this)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setImageDrawable(fresh(banner))
            p.card.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        } else if (app != null) {
            val img = ImageView(this)
            img.setImageDrawable(fresh(app.icon))
            p.card.addView(img, FrameLayout.LayoutParams(dpi(76), dpi(76), Gravity.CENTER))
        } else {
            p.card.alpha = 0.55f
            val t = tv(f.name + "\nNot installed", 15f, 1f, "sans-serif-medium")
            t.gravity = Gravity.CENTER
            p.card.addView(t, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val cell = finishCard(p, f.color, r, w, h, click)
        enter(cell, index, animateIn)
        return cell
    }

    private fun makeAllTile(index: Int, animateIn: Boolean): View {
        val w = dpi(208); val h = dpi(117); val r = dp(18)
        val p = shell(0xFFB8C4FF.toInt(), "All Apps", r) { }
        val bgd = GradientDrawable()
        bgd.setColor(0x2EFFFFFF)
        bgd.setStroke(dpi(1), 0x55FFFFFF)
        bgd.cornerRadius = r
        p.card.background = bgd
        p.card.addView(DotsView(this), FrameLayout.LayoutParams(dpi(46), dpi(46), Gravity.CENTER))
        val cell = finishCard(p, 0xFFB8C4FF.toInt(), r, w, h) { openAllApps() }
        enter(cell, index, animateIn)
        return cell
    }

    private fun launch(a: AppEntry, from: View) {
        try {
            val o = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            val i = Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i, o.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${a.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openStore(f: Fixed) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(f.name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "${f.name} isn't installed", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- app list

    private fun refreshApps(first: Boolean) {
        bg.execute {
            val list = try { loadApps() } catch (e: Throwable) { emptyList() }
            ui.post {
                if (!isDestroyed) {
                    val s = list.joinToString("|") { it.pkg }
                    apps = list
                    if (first || s != sig) {
                        sig = s
                        if (overlay == null) populateRow(first)
                    }
                }
            }
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val out = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo
                val pkg = ai.packageName
                if (pkg == packageName || out.containsKey(pkg)) continue
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { continue }
                val banner = try { ai.loadBanner(pm) } catch (e: Exception) { null }
                out[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), intent, icon, banner)
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    // ---------------------------------------------------------------- overlays

    private fun showOverlay(v: View) {
        if (overlay != null) return
        resetIdle()
        ui.removeCallbacks(idleRun)
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        v.alpha = 0f
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.animate().alpha(1f).setDuration(d(260)).start()
        overlay = v
    }

    private fun closeOverlay() {
        val v = overlay ?: return
        overlay = null
        v.animate().alpha(0f).setDuration(d(200)).withEndAction { root.removeView(v) }.start()
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        populateRow(false)
        resetIdle()
    }

    private fun openSettings() {
        showOverlay(SettingsPanel(this, store) { applySettings() }.build())
    }

    private fun openAllApps() {
        val p = FrameLayout(this)
        p.setBackgroundColor(0xF0080A10.toInt())
        p.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dpi(64), dpi(40), dpi(64), 0)
        col.addView(tv("All Apps", 30f, 1f, "sans-serif-light"))
        val sub = tv("Select an app to open it. Back to return.", 14f, 0.6f, "sans-serif")
        sub.setPadding(0, dpi(4), 0, dpi(14))
        col.addView(sub)
        val grid = GridLayout(this)
        grid.columnCount = 6
        for (a in apps) {
            val lp = GridLayout.LayoutParams()
            lp.width = dpi(150); lp.height = dpi(118)
            lp.setMargins(dpi(8), dpi(8), dpi(8), dpi(8))
            grid.addView(appItem(a), lp)
        }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dpi(16), 0, dpi(40))
        scroll.addView(grid)
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        p.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        showOverlay(p)
        grid.getChildAt(0)?.requestFocus()
    }

    private fun appItem(a: AppEntry): View {
        val cell = FrameLayout(this)
        val g = GradientDrawable()
        g.cornerRadius = dp(14)
        g.setColor(0x0DFFFFFF)
        cell.background = g
        cell.isFocusable = true
        cell.isClickable = true
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.gravity = Gravity.CENTER
        val img = ImageView(this)
        img.setImageDrawable(fresh(a.icon))
        inner.addView(img, LinearLayout.LayoutParams(dpi(56), dpi(56)))
        val l = tv(a.label, 12f, 0.9f, "sans-serif")
        l.gravity = Gravity.CENTER
        l.maxLines = 1
        l.ellipsize = TextUtils.TruncateAt.END
        l.setPadding(dpi(6), dpi(8), dpi(6), 0)
        inner.addView(l, LinearLayout.LayoutParams(MATCH, WRAP))
        cell.addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
        cell.setOnFocusChangeListener { v, has ->
            g.setColor(if (has) 0x40FFFFFF else 0x0DFFFFFF)
            val s = if (has) 1.07f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(d(200)).setInterpolator(ease).start()
        }
        cell.setOnClickListener { launch(a, it) }
        return cell
    }
}
