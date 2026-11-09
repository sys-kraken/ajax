package com.ajax.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ajax's orb: glowing colours that drift around each other. It swells with your voice while
 * listening, swirls faster while thinking and breathes gently while Ajax talks. The animation runs
 * only while the orb is on screen.
 */
class OrbView(ctx: Context) : View(ctx) {
    enum class State { HIDDEN, IDLE, LISTENING, THINKING, SPEAKING }

    var state: State = State.HIDDEN
        set(v) {
            field = v
            visibility = if (v == State.HIDDEN) GONE else VISIBLE
            contentDescription = when (v) {
                State.LISTENING -> "Listening"
                State.THINKING -> "Thinking"
                State.SPEAKING -> "Ajax is speaking"
                State.IDLE -> "Ajax"
                State.HIDDEN -> null
            }
            if (v == State.HIDDEN) level = 0f
            syncAnimation()
        }

    /** The colours of the orb: Ajax's green, a teal and the app's blue. */
    private val colors = intArrayOf(0xFFA5C736.toInt(), 0xFF22D3B4.toInt(), 0xFF3E91FF.toInt())

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f        // 0..1, loops
    private var level = 0f        // smoothed voice level 0..1
    private var target = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            level += (target - level) * 0.25f
            target *= 0.92f // fall back when the voice stops
            invalidate()
        }
    }

    init {
        // Hardware layers don't do additive blending reliably; this keeps the colours luminous.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        visibility = GONE
    }

    /** Voice level from speech recognition (roughly -2 dB in silence to 10 dB loud). */
    fun setLevel(rmsdB: Float) {
        target = max(target, ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncAnimation()
    }

    private fun syncAnimation() {
        val run = state != State.HIDDEN && isAttachedToWindow && isShown
        if (run && !animator.isStarted) animator.start()
        if (!run && animator.isStarted) animator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        if (state == State.HIDDEN) return
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val t = phase * 2 * PI.toFloat()
        val speed = when (state) {
            State.THINKING -> 3f
            State.LISTENING -> 1.4f
            State.IDLE -> 0.6f
            else -> 1f
        }
        val swell = when (state) {
            State.LISTENING -> 1f + level * 0.32f
            State.THINKING -> 1f + 0.06f * sin(t * 6)
            State.SPEAKING -> 1f + 0.05f * sin(t * 3)
            State.IDLE -> 1f + 0.03f * sin(t * 2)
            State.HIDDEN -> 1f
        }
        val r = maxR * 0.55f * swell

        // Soft halo.
        glow.shader = RadialGradient(cx, cy, maxR * min(1f, 0.75f * swell + 0.15f),
            intArrayOf(withAlpha(colors[2], 70), withAlpha(colors[0], 25), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, maxR, glow)

        // Three colours circling each other, blended so their overlap glows.
        for (i in colors.indices) {
            val a = t * speed + i * (2 * PI.toFloat() / 3)
            val wobble = r * (0.38f + 0.12f * sin(t * 2 + i))
            val x = cx + cos(a) * wobble
            val y = cy + sin(a * 1.3f) * wobble
            val br = r * (0.78f + 0.10f * sin(t * 3 + i * 2))
            blob.shader = RadialGradient(x, y, br,
                intArrayOf(withAlpha(colors[i], 190), withAlpha(colors[i], 90), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, br, blob)
        }

        // Bright centre.
        core.shader = RadialGradient(cx, cy, r * 0.35f,
            intArrayOf(withAlpha(Color.WHITE, (50 + 110 * level).toInt().coerceAtMost(170)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.35f, core)
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
}
