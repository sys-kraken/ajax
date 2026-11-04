package com.ajax.assistant

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Colours and small view builders shared by the setup screen and the overlay. No AndroidX. */
object Ui {
    const val BACKGROUND = 0xFF0A0A0B.toInt()
    const val SURFACE = 0xFF151517.toInt()
    const val SURFACE_RAISED = 0xFF232326.toInt()
    const val HAIRLINE = 0xFF2B2B2F.toInt()
    const val TEXT = 0xFFF5F5F7.toInt()
    const val MUTED = 0xFF98989F.toInt()
    const val GOOD = 0xFF8FD6AE.toInt()
    const val ATTENTION = 0xFFE6C583.toInt()

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

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, 18, HAIRLINE)
        val p = ctx.dp(18)
        setPadding(p, p, p, p)
    }

    /** Vertical spacing below a child of a vertical list. */
    fun below(ctx: Context, bottomDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = ctx.dp(bottomDp) }

    fun button(ctx: Context, label: String, primary: Boolean) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = MEDIUM
        stateListAnimator = null
        minHeight = ctx.dp(48)
        setTextColor(if (primary) BACKGROUND else TEXT)
        background = RippleDrawable(
            ColorStateList.valueOf(if (primary) MUTED else HAIRLINE),
            rounded(ctx, if (primary) TEXT else SURFACE_RAISED, 14),
            null,
        )
    }

    fun pill(ctx: Context, label: String, color: Int) = text(ctx, 12f, color, bold = true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(10), ctx.dp(3))
        background = rounded(ctx, SURFACE_RAISED, 12)
    }
}
