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
    private lateinit var orb: OrbView
    private lateinit var statusLabel: TextView
    private lateinit var send: TextView
    private lateinit var mic: android.widget.ImageView
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
        chat = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }
        val maxChat = ctx.dp(320)
        chatScroll = object : ScrollView(ctx) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxChat, MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            // The card's own colour: left alone, the dark theme paints scroll views a shade darker.
            setBackgroundColor(Ui.SURFACE)
            overScrollMode = View.OVER_SCROLL_NEVER
            // Never draw Android's focus highlight (a dark film) over the conversation.
            isFocusable = false
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            addView(chat)
        }
        orb = OrbView(ctx)
        statusLabel = Ui.text(ctx, 13f, Ui.MUTED)
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
            // Send while there is text; otherwise the mic, to talk again after typing.
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) = showSendOrMic()
            })
        }
        send = Ui.text(ctx, 15f, Ui.ACCENT, bold = true).apply {
            text = "Send"
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(16), ctx.dp(10))
            setOnClickListener { sendTyped() }
        }
        mic = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_mic)
            contentDescription = "Talk"
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            setOnClickListener { talkAgain() }
        }
        val box = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(ctx, Ui.SURFACE_RAISED, 24)
            minimumHeight = ctx.dp(48)
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
            addView(mic)
        }
        // Header: the orb, then Ajax's name and what it is doing.
        val header = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(orb, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(8), 0, 0, 0)
                addView(Ui.text(ctx, 15f, bold = true).apply { text = "Ajax" })
                addView(statusLabel)
            })
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on the card must not fall through and close the overlay
            // Keeps the text box from taking focus (and the keyboard) until it is touched.
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(14))
            background = Ui.rounded(ctx, Ui.SURFACE, 28, stroke = Ui.HAIRLINE)
            elevation = ctx.dp(12).toFloat()
            // The header (orb, name, status), then the conversation, then the text box.
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ctx.dp(10)
            })
            addView(chatScroll)
            addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(8)
            })
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
        MicGuard.hold(this)
        MicGuard.quietMedia(ctx, true)
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
        mode(OrbView.State.LISTENING)
        speech.start()
        IntentModel.warm(ctx)
    }

    override fun onHide() {
        active = false
        mode(OrbView.State.HIDDEN) // stops its animation
        MicGuard.release(this)
        MicGuard.quietMedia(ctx, false)
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.stop()
        super.onHide()
    }

    override fun onDestroy() {
        active = false
        MicGuard.release(this)
        MicGuard.quietMedia(ctx, false)
        showCount++
        main.removeCallbacksAndMessages(null)
        speech.destroy()
        speaker.shutdown()
        super.onDestroy()
    }

    /** One message in the chat: the user's on the right, Ajax's on the left. */
    private fun bubble(text: String, mine: Boolean): TextView {
        val view = Ui.text(ctx, 15f).apply {
            this.text = text
            setLineSpacing(0f, 1.15f)
            // Rounded, with a tighter corner on the speaker's side: yours bottom right, Ajax's bottom left.
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (mine) MY_BUBBLE else Ui.SURFACE_RAISED)
                val r = ctx.dp(18).toFloat()
                val tight = ctx.dp(6).toFloat()
                cornerRadii = if (mine) floatArrayOf(r, r, r, r, tight, tight, r, r)
                else floatArrayOf(r, r, r, r, r, r, tight, tight)
            }
            setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
        }
        chat.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            bottomMargin = ctx.dp(8)
            if (mine) marginStart = ctx.dp(48) else marginEnd = ctx.dp(48)
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

    /** Shows what Ajax is doing: the orb and the line under its name. */
    private fun mode(state: OrbView.State) {
        orb.state = state
        statusLabel.text = when (state) {
            OrbView.State.LISTENING -> "Listening"
            OrbView.State.THINKING -> "Thinking"
            OrbView.State.SPEAKING -> "Speaking"
            OrbView.State.IDLE -> "Type a command, or tap the mic"
            OrbView.State.HIDDEN -> ""
        }
        showSendOrMic()
    }

    private fun showSendOrMic() {
        val hasText = input.text.isNotBlank()
        send.visibility = if (hasText) View.VISIBLE else View.GONE
        // The mic shows when Ajax isn't already listening.
        mic.visibility = if (!hasText && orb.state != OrbView.State.LISTENING) View.VISIBLE else View.GONE
    }

    /** The mic button: put the keyboard away and listen again. */
    private fun talkAgain() {
        if (!active) return
        typing = false
        input.clearFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        speaker.stop()
        liveBubble = null
        followUpUntil = SystemClock.uptimeMillis() + FOLLOW_UP_MS
        mode(OrbView.State.LISTENING)
        speech.start()
    }

    private fun startTyping() {
        typing = true
        main.removeCallbacksAndMessages(null)
        speech.cancel()
        speaker.stop()
        liveBubble = null
        mode(OrbView.State.IDLE) // stays while you type, just calmer
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !active) return
        input.text.clear()
        liveBubble = null
        onResults(listOf(text))
    }

    override fun onListening(mode: String) {
        if (active) mode(OrbView.State.LISTENING)
    }

    override fun onLevel(rmsdB: Float) {
        if (active) orb.setLevel(rmsdB)
    }

    override fun onPartial(text: String) {
        if (active) heard(text)
    }

    override fun onResults(candidates: List<String>) {
        if (!active) return
        mode(OrbView.State.THINKING)
        heard(candidates.first())
        brain.handle(candidates, onThinking = { if (active) mode(OrbView.State.THINKING) }) { reply ->
            if (active) act(reply)
        }
    }

    private fun act(reply: Brain.Reply) {
        heard(brain.heard)
        liveBubble = null
        mode(if (reply.afterHide != null) OrbView.State.HIDDEN else OrbView.State.SPEAKING)
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
            } else if (active) {
                mode(OrbView.State.IDLE) // finished talking; you're typing, so no listening
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
        mode(OrbView.State.SPEAKING)
        bubble(r.message, mine = false)
        speaker.speak(r.message) {
            if (active) {
                mode(OrbView.State.IDLE)
                main.postDelayed({ close() }, 900)
            }
        }
    }

    /** Tears the session down fully so the next side-key press starts clean. */
    private fun close() {
        active = false
        finish()
    }

    private companion object {
        const val FOLLOW_UP_MS = 12_000L
        /** Your messages: a dark blue, so they stand apart from Ajax's grey. */
        const val MY_BUBBLE = 0xFF1C3554.toInt()
    }
}
