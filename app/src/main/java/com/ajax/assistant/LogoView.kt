package com.ajax.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws block-letter character art (full blocks plus double-line box characters) on a fixed grid.
 * As plain text it can't be trusted: the phone's monospace font has no block characters, the
 * fallback font's are a different width, and rows end up shifted against each other with gaps
 * between them. Here every character gets the same cell and neighbouring cells touch.
 */
class LogoView(ctx: Context, art: String, color: Int) : View(ctx) {
    private val rows = art.lines()
    private val cols = rows.maxOf { it.length }
    private val density = resources.displayMetrics.density
    private val cellW = CELL_W_DP * density
    private val cellH = CELL_H_DP * density
    private val stroke = max(1f, (density * 0.9f).roundToInt().toFloat())
    private val paint = Paint().apply { this.color = color }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(
            (cols * cellW).roundToInt() + paddingLeft + paddingRight,
            (rows.size * cellH).roundToInt() + paddingTop + paddingBottom,
        )
    }

    override fun onDraw(canvas: Canvas) {
        for ((r, line) in rows.withIndex()) {
            // Edges are rounded per cell, so the right edge of one is the left edge of the next.
            val top = paddingTop + (r * cellH).roundToInt().toFloat()
            val bottom = paddingTop + ((r + 1) * cellH).roundToInt().toFloat()
            for ((c, ch) in line.withIndex()) {
                if (ch == ' ') continue
                val left = paddingLeft + (c * cellW).roundToInt().toFloat()
                val right = paddingLeft + ((c + 1) * cellW).roundToInt().toFloat()
                if (ch == '█') { canvas.drawRect(left, top, right, bottom, paint); continue }
                // The two lines of a double-line character, across and down.
                val x1 = (left + (right - left) * 0.3f).roundToInt().toFloat()
                val x2 = (left + (right - left) * 0.7f).roundToInt().toFloat()
                val y1 = (top + (bottom - top) * 0.36f).roundToInt().toFloat()
                val y2 = (top + (bottom - top) * 0.64f).roundToInt().toFloat()
                fun across(y: Float, from: Float, to: Float) = canvas.drawRect(from, y, to, y + stroke, paint)
                fun down(x: Float, from: Float, to: Float) = canvas.drawRect(x, from, x + stroke, to, paint)
                when (ch) {
                    '═' -> { across(y1, left, right); across(y2, left, right) }
                    '║' -> { down(x1, top, bottom); down(x2, top, bottom) }
                    '╗' -> { across(y1, left, x2 + stroke); down(x2, y1, bottom); across(y2, left, x1 + stroke); down(x1, y2, bottom) }
                    '╔' -> { across(y1, x1, right); down(x1, y1, bottom); across(y2, x2, right); down(x2, y2, bottom) }
                    '╝' -> { across(y2, left, x2 + stroke); down(x2, top, y2 + stroke); across(y1, left, x1 + stroke); down(x1, top, y1 + stroke) }
                    '╚' -> { across(y2, x1, right); down(x1, top, y2 + stroke); across(y1, x2, right); down(x2, top, y1 + stroke) }
                }
            }
        }
    }

    private companion object {
        const val CELL_W_DP = 8f
        const val CELL_H_DP = 15f
    }
}
