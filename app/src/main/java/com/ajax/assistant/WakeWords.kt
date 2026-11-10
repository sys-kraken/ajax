package com.ajax.assistant

/**
 * What the always-on listener does with one utterance (the words between two pauses). The whole
 * utterance must be the keyword, so "Ajax" inside a sentence, or "flashlight" in a conversation,
 * does nothing. The variants are what the offline recogniser tends to write for each word.
 *
 * Two detectors must agree before anything happens: the keyword recogniser (which writes the words
 * and how sure it is) and, for each word that has one, the detector trained on that word's sound.
 * Either one alone is fooled by music and noise now and then; they are rarely fooled together.
 */
object WakeWords {
    enum class Kind { WAKE, TORCH_ON, TORCH_OFF }

    /** The words a trained detector can exist for; [file] names its network (assets/wake/<file>.json). */
    enum class Word(val file: String, val say: String) {
        AJAX("ajax", "Ajax"), LUMOS("lumos", "lumos"), FLASHLIGHT("flashlight", "flashlight")
    }

    /** "Ajax" said on its own: the whole utterance, with the pause after it, fits in 1.7 s (50 ms chunks). */
    const val MAX_WAKE_CHUNKS = 34

    /**
     * How sure the keyword recogniser must be. It writes its keywords for plain noise too, usually
     * with a low score, so every keyword needs a real minimum. Audio from the phone's own speaker
     * (a song, a video saying "flashlight") must be clearer still.
     */
    fun minConfidence(kind: Kind, mediaPlaying: Boolean): Double = when (kind) {
        Kind.WAKE -> if (mediaPlaying) MIN_CONFIDENCE_IN_AUDIO else 0.7
        else -> if (mediaPlaying) 0.8 else 0.6
    }

    /** How sure the keyword recogniser must be of "Ajax" while the phone plays audio. */
    const val MIN_CONFIDENCE_IN_AUDIO = 0.75

    /**
     * What the trained detector must score (0 to 1) to agree with the keyword recogniser. Lower than
     * the detector would need on its own, because the recogniser has to say the same word as well.
     */
    const val AGREE = 0.5f

    /**
     * The choices for that score in settings, strictest first: fewer false triggers against fewer
     * misses of quiet or far speech. [AGREE] is the middle one.
     */
    val AGREEMENT = listOf("Strict" to 0.8f, "Balanced" to AGREE, "Relaxed" to 0.3f)
    const val DEFAULT_AGREEMENT = 1

    fun agreement(index: Int): Float = AGREEMENT.getOrElse(index) { AGREEMENT[DEFAULT_AGREEMENT] }.second

    /**
     * "Ajax" heard with other sound around it (only counted while the phone plays audio): the
     * recogniser's word is worth less there, so the detector must be as sure as if it decided alone.
     */
    const val AGREE_INSIDE = 0.9f

    /** What the recogniser must still reach when the trained detector is that sure. */
    const val SURE_CONFIDENCE = 0.5

    // The recogniser only knows these phrases. Words it has never heard of ("lumos") are dropped
    // from its list, so each keyword is also spelled as ordinary words that sound the same.
    // One spelling per sound: spellings that sound the same ("nox", "knocks") split the recogniser's
    // confidence between them without catching anything new. Only the closest spellings are kept:
    // loose ones ("loom is", "lumo") are built from small words the recogniser also writes for noise.
    private val wake = setOf("ajax", "hey ajax", "okay ajax")
    private val lumos = setOf("lumos", "lou moss", "lou most", "loom us")
    private val flashlight = setOf("flashlight", "flashlight on")
    private val torchOff = setOf("nox", "flashlight off")

    // What the recogniser may also write for the words above; accepted, but not listened for.
    private val alsoLumos = setOf("loo moss", "lu moss", "lieu moss")
    private val alsoFlashlight = setOf("flash light", "flash light on")
    private val alsoOff = setOf("knox", "knocks", "nocks", "flash light off")

    /** Everything the recogniser listens for. */
    val phrases: List<String> get() = (wake + lumos + flashlight + torchOff).toList()

    /** "Ajax" as a word anywhere in the phrase. */
    fun containsWake(text: String): Boolean = "ajax" in normalize(text).split(" ")

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun match(text: String): Kind? = when (normalize(text)) {
        in wake -> Kind.WAKE
        in lumos, in alsoLumos, in flashlight, in alsoFlashlight -> Kind.TORCH_ON
        in torchOff, in alsoOff -> Kind.TORCH_OFF
        else -> null
    }

    /** The word whose trained detector has to agree with this phrase; none for switching the light off. */
    fun word(text: String): Word? = when (normalize(text)) {
        in wake -> Word.AJAX
        in lumos, in alsoLumos -> Word.LUMOS
        in flashlight, in alsoFlashlight -> Word.FLASHLIGHT
        else -> null
    }

    /**
     * What one utterance does, if anything.
     *
     * [text] and [confidence] come from the keyword recogniser. [scores] are the trained detectors'
     * highest scores during the utterance, one per [Word] (null without detectors; below zero for a
     * word that has no detector yet, which then rests on the recogniser alone). [playing]: the phone
     * is playing audio. [quietTorch] / [quietWake]: the settings that ignore the flashlight words /
     * "Ajax" while it does. [agree]: the detector score that counts as agreeing (see [AGREEMENT]).
     */
    fun decide(
        text: String,
        confidence: Double,
        playing: Boolean,
        scores: FloatArray?,
        quietTorch: Boolean = false,
        quietWake: Boolean = false,
        agree: Float = AGREE,
    ): Kind? {
        val words = normalize(text).split(" ").filter { it.isNotEmpty() }
        // "[unk]" is the recogniser's mark for sound that is none of its keywords.
        val core = words.filter { it != "unk" }.joinToString(" ")
        val alone = words.none { it == "unk" }
        val kind = match(core) ?: return null
        // With audio playing, its sound runs into what you say, so "Ajax" rarely arrives on its own:
        // then it also counts with other sound around it. Nothing else does, and never in a quiet room.
        if (!alone && (kind != Kind.WAKE || !playing)) return null
        if (playing && (if (kind == Kind.WAKE) quietWake else quietTorch)) return null
        val score = word(core)?.let { scores?.getOrNull(it.ordinal) }?.takeIf { it >= 0f }
        // The surer the trained detector, the less the recogniser has to be: quiet or far speech
        // often comes out as the right word with a middling score.
        val needed = if (score != null && score >= AGREE_INSIDE) SURE_CONFIDENCE else minConfidence(kind, playing)
        if (confidence < needed) return null
        if (word(core) == null) {
            // The recogniser sometimes writes "nox" for a muffled "Ajax"; the Ajax detector knows better.
            val ajax = scores?.getOrNull(Word.AJAX.ordinal) ?: -1f
            return if (ajax >= AGREE_INSIDE) null else kind
        }
        // No detector for this word: only the recogniser's word counts, and only when said alone.
        if (score == null) return if (alone) kind else null
        return if (score >= (if (alone) agree else maxOf(agree, AGREE_INSIDE))) kind else null
    }

    /**
     * Which words' detectors are worth running right now, one flag per [Word]. While the phone plays
     * audio, a word the settings ignore needs no detector; with every keyword ignored, nothing has to
     * listen at all (see [nothingToHear]).
     */
    fun wanted(playing: Boolean, quietTorch: Boolean, quietWake: Boolean): BooleanArray =
        BooleanArray(Word.values().size) { i ->
            !playing || !(if (Word.values()[i] == Word.AJAX) quietWake else quietTorch)
        }

    /** Every keyword is being ignored: the recogniser and the detectors can both rest. */
    fun nothingToHear(playing: Boolean, quietTorch: Boolean, quietWake: Boolean): Boolean =
        playing && quietTorch && quietWake

    /**
     * The trained detector answers a little after the word ends, sometimes after the recogniser has
     * already handed the utterance over. True when this utterance is a keyword whose detector has
     * not agreed yet, so the listener should give it a moment more before deciding.
     */
    fun waitsForDetector(text: String, scores: FloatArray?): Boolean {
        val core = normalize(text).split(" ").filter { it.isNotEmpty() && it != "unk" }.joinToString(" ")
        val score = word(core)?.let { scores?.getOrNull(it.ordinal) } ?: return false
        return score >= 0f && score < AGREE_INSIDE
    }

    /** Commands that are safe, and work, with the phone locked or the screen off. */
    private val lockedPrefixes = listOf("torch-", "volume-", "media-", "ringer-", "dnd-", "rotate-", "about-")
    private val lockedIds = setOf(
        "mute", "unmute", "brightness", "info-battery", "info-time", "info-date", "info-storage",
        "info-memory", "info-temperature", "info-uptime", "info-device", "call-answer", "call-end",
    )

    fun allowedHandsFree(id: String, hintOnly: Boolean): Boolean =
        hintOnly || id in lockedIds || lockedPrefixes.any { id.startsWith(it) }
}
