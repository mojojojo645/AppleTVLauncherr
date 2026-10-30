package com.user.aplaunch

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen settings: categories on the left, rows on the right. OK changes a value, Left goes back to categories. */
class SettingsPanel(
    private val ctx: Context,
    private val s: Store,
    private val onChange: () -> Unit
) {
    private sealed class It
    private class Ch(val key: String, val title: String, val sub: String, val opts: List<String>) : It()
    private class Ac(val title: String, val sub: String, val run: (TextView) -> Unit) : It()

    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val cats = LinkedHashMap<String, List<It>>()
    private val side = ArrayList<TextView>()
    private lateinit var pane: LinearLayout
    private lateinit var paneTitle: TextView
    private var cur = 0
    private var armed = false

    private fun sys(action: String) = Ac(
        when (action) {
            Settings.ACTION_WIFI_SETTINGS -> "Network & Wi-Fi"
            Settings.ACTION_DISPLAY_SETTINGS -> "Display & picture"
            Settings.ACTION_SOUND_SETTINGS -> "Sound"
            Settings.ACTION_BLUETOOTH_SETTINGS -> "Bluetooth & remotes"
            Settings.ACTION_APPLICATION_SETTINGS -> "Apps"
            Settings.ACTION_DATE_SETTINGS -> "Date & time"
            Settings.ACTION_INTERNAL_STORAGE_SETTINGS -> "Storage"
            Settings.ACTION_ACCESSIBILITY_SETTINGS -> "Accessibility"
            Settings.ACTION_INPUT_METHOD_SETTINGS -> "Keyboard"
            Settings.ACTION_LOCALE_SETTINGS -> "Language"
            Settings.ACTION_HOME_SETTINGS -> "Default home app"
            else -> "All Android settings"
        }, "Opens the system page"
    ) {
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    init {
        val onOff = listOf("Off", "On")
        cats["Clock"] = listOf(
            Ch(K.CLOCK_MODE, "Clock style", "Behind the mountains is the depth effect", listOf("Behind the mountains", "In front", "Hidden")),
            Ch(K.CLOCK_SIZE, "Clock size", "", listOf("Small", "Medium", "Large")),
            Ch(K.CLOCK_WEIGHT, "Clock weight", "", listOf("Light", "Medium", "Bold")),
            Ch(K.CLOCK_FMT, "Time format", "", listOf("Follow TV", "12-hour", "24-hour")),
            Ch(K.CLOCK_DATE, "Show date", "", onOff)
        )
        cats["Wallpaper"] = listOf(
            Ch(K.SCENE, "Scene", "Auto cycles through all scenes", listOf("Auto") + Scenes.names),
            Ch(K.ROTATE, "Change scene", "Only when Scene is Auto", listOf("Every 30 seconds", "Every minute", "Every 5 minutes", "Every 15 minutes", "Never")),
            Ch(K.MOTION, "Slow motion", "Gentle parallax drift on the layers", onOff)
        )
        cats["Effects"] = listOf(
            Ch(K.GLOW, "Focus glow", "Coloured halo behind the selected app", onOff),
            Ch(K.GLOW_STR, "Glow strength", "", listOf("Soft", "Normal", "Intense")),
            Ch(K.SHEEN, "Shine sweep", "Light streak across the selected app", onOff),
            Ch(K.ANIM, "Animation speed", "", listOf("Normal", "Fast", "Off")),
            Ch(K.PERF, "Performance mode", "Lighter effects and smaller wallpapers for slow TVs", onOff)
        )
        cats["Always-on display"] = listOf(
            Ch(K.AOD, "Always-on display", "Dims the wallpaper and keeps only the clock when idle", onOff),
            Ch(K.AOD_DELAY, "Start after", "", listOf("1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes")),
            Ch(K.AOD_DIM, "Dimming", "", listOf("Light", "Medium", "Dark")),
            Ch(K.KEEP_ON, "Keep display on", "Stops the TV sleeping or starting the screensaver while on Home", onOff)
        )
        cats["Home row"] = listOf(
            Ch(K.ALL_APPS_TILE, "All Apps tile", "Last tile in the row", onOff),
            Ch(K.SHOW_MISSING, "Show apps I haven't installed", "Dimmed tile that opens the store", onOff)
        )
        cats["System"] = listOf(
            sys(Settings.ACTION_WIFI_SETTINGS), sys(Settings.ACTION_DISPLAY_SETTINGS),
            sys(Settings.ACTION_SOUND_SETTINGS), sys(Settings.ACTION_BLUETOOTH_SETTINGS),
            sys(Settings.ACTION_APPLICATION_SETTINGS), sys(Settings.ACTION_DATE_SETTINGS),
            sys(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), sys(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            sys(Settings.ACTION_INPUT_METHOD_SETTINGS), sys(Settings.ACTION_LOCALE_SETTINGS),
            sys(Settings.ACTION_HOME_SETTINGS), sys(Settings.ACTION_SETTINGS)
        )
        cats["About"] = listOf(
            Ac("Launcher", "Home 2.0") {},
            Ac("Reset launcher settings", "Press OK, then OK again to confirm") { sub ->
                if (!armed) {
                    armed = true
                    sub.text = "Press OK again to reset everything"
                } else {
                    armed = false
                    s.clear()
                    onChange()
                    show(cur)
                }
            }
        )
    }

    fun build(): FrameLayout {
        val root = FrameLayout(ctx)
        root.setBackgroundColor(0xF2090B10.toInt())
        root.isClickable = true

        val h = LinearLayout(ctx)
        h.orientation = LinearLayout.HORIZONTAL
        root.addView(h, FrameLayout.LayoutParams(-1, -1))

        val left = LinearLayout(ctx)
        left.orientation = LinearLayout.VERTICAL
        left.setPadding(ctx.dpi(64), ctx.dpi(48), ctx.dpi(24), 0)
        left.addView(ctx.tv("Settings", 34f, 1f, "sans-serif-light"))
        val gap = View(ctx)
        left.addView(gap, LinearLayout.LayoutParams(1, ctx.dpi(24)))
        cats.keys.forEachIndexed { i, name ->
            val t = ctx.tv(name, 19f, 0.6f, "sans-serif-medium")
            t.setPadding(ctx.dpi(18), ctx.dpi(12), ctx.dpi(18), ctx.dpi(12))
            val g = GradientDrawable()
            g.cornerRadius = ctx.dp(12)
            g.setColor(Color.TRANSPARENT)
            t.background = g
            t.isFocusable = true
            t.isClickable = true
            t.setOnFocusChangeListener { v, has ->
                if (has) { cur = i; show(i) }
                g.setColor(if (has) 0x40FFFFFF else Color.TRANSPARENT)
                t.alpha = if (has || cur == i) 1f else 0.6f
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(180).start()
            }
            t.setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    pane.getChildAt(0)?.requestFocus(); true
                } else false
            }
            side.add(t)
            left.addView(t, LinearLayout.LayoutParams(ctx.dpi(260), -2))
        }
        h.addView(left, LinearLayout.LayoutParams(ctx.dpi(348), -1))

        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.clipToPadding = false
        sv.setPadding(ctx.dpi(24), ctx.dpi(48), ctx.dpi(64), ctx.dpi(48))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        paneTitle = ctx.tv("", 28f, 1f, "sans-serif-light")
        paneTitle.setPadding(0, 0, 0, ctx.dpi(16))
        col.addView(paneTitle)
        pane = LinearLayout(ctx)
        pane.orientation = LinearLayout.VERTICAL
        col.addView(pane, LinearLayout.LayoutParams(-1, -2))
        sv.addView(col)
        h.addView(sv, LinearLayout.LayoutParams(0, -1, 1f))

        show(0)
        root.post { side[0].requestFocus() }
        return root
    }

    private fun show(i: Int) {
        val name = cats.keys.elementAt(i)
        paneTitle.text = name
        side.forEachIndexed { j, v -> v.alpha = if (j == i) 1f else 0.6f }
        pane.removeAllViews()
        for (it in cats.getValue(name)) pane.addView(rowView(it))
    }

    private fun rowView(item: It): View {
        val row = FrameLayout(ctx)
        val g = GradientDrawable()
        g.cornerRadius = ctx.dp(14)
        g.setColor(0x14FFFFFF)
        row.background = g
        row.isFocusable = true
        row.isClickable = true
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = ctx.dpi(8)
        row.layoutParams = lp
        row.setPadding(ctx.dpi(20), ctx.dpi(14), ctx.dpi(20), ctx.dpi(14))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        val title: String
        val sub: String
        when (item) {
            is Ch -> { title = item.title; sub = item.sub }
            is Ac -> { title = item.title; sub = item.sub }
        }
        texts.addView(ctx.tv(title, 18f, 1f, "sans-serif-medium"))
        val subTv = ctx.tv(sub, 13f, 0.55f, "sans-serif")
        if (sub.isNotEmpty()) texts.addView(subTv)
        row.addView(texts, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))

        val value = ctx.tv("", 16f, 0.9f, "sans-serif-medium")
        if (item is Ch) {
            value.text = item.opts[s.i(item.key).coerceIn(0, item.opts.size - 1)]
            row.addView(value, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        }

        row.setOnFocusChangeListener { v, has ->
            g.setColor(if (has) 0x38FFFFFF else 0x14FFFFFF)
            v.animate().scaleX(if (has) 1.015f else 1f).scaleY(if (has) 1.015f else 1f)
                .setDuration(180).setInterpolator(ease).start()
        }
        row.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                side[cur].requestFocus(); true
            } else false
        }
        row.setOnClickListener {
            when (item) {
                is Ch -> {
                    val next = (s.i(item.key) + 1) % item.opts.size
                    s.put(item.key, next)
                    value.text = item.opts[next]
                    onChange()
                }
                is Ac -> item.run(subTv)
            }
        }
        return row
    }
}
