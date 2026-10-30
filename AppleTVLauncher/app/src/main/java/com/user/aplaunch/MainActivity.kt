package com.user.aplaunch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class AppEntry(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val icon: Drawable,
    val banner: Drawable?
)

class MainActivity : Activity() {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ROTATE_MS = 60_000L
        val PREFERRED = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.wbd.stream", "com.apple.atve.androidtv.appletv",
            "com.plexapp.android", "org.xbmc.kodi", "com.spotify.tv.android", "tv.twitch.android.app",
            "com.google.android.youtube.tvmusic"
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var wallHost: FrameLayout
    private lateinit var wallFront: ImageView
    private lateinit var wallBack: ImageView
    private lateinit var content: FrameLayout
    private lateinit var header: LinearLayout
    private lateinit var rowScroll: HorizontalScrollView
    private lateinit var row: LinearLayout

    private var picker: FrameLayout? = null
    private val badges = HashMap<String, View>()
    private var apps: List<AppEntry> = emptyList()
    private var sig = ""
    private var wallIndex = 0
    private var wallBusy = false
    private var drift: ObjectAnimator? = null

    private val rotate = object : Runnable {
        override fun run() {
            nextWallpaper()
            ui.postDelayed(this, ROTATE_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        root = FrameLayout(this)
        wallHost = FrameLayout(this)
        wallFront = wallView()
        wallBack = wallView()
        wallHost.addView(wallFront, FrameLayout.LayoutParams(MATCH, MATCH))
        wallHost.addView(wallBack, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(wallHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x66000000, 0x00000000, 0x00000000, 0xCC000000.toInt())
        )
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        content = FrameLayout(this)
        content.clipChildren = false
        buildHeader()
        buildRow()
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        nextWallpaper()
        refreshApps(true)
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(rotate)
        ui.postDelayed(rotate, ROTATE_MS)
        drift?.resume()
        refreshApps(false)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(rotate)
        drift?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        bg.shutdownNow()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (picker != null) closePicker()
        rowScroll.smoothScrollTo(0, 0)
        firstCard()?.requestFocus()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (picker != null) closePicker()
        // A launcher never goes "back".
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && picker == null) {
            openPicker()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
    private fun dpi(v: Number): Int = dp(v).toInt()

    private fun fresh(d: Drawable): Drawable = d.constantState?.newDrawable(resources)?.mutate() ?: d

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = Color.red(a) + (Color.red(b) - Color.red(a)) * f
        val g = Color.green(a) + (Color.green(b) - Color.green(a)) * f
        val bl = Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f
        return Color.rgb(r.toInt(), g.toInt(), bl.toInt())
    }

    private fun avgColor(d: Drawable): Int {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 16, 16)
        d.draw(Canvas(bmp))
        val px = IntArray(256)
        bmp.getPixels(px, 0, 16, 0, 0, 16, 16)
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (p in px) {
            if (Color.alpha(p) < 40) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        bmp.recycle()
        if (n == 0L) return Color.rgb(58, 63, 75)
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height, r)
        }
    }

    private fun tv(s: String, size: Float, alpha: Float, family: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(Color.WHITE)
        t.alpha = alpha
        t.typeface = Typeface.create(family, Typeface.NORMAL)
        return t
    }

    private fun wallView(): ImageView {
        val v = ImageView(this)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        return v
    }

    private fun firstCard(): View? = (row.getChildAt(0) as? ViewGroup)?.getChildAt(0)

    // ---------------------------------------------------------------- header

    private fun buildHeader() {
        header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL

        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 58f
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        clock.setShadowLayer(dp(10), 0f, dp(2), 0x66000000)
        header.addView(clock)

        val date = TextClock(this)
        date.format12Hour = "EEEE d MMMM"
        date.format24Hour = "EEEE d MMMM"
        date.textSize = 19f
        date.setTextColor(0xE6FFFFFF.toInt())
        date.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        date.setShadowLayer(dp(8), 0f, dp(1), 0x66000000)
        header.addView(date)

        val hp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        hp.leftMargin = dpi(64); hp.topMargin = dpi(34)
        content.addView(header, hp)

        // settings button (top right)
        val size = dpi(52)
        val btn = FrameLayout(this)
        val bgd = GradientDrawable()
        bgd.shape = GradientDrawable.OVAL
        bgd.setColor(0x33FFFFFF)
        btn.background = bgd
        btn.isFocusable = true
        btn.isClickable = true
        val gear = GearView(this)
        btn.addView(gear, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            val m = dpi(13); setMargins(m, m, m, m)
        })
        btn.setOnFocusChangeListener { v, has ->
            bgd.setColor(if (has) 0x80FFFFFF.toInt() else 0x33FFFFFF)
            val s = if (has) 1.18f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(240).setInterpolator(ease).start()
        }
        btn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show()
            }
        }
        val bp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        bp.rightMargin = dpi(64); bp.topMargin = dpi(40)
        content.addView(btn, bp)

        header.alpha = 0f
        header.animate().alpha(1f).setDuration(1000).start()
    }

    // ---------------------------------------------------------------- app row

    private fun buildRow() {
        rowScroll = HorizontalScrollView(this)
        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.overScrollMode = View.OVER_SCROLL_NEVER
        rowScroll.clipToPadding = false
        rowScroll.clipChildren = false
        rowScroll.setPadding(dpi(64), dpi(52), dpi(64), dpi(52))
        row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        rowScroll.addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
        val lp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        lp.bottomMargin = dpi(40)
        content.addView(rowScroll, lp)
    }

    private fun populateRow(animateIn: Boolean) {
        row.removeAllViews()
        val favs = favorites()
        favs.forEachIndexed { i, a -> row.addView(makeTile(a, i, animateIn)) }
        row.addView(makeAddTile(favs.size, animateIn))
        rowScroll.post { firstCard()?.requestFocus() }
    }

    private fun focusFx(
        card: View, ring: View, sheen: View, label: TextView,
        has: Boolean, w: Int, h: Int, cell: View
    ) {
        val s = if (has) 1.16f else 1f
        card.animate().scaleX(s).scaleY(s).translationZ(if (has) dp(26) else 0f)
            .setDuration(300).setInterpolator(ease).start()
        ring.animate().alpha(if (has) 0.95f else 0f).setDuration(220).start()
        label.animate().alpha(if (has) 1f else 0f).translationY(if (has) h * 0.08f else 0f)
            .setDuration(260).setInterpolator(ease).start()
        sheen.animate().cancel()
        if (has) {
            sheen.translationX = -w.toFloat()
            sheen.alpha = 1f
            sheen.animate().translationX(w.toFloat()).setDuration(850)
                .setInterpolator(LinearInterpolator())
                .withEndAction { sheen.alpha = 0f }.start()
            val target = rowScroll.paddingLeft + cell.left + cell.width / 2 - rowScroll.width / 2
            rowScroll.smoothScrollTo(maxOf(0, target), 0)
        } else {
            sheen.alpha = 0f
        }
    }

    private fun cellOf(w: Int): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.clipChildren = false
        val lp = LinearLayout.LayoutParams(w, WRAP)
        lp.marginEnd = dpi(28)
        cell.layoutParams = lp
        return cell
    }

    private fun label(text: String): TextView {
        val l = tv(text, 15f, 0f, "sans-serif-medium")
        l.gravity = Gravity.CENTER
        l.maxLines = 1
        l.ellipsize = TextUtils.TruncateAt.END
        l.setPadding(0, dpi(14), 0, 0)
        l.setShadowLayer(dp(8), 0f, dp(1), 0x99000000.toInt())
        return l
    }

    private fun overlays(card: FrameLayout, w: Int, r: Float): Pair<View, View> {
        val sheen = View(this)
        sheen.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x44FFFFFF, 0x00FFFFFF)
        )
        sheen.alpha = 0f
        card.addView(sheen, FrameLayout.LayoutParams(w, MATCH))
        val ring = View(this)
        val g = GradientDrawable()
        g.setColor(Color.TRANSPARENT)
        g.setStroke(dpi(2), Color.WHITE)
        g.cornerRadius = r
        ring.background = g
        ring.alpha = 0f
        card.addView(ring, FrameLayout.LayoutParams(MATCH, MATCH))
        return Pair(ring, sheen)
    }

    private fun enter(cell: View, index: Int, animateIn: Boolean) {
        if (!animateIn) return
        cell.alpha = 0f
        cell.translationY = dp(36)
        cell.animate().alpha(1f).translationY(0f).setStartDelay(150L + index * 70L)
            .setDuration(650).setInterpolator(ease).start()
    }

    private fun makeTile(a: AppEntry, index: Int, animateIn: Boolean): View {
        val w = dpi(208); val h = dpi(117); val r = dp(18)
        val cell = cellOf(w)
        val card = FrameLayout(this)
        val base = avgColor(fresh(a.icon))
        val bgd = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(base, Color.WHITE, .12f), mix(base, Color.BLACK, .40f))
        )
        bgd.cornerRadius = r
        card.background = bgd
        card.outlineProvider = roundOutline(r)
        card.clipToOutline = true
        card.elevation = dp(3)

        val img = ImageView(this)
        val banner = a.banner
        if (banner != null) {
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.setImageDrawable(fresh(banner))
            card.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            img.setImageDrawable(fresh(a.icon))
            card.addView(img, FrameLayout.LayoutParams(dpi(76), dpi(76), Gravity.CENTER))
        }
        val (ring, sheen) = overlays(card, w, r)
        val lab = label(a.label)

        card.isFocusable = true
        card.isClickable = true
        card.setOnFocusChangeListener { _, has -> focusFx(card, ring, sheen, lab, has, w, h, cell) }
        card.setOnClickListener { launch(a, it) }
        card.setOnLongClickListener { openPicker(); true }

        cell.addView(card, LinearLayout.LayoutParams(w, h))
        cell.addView(lab, LinearLayout.LayoutParams(w, WRAP))
        enter(cell, index, animateIn)
        return cell
    }

    private fun makeAddTile(index: Int, animateIn: Boolean): View {
        val w = dpi(208); val h = dpi(117); val r = dp(18)
        val cell = cellOf(w)
        val card = FrameLayout(this)
        val bgd = GradientDrawable()
        bgd.setColor(0x26FFFFFF)
        bgd.setStroke(dpi(1), 0x55FFFFFF)
        bgd.cornerRadius = r
        card.background = bgd
        card.outlineProvider = roundOutline(r)
        card.clipToOutline = true
        card.elevation = dp(3)
        val plus = tv("+", 40f, 0.9f, "sans-serif-thin")
        plus.gravity = Gravity.CENTER
        card.addView(plus, FrameLayout.LayoutParams(MATCH, MATCH))
        val (ring, sheen) = overlays(card, w, r)
        val lab = label("Edit apps")
        card.isFocusable = true
        card.isClickable = true
        card.setOnFocusChangeListener { _, has -> focusFx(card, ring, sheen, lab, has, w, h, cell) }
        card.setOnClickListener { openPicker() }
        cell.addView(card, LinearLayout.LayoutParams(w, h))
        cell.addView(lab, LinearLayout.LayoutParams(w, WRAP))
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

    // ---------------------------------------------------------------- apps + favourites

    private fun refreshApps(first: Boolean) {
        bg.execute {
            val list = try { loadApps() } catch (e: Throwable) { emptyList() }
            ui.post {
                if (!isDestroyed) {
                    val s = list.joinToString("|") { it.pkg }
                    apps = list
                    if (first || s != sig) {
                        sig = s
                        if (picker == null) populateRow(first)
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
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg) ?: continue
                val icon = try { ri.loadIcon(pm) } catch (e: Exception) { continue }
                val banner = try { ai.loadBanner(pm) } catch (e: Exception) { null }
                out[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), intent, icon, banner)
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    private fun savedFavs(): MutableList<String> =
        (prefs.getString("favs", null) ?: "").split("\n").filter { it.isNotEmpty() }.toMutableList()

    private fun saveFavs(l: List<String>) = prefs.edit().putString("favs", l.joinToString("\n")).apply()

    private fun favorites(): List<AppEntry> {
        val byPkg = apps.associateBy { it.pkg }
        if (!prefs.contains("favs")) saveFavs(defaultFavorites())
        return savedFavs().mapNotNull { byPkg[it] }
    }

    private fun toggleFav(pkg: String): Boolean {
        val l = savedFavs()
        val on = if (l.remove(pkg)) false else { l.add(pkg); true }
        saveFavs(l)
        return on
    }

    private fun isNoise(p: String) =
        p.startsWith("com.android.") || p == "android" || p.contains("tvlauncher") ||
            p.contains("launcherx") || p.contains("setupwizard") || p == "com.google.android.gms"

    private fun defaultFavorites(): List<String> {
        val installed = apps.map { it.pkg }.toSet()
        val picks = LinkedHashSet<String>()
        for (p in topUsed()) if (picks.size < 8 && p in installed && !isNoise(p)) picks.add(p)
        for (p in PREFERRED) if (picks.size < 8 && p in installed) picks.add(p)
        return picks.toList()
    }

    @Suppress("DEPRECATION")
    private fun topUsed(): List<String> {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
            if (mode != AppOpsManager.MODE_ALLOWED) return emptyList()
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600 * 1000, now)
                .groupBy { it.packageName }
                .mapValues { e -> e.value.sumOf { it.totalTimeInForeground } }
                .entries.sortedByDescending { it.value }.map { it.key }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- picker

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = tv(text, 15f, 1f, "sans-serif-medium")
        t.setPadding(dpi(22), dpi(10), dpi(22), dpi(10))
        val g = GradientDrawable()
        g.cornerRadius = dp(40)
        g.setColor(0x33FFFFFF)
        t.background = g
        t.isFocusable = true
        t.isClickable = true
        t.setOnFocusChangeListener { v, has ->
            g.setColor(if (has) 0x99FFFFFF.toInt() else 0x33FFFFFF)
            t.setTextColor(if (has) Color.BLACK else Color.WHITE)
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(200).start()
        }
        t.setOnClickListener { onClick() }
        return t
    }

    private fun pickItem(a: AppEntry): View {
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
        val check = CheckView(this)
        check.visibility = if (a.pkg in savedFavs()) View.VISIBLE else View.INVISIBLE
        val cp = FrameLayout.LayoutParams(dpi(24), dpi(24), Gravity.TOP or Gravity.END)
        cp.topMargin = dpi(8); cp.rightMargin = dpi(8)
        cell.addView(check, cp)
        badges[a.pkg] = check
        cell.setOnFocusChangeListener { v, has ->
            g.setColor(if (has) 0x40FFFFFF else 0x0DFFFFFF)
            val s = if (has) 1.07f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(ease).start()
        }
        cell.setOnClickListener {
            check.visibility = if (toggleFav(a.pkg)) View.VISIBLE else View.INVISIBLE
        }
        return cell
    }

    private fun openPicker() {
        if (picker != null) return
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        badges.clear()
        val p = FrameLayout(this)
        p.setBackgroundColor(0xF0080A10.toInt())
        p.isClickable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dpi(64), dpi(40), dpi(64), 0)
        col.addView(tv("Your Apps", 30f, 1f, "sans-serif-light"))
        val sub = tv("OK adds or removes an app on your home row. Back when you're done.", 14f, 0.6f, "sans-serif")
        sub.setPadding(0, dpi(4), 0, dpi(14))
        col.addView(sub)
        val auto = pill("Fill from my most used") {
            saveFavs(defaultFavorites())
            val on = savedFavs().toSet()
            for ((k, v) in badges) v.visibility = if (k in on) View.VISIBLE else View.INVISIBLE
        }
        col.addView(auto, LinearLayout.LayoutParams(WRAP, WRAP))

        val grid = GridLayout(this)
        grid.columnCount = 6
        for (a in apps) {
            val lp = GridLayout.LayoutParams()
            lp.width = dpi(150); lp.height = dpi(118)
            lp.setMargins(dpi(8), dpi(8), dpi(8), dpi(8))
            grid.addView(pickItem(a), lp)
        }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dpi(16), 0, dpi(40))
        scroll.addView(grid)
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        p.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        p.alpha = 0f
        root.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        p.animate().alpha(1f).setDuration(260).start()
        picker = p
        grid.getChildAt(0)?.requestFocus()
    }

    private fun closePicker() {
        val p = picker ?: return
        picker = null
        p.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(p) }.start()
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        populateRow(false)
    }

    // ---------------------------------------------------------------- wallpapers

    private val assetWalls: List<String> by lazy {
        try {
            (assets.list("wallpapers") ?: emptyArray())
                .filter { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) || it.endsWith(".png", true) || it.endsWith(".webp", true) }
                .sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeAsset(name: String): Bitmap {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= 1920) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return assets.open("wallpapers/$name").use { BitmapFactory.decodeStream(it, null, o2)!! }
    }

    private fun nextWallpaper() {
        if (wallBusy) return
        wallBusy = true
        val n = if (assetWalls.isNotEmpty()) assetWalls.size else Scenes.count
        val i = wallIndex % n
        wallIndex++
        bg.execute {
            val bmp = try {
                if (assetWalls.isNotEmpty()) decodeAsset(assetWalls[i]) else Scenes.render(i, 1600, 900)
            } catch (e: Throwable) {
                null
            }
            ui.post {
                wallBusy = false
                if (bmp != null && !isDestroyed) showWall(bmp)
            }
        }
    }

    private fun showWall(bmp: Bitmap) {
        val incoming = wallBack
        val outgoing = wallFront
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        incoming.scaleX = 1.03f; incoming.scaleY = 1.03f; incoming.translationX = 0f
        wallHost.bringChildToFront(incoming)

        val fade = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f)
        fade.duration = 2000
        val oldDrift = drift
        val newDrift = ObjectAnimator.ofPropertyValuesHolder(
            incoming,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.03f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.03f, 1.12f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -dp(30))
        )
        newDrift.duration = 90_000
        newDrift.interpolator = LinearInterpolator()
        fade.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                oldDrift?.cancel()
                outgoing.setImageDrawable(null)
                wallFront = incoming
                wallBack = outgoing
            }
        })
        drift = newDrift
        fade.start()
        newDrift.start()
    }
}

/** Gear glyph drawn in code so it renders on any TV font set. */
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

/** Blue badge with a white check mark. */
class CheckView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 132, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawCircle(w / 2, h / 2, min(w, h) / 2, fill)
        stroke.strokeWidth = w * 0.1f
        val path = Path()
        path.moveTo(w * 0.27f, h * 0.52f)
        path.lineTo(w * 0.43f, h * 0.68f)
        path.lineTo(w * 0.74f, h * 0.34f)
        c.drawPath(path, stroke)
    }
}
