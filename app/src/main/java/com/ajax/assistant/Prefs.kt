package com.ajax.assistant

object Prefs {
    const val NAME = "ajax"
    const val SPEAK = "speak"
    const val ONLINE = "online"
    const val WAKE = "wake"
    const val CALLS = "calls"
    const val VOICE_LOCK = "voice_lock"
    /** While the phone plays audio: ignore the flashlight words / ignore "Ajax". */
    const val QUIET_TORCH = "quiet_torch"
    const val QUIET_WAKE = "quiet_wake"
    /** How readily a trained detector agrees with the recogniser: an index into [WakeWords.AGREEMENT]. */
    const val AGREEMENT = "agreement"
}
