package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutAjaxTest {
    private fun check(id: String, vararg phrases: String) {
        for (p in phrases) assertEquals(p, id, CommandRouter.route(p)?.id)
    }

    @Test fun name() = check("about-name", "who are you?", "Who are you", "what's your name", "what are you", "introduce yourself", "Ajax, who are you")
    @Test fun help() = check("about-help", "what can you do", "help", "what can I say")
    @Test fun maker() = check("about-maker", "who made you", "who created you", "who is your creator")
    @Test fun mood() = check("about-mood", "how are you", "how are you doing")
    @Test fun hello() = check("about-hello", "hello", "hi there", "good morning")
    @Test fun here() = check("about-here", "are you there", "can you hear me")

    @Test fun uninstallPhrases() {
        check("uninstall-app", "uninstall clash of clans", "Uninstall Clash of Clans", "delete the instagram app", "remove telegram from my phone")
        // Not an app name: left to other commands.
        assertEquals(null, CommandRouter.route("delete it")?.id?.takeIf { it == "uninstall-app" })
        assertEquals(false, WakeWords.allowedHandsFree("uninstall-app", false))
    }

    @Test fun otherQuestionsStillWork() {
        check("info-time", "what time is it")
        check("info-battery", "battery percent")
        check("call", "call mom")
    }
}
