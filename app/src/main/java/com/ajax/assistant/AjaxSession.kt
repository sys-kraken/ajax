package com.ajax.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ajax.assistant.Ui.dp

/**
 * The chat overlay shown when the side key is held: listens and acts, then keeps listening for
 * [FOLLOW_UP_MS] so commands can follow each other. Commands can also be typed; touching the box
 * turns the microphone off. Closes on silence, on "stop"/"thanks", on a tap outside the card, or
 * after a command that hands the user over to something else.
 */
class AjaxSession(private val ctx: Context) : VoiceInteractionSession(ctx), SpeechInput.Callback {

    private val main = Handler(Looper.getMainLooper())
    private val brain = Brain(ctx)
    private val speech = SpeechInput(ctx, this)
    private val speaker = Speaker(ctx)

    init { Actions.watchTorch(ctx) }
    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText

    /** The user's bubble for the current request, updated as speech comes in. */
    private var liveBubble: TextView? = null

    /** False once hidden: late speech/TTS callbacks from a previous press are ignored. */
    private var active = false

    /** Bumped on every show and on destroy, so a command running in the background can't reopen a stale overlay. */
    private var showCount = 0
    private var resuming = false

    /** Uptime until which silence means "listen again"; 0 before the first command of a conversation. */
    private var followUpUntil = 0L

    /** The user touched the text box: the microphone stays off and silence doesn't close the overlay. */
    private var typing = false

    override fun onCreateContentView(): View {
        chat = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val maxChat = ctx.dp(320)
        chatScroll = object : ScrollView(ctx) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxChat, MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(chat)
        }
        status = Ui.text(ctx, 13f, Ui.MUTED).apply { setPadding(ctx.dp(2), ctx.dp(8), 0, ctx.dp(10)) }
        input = EditText(ctx).apply {
            hint = "Type a command"
            textSize = 15f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(8), ctx.dp(10))
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) startTyping()
                false
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }
        val send = Ui.text(ctx, 14f, bold = true).apply {
            text = "Send"
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            setOnClickListener { sendTyped() }
        }
        val box = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ctx, Ui.SURFACE_RAISED, 22)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on the card must not fall through and close the overlay
            // Keeps the text box from taking focus (and the keyboard) until it is touched.
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            background = Ui.rounded(ctx, Ui.SURFACE, 26)
            elevation = ctx.dp(12).toFloat()
            addView(chatScroll)
            addView(status)
            addView(box)
        }
        val pad = ctx.dp(12)
        return LinearLayout(ctx).apply {
            gravity = Gravity.BOTTOM
            setPadding(pad, pad, pad, ctx.dp(48))
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { close() }
            // Stay above the keyboard while typing.
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { v, i ->
                    val bottom = i.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()).bottom
                    v.setPadding(pad, pad, pad, maxOf(bottom + pad, ctx.dp(48)))
                    i
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        main.removeCallbacksAndMessages(null)
        speaker.stop()
        brain.reset()
        active = true
        showCount++
        if (!resuming) {
            followUpUntil = 0L
            chat.removeAllViews()
        }
        resuming = false
        typing = false
        liveBubble = null
        input.text.clear()
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishWith(Result(false, "Open Ajax and allow the microphone first."))
            return
        }
        status.text = "Listening…"
        speech.start()
    }

    override fun onHide() {
        active = false
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.stop()
        super.onHide()
    }

    override fun onDestroy() {
        active = false
        showCount++
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    /** One message in the chat: the user's on the right, Ajax's on the left. */
    private fun bubble(text: String, mine: Boolean): TextView {
        val view = Ui.text(ctx, if (mine) 15f else 16f).apply {
            this.text = text
            background = Ui.rounded(ctx, if (mine) Ui.SURFACE_RAISED else Ui.BACKGROUND, 18)
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        }
        chat.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            bottomMargin = ctx.dp(6)
            if (mine) marginStart = ctx.dp(40) else marginEnd = ctx.dp(40)
        })
        toBottom()
        return view
    }

    private fun toBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun heard(text: String) {
        val b = liveBubble
        if (b != null) {
            b.text = text
            toBottom()
        } else {
            liveBubble = bubble(text, mine = true)
        }
    }

    private fun startTyping() {
        typing = true
        main.removeCallbacksAndMessages(null)
        speech.cancel()
        speaker.stop()
        liveBubble = null
        status.text = ""
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !active) return
        input.text.clear()
        liveBubble = null
        onResults(listOf(text))
    }

    override fun onListening(mode: String) {
        if (active) status.text = "Listening…"
    }

    override fun onPartial(text: String) {
        if (active) heard(text)
    }

    override fun onResults(candidates: List<String>) {
        if (!active) return
        status.text = ""
        heard(candidates.first())
        brain.handle(candidates, onThinking = { if (active) status.text = "Thinking…" }) { reply ->
            if (active) act(reply)
        }
    }

    private fun act(reply: Brain.Reply) {
        heard(brain.heard)
        liveBubble = null
        status.text = ""
        val after = reply.afterHide
        when {
            after != null -> {
                // Accessibility acts on the window underneath, so get the overlay out of the way first.
                // Own handler: closing or hiding clears `main`.
                val shown = showCount
                if (reply.ends) close() else { active = false; hide() }
                Handler(Looper.getMainLooper()).postDelayed({
                    Access.run(ctx, after) {
                        if (!reply.ends && shown == showCount) reopen()
                    }
                }, 450)
            }
            reply.listenAgain -> listenAfter(reply.result)
            reply.ends -> finishWith(reply.result)
            // A failed command only keeps an open conversation going; it can't extend it.
            !typing && !reply.result.ok && SystemClock.uptimeMillis() >= followUpUntil -> finishWith(reply.result)
            else -> listenAfter(reply.result, extend = reply.result.ok)
        }
    }

    /** Back from a command that needed the overlay out of the way: show it again, listening. */
    private fun reopen() {
        resuming = true
        followUpUntil = SystemClock.uptimeMillis() + FOLLOW_UP_MS
        show(null, 0)
    }

    private fun listenAfter(r: Result, extend: Boolean = false) {
        bubble(r.message, mine = false)
        speaker.speak(r.message) {
            if (active && !typing) {
                if (extend) followUpUntil = SystemClock.uptimeMillis() + FOLLOW_UP_MS
                speech.start()
            }
        }
    }

    override fun onError(message: String) {
        if (active && !typing) finishWith(Result(false, message))
    }

    override fun onNothingHeard() {
        if (!active || typing) return
        when {
            followUpUntil == 0L -> finishWith(Result(false, "I didn't hear anything."))
            SystemClock.uptimeMillis() < followUpUntil -> main.postDelayed({ if (active && !typing) speech.start() }, 200)
            else -> close()
        }
    }

    private fun finishWith(r: Result) {
        status.text = ""
        bubble(r.message, mine = false)
        speaker.speak(r.message) { if (active) main.postDelayed({ close() }, 900) }
    }

    /** Tears the session down fully so the next side-key press starts clean. */
    private fun close() {
        active = false
        finish()
    }

    private companion object {
        const val FOLLOW_UP_MS = 12_000L
    }
}
