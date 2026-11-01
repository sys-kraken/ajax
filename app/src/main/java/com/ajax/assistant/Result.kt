package com.ajax.assistant

/** Outcome of running a command. [message] is shown on screen and spoken. */
data class Result(val ok: Boolean, val message: String)
