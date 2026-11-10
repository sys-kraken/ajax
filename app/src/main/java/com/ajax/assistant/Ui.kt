package com.ajax.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Colours and small view builders shared by the setup screen and the overlay, in the style of the
 * phone's own Settings: true black for OLED, rounded groups of rows. No AndroidX.
 */
object Ui {
    const val BACKGROUND = 0xFF000000.toInt()
    const val SURFACE = 0xFF1A1A1C.toInt()
    const val SURFACE_RAISED = 0xFF2C2C2E.toInt()
    const val HAIRLINE = 0xFF2E2E30.toInt()
    const val TEXT = 0xFFFAFAFA.toInt()
    const val MUTED = 0xFF9C9CA1.toInt()
    const val ACCENT = 0xFF3E91FF.toInt()
    private const val PRESSED = 0x33FFFFFF
    private const val GROUP_RADIUS = 26

    private val MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dp(radiusDp).toFloat()
        if (stroke != null) setStroke(ctx.dp(1).coerceAtLeast(1), stroke)
    }

    fun text(ctx: Context, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = MEDIUM
    }

    /** The small label above a group of rows. */
    fun header(ctx: Context, title: String) = text(ctx, 13f, MUTED, bold = true).apply {
        text = title
        setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(8))
    }

    /** A rounded group of rows. Add rows with [addRow] so they get dividers. */
    fun group(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, GROUP_RADIUS)
        clipToOutline = true
    }

    /** A group with room around its content, for anything that isn't a row. */
    fun card(ctx: Context) = group(ctx).apply {
        val p = ctx.dp(20)
        setPadding(p, p, p, p)
    }

    /** Vertical spacing below a child of a vertical list. */
    fun below(ctx: Context, bottomDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = ctx.dp(bottomDp) }

    /** One row of a group: a title, an optional line under it, and optionally something at the end. */
    class Row(val view: LinearLayout, val title: TextView, val sub: TextView) {
        fun sub(text: CharSequence, color: Int = MUTED) {
            sub.text = text
            sub.setTextColor(color)
            sub.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }

        fun onClick(action: () -> Unit) {
            view.background = RippleDrawable(ColorStateList.valueOf(PRESSED), null, GradientDrawable().apply { setColor(TEXT) })
            view.setOnClickListener { action() }
        }
    }

    fun row(ctx: Context, title: String, sub: String = "", subColor: Int = MUTED, end: View? = null): Row {
        val titleView = text(ctx, 17f).apply { text = title }
        val subView = text(ctx, 14f, subColor)
        val words = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(subView)
        }
        val view = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(60)
            setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
            addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (end != null) addView(end)
        }
        return Row(view, titleView, subView).also { it.sub(sub, subColor) }
    }

    /** Adds [view] to [group], with an inset divider if it isn't the first. */
    fun addRow(group: LinearLayout, view: View) {
        val ctx = group.context
        if (group.childCount > 0) {
            group.addView(View(ctx).apply { setBackgroundColor(HAIRLINE) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1).coerceAtLeast(1),
            ).apply { marginStart = ctx.dp(20); marginEnd = ctx.dp(20) })
        }
        group.addView(view)
    }

    fun switch(ctx: Context) = Switch(ctx).apply {
        val checked = intArrayOf(android.R.attr.state_checked)
        val other = intArrayOf()
        thumbTintList = ColorStateList.valueOf(TEXT)
        trackTintList = ColorStateList(arrayOf(checked, other), intArrayOf(ACCENT, 0xFF6A6A6E.toInt()))
    }

    fun button(ctx: Context, label: String) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        typeface = MEDIUM
        stateListAnimator = null
        setTextColor(TEXT)
        background = RippleDrawable(ColorStateList.valueOf(ACCENT), rounded(ctx, SURFACE, GROUP_RADIUS), null)
    }
}
