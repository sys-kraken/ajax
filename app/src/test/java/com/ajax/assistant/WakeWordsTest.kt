package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordsTest {
    @Test fun wakeWordAlone() {
        assertEquals(WakeWords.Kind.WAKE, WakeWords.match("ajax"))
        assertEquals(WakeWords.Kind.WAKE, WakeWords.match(" Ajax "))
        assertEquals(WakeWords.Kind.WAKE, WakeWords.match("hey ajax"))
    }

    @Test fun flashlightWords() {
        assertEquals(WakeWords.Kind.TORCH_ON, WakeWords.match("lumos"))
        assertEquals(WakeWords.Kind.TORCH_ON, WakeWords.match("flashlight"))
        assertEquals(WakeWords.Kind.TORCH_ON, WakeWords.match("flash light"))
        assertEquals(WakeWords.Kind.TORCH_OFF, WakeWords.match("nox"))
        assertEquals(WakeWords.Kind.TORCH_OFF, WakeWords.match("flashlight off"))
        assertEquals(WakeWords.Kind.TORCH_OFF, WakeWords.match("knocks"))
        assertEquals(WakeWords.Kind.TORCH_ON, WakeWords.match("lou most"))
    }

    @Test fun wakeNeedsMoreConfidenceThanTheFlashlight() {
        assertTrue(WakeWords.minConfidence(WakeWords.Kind.WAKE, false) > WakeWords.minConfidence(WakeWords.Kind.TORCH_ON, false))
        assertTrue(WakeWords.minConfidence(WakeWords.Kind.WAKE, true) > WakeWords.minConfidence(WakeWords.Kind.WAKE, false))
        assertTrue(WakeWords.minConfidence(WakeWords.Kind.TORCH_OFF, true) > 0.0)
    }

    @Test fun listensOnlyForOneSpellingPerSound() {
        assertFalse("knox" in WakeWords.phrases)
        assertFalse("flash light" in WakeWords.phrases)
        assertTrue("ajax" in WakeWords.phrases)
    }

    @Test fun wordsInsideASentenceDoNothing() {
        assertNull(WakeWords.match("i told ajax about it"))
        assertNull(WakeWords.match("where is the flashlight"))
        assertNull(WakeWords.match("the lumos spell"))
        assertNull(WakeWords.match(""))
    }

    @Test fun handsFreeAllowsOnlyHarmlessCommands() {
        assertTrue(WakeWords.allowedHandsFree("torch-on", false))
        assertTrue(WakeWords.allowedHandsFree("volume-up", false))
        assertTrue(WakeWords.allowedHandsFree("info-time", false))
        assertTrue(WakeWords.allowedHandsFree("call-number", true))
        assertFalse(WakeWords.allowedHandsFree("call", false))
        assertFalse(WakeWords.allowedHandsFree("text", false))
        assertFalse(WakeWords.allowedHandsFree("info-notifications", false))
        assertFalse(WakeWords.allowedHandsFree("open-app", false))
        assertFalse(WakeWords.allowedHandsFree("nav-restart", false))
        assertFalse(WakeWords.allowedHandsFree("toggle:Wi-Fi:off", false))
    }

    @Test fun lockedSpellsStillRoute() {
        assertEquals("torch-on", CommandRouter.route("lumos")?.id)
        assertEquals("torch-off", CommandRouter.route("nox")?.id)
    }
}
