package com.ajax.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible launch entry for shortcuts that can only open an app (RegiStar's back tap, the side
 * button's double press): starts the listening overlay at once.
 */
class ListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not the default assistant, so no overlay: the setup screen listens instead.
        if (!AjaxInteractionService.listen()) {
            startActivity(Intent(Intent.ACTION_ASSIST).setClass(this, MainActivity::class.java))
        }
        finish()
    }
}
