package com.ajax.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService

/**
 * Picks the real speech engine. Once Ajax is the default assistant, Android makes Ajax's own
 * [AjaxRecognitionService] the system recogniser, so we must always target another one explicitly.
 */
object Recognizers {
    private val preferred = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.tts",
        "com.samsung.android.bixby.agent",
    )

    fun delegate(ctx: Context): ComponentName? {
        val found = ctx.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val i = choose(found.map { it.packageName }, ctx.packageName)
        return if (i < 0) null else found[i]
    }

    /** Index of the best engine in [packages], never [own]; -1 if none. */
    fun choose(packages: List<String>, own: String): Int {
        preferred.forEach { p -> packages.indexOf(p).let { if (it >= 0) return it } }
        return packages.indexOfFirst { it != own }
    }
}
