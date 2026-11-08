package com.threedstwo.tv.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.threedstwo.tv.R

/** Tiny programmatic UI kit for a 10-foot, D-pad-only interface. */
object Ui {
    fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun scroll(ctx: Context, child: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        addView(child)
    }

    fun title(ctx: Context, text: String, size: Float = 34f) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ctx.getColor(R.color.text))
        setPadding(0, 0, 0, dp(ctx, 12f))
    }

    fun text(ctx: Context, text: String, size: Float = 18f, color: Int = R.color.text_dim) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(ctx.getColor(color))
        setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(ctx.getColor(R.color.text))
        setBackgroundResource(R.drawable.row_bg)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        val p = dp(ctx, 14f)
        setPadding(p * 2, p, p * 2, p)
        isFocusable = true
        isFocusableInTouchMode = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 6f)
        }
    }
}

/**
 * A list of focusable rows. "Choice" rows change their value with ENTER (next) and D-pad
 * LEFT/RIGHT (previous/next), showing "Label: value" so everything is usable with the TV remote.
 */
class OptionList(private val ctx: Context, val container: LinearLayout) {
    private val refreshers = mutableListOf<() -> Unit>()

    fun header(text: String) {
        container.addView(Ui.text(ctx, text, 20f, R.color.accent).apply { setPadding(0, Ui.dp(ctx, 16f), 0, Ui.dp(ctx, 2f)) })
    }

    fun note(text: String) {
        container.addView(Ui.text(ctx, text, 16f))
    }

    fun action(label: String, onClick: () -> Unit): Button = Ui.button(ctx, label, onClick).also { container.addView(it) }

    fun <T> choice(
        label: String,
        values: List<T>,
        get: () -> T,
        name: (T) -> String = { it.toString() },
        set: (T) -> Unit,
    ): Button {
        lateinit var b: Button
        fun refresh() { b.text = "$label:  ${name(get())}" }
        fun step(delta: Int) {
            val i = values.indexOf(get()).let { if (it < 0) 0 else it }
            set(values[(i + delta + values.size) % values.size])
            refresh()
        }
        b = Ui.button(ctx, label) { step(1) }
        b.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                else -> false
            }
        }
        refresh()
        refreshers += ::refresh
        container.addView(b)
        return b
    }

    fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        choice(label, listOf(true, false), get, { if (it) "Sí" else "No" }, set)

    fun intRange(label: String, range: IntProgression, get: () -> Int, suffix: String = "", set: (Int) -> Unit) =
        choice(label, range.toList(), get, { "$it$suffix" }, set)

    fun refreshAll() = refreshers.forEach { it() }
}
