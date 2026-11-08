package com.ajax.assistant

/**
 * What the always-on listener does with one utterance (the words between two pauses). The whole
 * utterance must be the keyword, so "Ajax" inside a sentence, or "flashlight" in a conversation,
 * does nothing. The variants are what the offline recogniser tends to write for each word.
 */
object WakeWords {
    enum class Kind { WAKE, TORCH_ON, TORCH_OFF }

    /**
     * How sure the recogniser must be before "Ajax" opens anything. The flashlight words need less:
     * a wrong flashlight is harmless, and their sound-alike spellings share the score between them.
     * Audio from the phone's own speaker (a video saying "Ajax") must be clearer still.
     */
    fun minConfidence(kind: Kind, mediaPlaying: Boolean): Double = when (kind) {
        Kind.WAKE -> if (mediaPlaying) 0.95 else 0.7
        else -> if (mediaPlaying) 0.6 else 0.0
    }

    // The recogniser only knows these phrases. Words it has never heard of (maybe "lumos") are dropped
    // from its list, so each keyword is also spelled as ordinary words that sound the same.
    // One spelling per sound: spellings that sound the same ("nox", "knocks") split the recogniser's
    // confidence between them without catching anything new.
    private val wake = setOf("ajax", "hey ajax", "okay ajax")
    private val torchOn = setOf(
        "lumos", "lou moss", "lou most", "loom us", "loom is", "lumo",
        "flashlight", "flashlight on", "torch on",
    )
    private val torchOff = setOf("nox", "flashlight off", "torch off")

    // What the recogniser may also write for the words above; accepted, but not listened for.
    private val alsoOn = setOf("loo moss", "lu moss", "lieu moss", "lumus", "lumas", "loomis", "flash light", "flash light on")
    private val alsoOff = setOf("knox", "knocks", "nocks", "flash light off")

    /** Everything the recogniser listens for. */
    val phrases: List<String> get() = (wake + torchOn + torchOff).toList()

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun match(text: String): Kind? = when (normalize(text)) {
        in wake -> Kind.WAKE
        in torchOn, in alsoOn -> Kind.TORCH_ON
        in torchOff, in alsoOff -> Kind.TORCH_OFF
        else -> null
    }

    /** Commands that are safe, and work, with the phone locked or the screen off. */
    private val lockedPrefixes = listOf("torch-", "volume-", "media-", "ringer-", "dnd-", "rotate-")
    private val lockedIds = setOf(
        "mute", "unmute", "brightness", "info-battery", "info-time", "info-date", "info-storage",
        "info-memory", "info-temperature", "info-uptime", "info-device",
    )

    fun allowedHandsFree(id: String, hintOnly: Boolean): Boolean =
        hintOnly || id in lockedIds || lockedPrefixes.any { id.startsWith(it) }
}
