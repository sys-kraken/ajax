package com.ajax.assistant

import android.content.Context

/** What a matched command wants to happen next. */
sealed class Outcome {
    /** Finished; show/speak the result. */
    class Done(val result: Result) : Outcome()

    /** Risky action (sends, deletes, spends): ask yes/no first. */
    class Ask(val prompt: String, val onYes: (Context) -> Result) : Outcome()

    /** Must run after the assistant overlay is gone (Accessibility acts on the window underneath). */
    class AfterHide(val label: String, val run: (Context) -> Result) : Outcome()
}
