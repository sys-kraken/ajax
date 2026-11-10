package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordsTest {
    private val WAKE = WakeWords.Kind.WAKE
    private val ON = WakeWords.Kind.TORCH_ON
    private val OFF = WakeWords.Kind.TORCH_OFF

    /** Detector scores in the order Ajax, lumos, flashlight; below zero means no detector for the word. */
    private fun scores(ajax: Float = 0f, lumos: Float = 0f, flashlight: Float = 0f) = floatArrayOf(ajax, lumos, flashlight)

    @Test fun wakeWordAlone() {
        assertEquals(WAKE, WakeWords.match("ajax"))
        assertEquals(WAKE, WakeWords.match(" Ajax "))
        assertEquals(WAKE, WakeWords.match("hey ajax"))
    }

    @Test fun flashlightWords() {
        assertEquals(ON, WakeWords.match("lumos"))
        assertEquals(ON, WakeWords.match("flashlight"))
        assertEquals(ON, WakeWords.match("flash light"))
        assertEquals(OFF, WakeWords.match("nox"))
        assertEquals(OFF, WakeWords.match("flashlight off"))
        assertEquals(OFF, WakeWords.match("knocks"))
        assertEquals(ON, WakeWords.match("lou most"))
        assertEquals(ON, WakeWords.match("lou moss"))
    }

    @Test fun looseSoundAlikesAreGone() {
        for (loose in listOf("loom is", "lumo", "loomis", "lumus", "lumas", "torch", "torch on", "torch off")) {
            assertNull(loose, WakeWords.match(loose))
            assertFalse(loose, loose in WakeWords.phrases)
        }
    }

    @Test fun everyKeywordNeedsRealConfidence() {
        for (kind in WakeWords.Kind.values()) {
            assertTrue(WakeWords.minConfidence(kind, false) >= 0.6)
            assertTrue(WakeWords.minConfidence(kind, true) > WakeWords.minConfidence(kind, false))
        }
        assertNull(WakeWords.decide("lou moss", 0.45, false, null))
        assertEquals(ON, WakeWords.decide("lou moss", 0.9, false, null))
        assertNull(WakeWords.decide("nox", 0.4, false, null))
        assertEquals(OFF, WakeWords.decide("nox", 1.0, false, null))
        assertNull(WakeWords.decide("flashlight", 0.7, true, null))
        assertNull(WakeWords.decide("ajax", 0.6, false, null))
    }

    @Test fun listensOnlyForOneSpellingPerSound() {
        assertFalse("knox" in WakeWords.phrases)
        assertFalse("flash light" in WakeWords.phrases)
        assertTrue("ajax" in WakeWords.phrases)
    }

    @Test fun bothDetectorsMustAgreeOnAjax() {
        assertEquals(WAKE, WakeWords.decide("ajax", 1.0, false, scores(ajax = 0.99f)))
        assertEquals(WAKE, WakeWords.decide("hey ajax", 1.0, false, scores(ajax = 0.6f)))
        // The recogniser is sure, the trained detector is not: music, or another word.
        assertNull(WakeWords.decide("ajax", 1.0, false, scores(ajax = 0.02f)))
        assertNull(WakeWords.decide("ajax", 1.0, true, scores(ajax = 0.02f)))
        // The trained detector is sure, the recogniser heard something else.
        assertNull(WakeWords.decide("[unk]", 1.0, false, scores(ajax = 1f)))
    }

    @Test fun bothDetectorsMustAgreeOnTheFlashlight() {
        assertEquals(ON, WakeWords.decide("lou moss", 0.9, false, scores(lumos = 0.9f)))
        assertNull(WakeWords.decide("lou moss", 0.9, false, scores(lumos = 0.1f)))
        assertNull(WakeWords.decide("loom us", 1.0, false, scores(lumos = 0.1f, flashlight = 1f)))
        assertEquals(ON, WakeWords.decide("flashlight", 0.9, false, scores(flashlight = 0.9f)))
        assertNull(WakeWords.decide("flashlight", 0.9, false, scores(lumos = 1f, flashlight = 0.1f)))
        // Switching the light off needs no second opinion.
        assertEquals(OFF, WakeWords.decide("nox", 0.9, false, scores()))
        assertEquals(OFF, WakeWords.decide("flashlight off", 0.9, false, scores()))
    }

    @Test fun aSureDetectorNeedsLessFromTheRecogniser() {
        // Quiet or far "Ajax" often arrives as "hey ajax" with a middling score.
        assertEquals(WAKE, WakeWords.decide("hey ajax", 0.55, false, scores(ajax = 1f)))
        assertNull(WakeWords.decide("hey ajax", 0.55, false, scores(ajax = 0.7f)))
        assertNull(WakeWords.decide("hey ajax", 0.3, false, scores(ajax = 1f)))
        assertEquals(WAKE, WakeWords.decide("[unk] ajax", 0.51, true, scores(ajax = 0.96f)))
        assertEquals(ON, WakeWords.decide("lou moss", 0.52, false, scores(lumos = 0.95f)))
        // Switching off has no detector, so it keeps the full minimum.
        assertNull(WakeWords.decide("nox", 0.55, false, scores(lumos = 1f, flashlight = 1f)))
        // "nox" written for what the Ajax detector is sure was "Ajax" does nothing.
        assertNull(WakeWords.decide("nox", 1.0, false, scores(ajax = 1f)))
        assertEquals(OFF, WakeWords.decide("nox", 1.0, false, scores(ajax = 0.3f)))
    }

    @Test fun aKeywordWaitsForItsDetector() {
        assertTrue(WakeWords.waitsForDetector("ajax", scores(ajax = 0f)))
        assertTrue(WakeWords.waitsForDetector("[unk] ajax", scores(ajax = 0.6f)))
        assertFalse(WakeWords.waitsForDetector("ajax", scores(ajax = 0.95f)))
        assertFalse(WakeWords.waitsForDetector("ajax", null))
        assertFalse(WakeWords.waitsForDetector("lou moss", floatArrayOf(0f, -1f, -1f)))
        assertFalse(WakeWords.waitsForDetector("nox", scores()))
        assertFalse(WakeWords.waitsForDetector("[unk]", scores()))
    }

    @Test fun aWordWithoutADetectorRestsOnTheRecogniser() {
        val onlyAjax = floatArrayOf(0f, -1f, -1f)
        assertEquals(ON, WakeWords.decide("lou moss", 0.9, false, onlyAjax))
        assertEquals(ON, WakeWords.decide("flashlight", 0.9, false, onlyAjax))
        assertNull(WakeWords.decide("ajax", 1.0, false, onlyAjax))
    }

    @Test fun ajaxInsideOtherSoundOnlyWhileAudioPlaysAndOnlyWhenTheDetectorIsSure() {
        assertTrue(WakeWords.containsWake("[unk] ajax [unk]"))
        assertFalse(WakeWords.containsWake("ajaxes"))
        assertEquals(WAKE, WakeWords.decide("[unk] ajax", 0.9, true, scores(ajax = 0.97f)))
        assertEquals(WAKE, WakeWords.decide("[unk] ajax [unk]", 0.8, true, scores(ajax = 0.95f)))
        assertNull(WakeWords.decide("[unk] ajax", 0.9, true, scores(ajax = 0.6f)))
        assertNull(WakeWords.decide("[unk] ajax", 0.9, true, null))
        assertNull(WakeWords.decide("[unk] ajax", 0.9, true, floatArrayOf(-1f, -1f, -1f)))
        assertNull(WakeWords.decide("[unk] ajax", 0.9, false, scores(ajax = 1f)))
        assertNull(WakeWords.decide("[unk] ajax", 0.4, true, scores(ajax = 1f)))
        // Other keywords around it are the recogniser guessing at noise.
        assertNull(WakeWords.decide("moss ajax", 0.92, true, scores(ajax = 1f)))
        assertNull(WakeWords.decide("ajax us", 0.9, true, scores(ajax = 1f)))
        // The flashlight words never count inside other sound.
        assertNull(WakeWords.decide("[unk] lou moss", 1.0, true, scores(lumos = 1f)))
        assertNull(WakeWords.decide("[unk] flashlight", 1.0, true, scores(flashlight = 1f)))
        assertNull(WakeWords.decide("[unk] nox", 1.0, true, scores()))
    }

    @Test fun settingsIgnoreKeywordsWhileAudioPlays() {
        val sure = scores(1f, 1f, 1f)
        assertNull(WakeWords.decide("lou moss", 1.0, true, sure, quietTorch = true))
        assertNull(WakeWords.decide("flashlight", 1.0, true, sure, quietTorch = true))
        assertNull(WakeWords.decide("nox", 1.0, true, sure, quietTorch = true))
        assertEquals(WAKE, WakeWords.decide("ajax", 1.0, true, sure, quietTorch = true))
        assertNull(WakeWords.decide("ajax", 1.0, true, sure, quietWake = true))
        assertNull(WakeWords.decide("[unk] ajax", 1.0, true, sure, quietWake = true))
        assertEquals(ON, WakeWords.decide("lou moss", 1.0, true, sure, quietWake = true))
        // In a quiet room the settings change nothing.
        assertEquals(ON, WakeWords.decide("lou moss", 1.0, false, sure, quietTorch = true, quietWake = true))
        assertEquals(WAKE, WakeWords.decide("ajax", 1.0, false, sure, quietTorch = true, quietWake = true))
    }

    @Test fun ignoredWordsNeedNoDetectorWhileAudioPlays() {
        assertTrue(WakeWords.wanted(false, true, true).all { it })
        assertTrue(WakeWords.wanted(true, false, false).all { it })
        val torchIgnored = WakeWords.wanted(true, true, false)
        assertTrue(torchIgnored[WakeWords.Word.AJAX.ordinal])
        assertFalse(torchIgnored[WakeWords.Word.LUMOS.ordinal])
        assertFalse(torchIgnored[WakeWords.Word.FLASHLIGHT.ordinal])
        val wakeIgnored = WakeWords.wanted(true, false, true)
        assertFalse(wakeIgnored[WakeWords.Word.AJAX.ordinal])
        assertTrue(wakeIgnored[WakeWords.Word.LUMOS.ordinal])
        assertTrue(WakeWords.nothingToHear(true, true, true))
        assertFalse(WakeWords.nothingToHear(false, true, true))
        assertFalse(WakeWords.nothingToHear(true, true, false))
    }

    @Test fun detectorAgreementCanBeStricterOrLooser() {
        val strict = WakeWords.agreement(0)
        val relaxed = WakeWords.agreement(2)
        assertEquals(WakeWords.AGREE, WakeWords.agreement(WakeWords.DEFAULT_AGREEMENT))
        assertEquals(WakeWords.AGREE, WakeWords.agreement(99))
        assertNull(WakeWords.decide("ajax", 1.0, false, scores(ajax = 0.6f), agree = strict))
        assertEquals(WAKE, WakeWords.decide("ajax", 1.0, false, scores(ajax = 0.85f), agree = strict))
        assertEquals(WAKE, WakeWords.decide("ajax", 1.0, false, scores(ajax = 0.35f), agree = relaxed))
        assertNull(WakeWords.decide("ajax", 1.0, false, scores(ajax = 0.1f), agree = relaxed))
        // Inside other sound the detector must stay very sure, whatever the setting.
        assertNull(WakeWords.decide("[unk] ajax", 0.9, true, scores(ajax = 0.6f), agree = relaxed))
    }

    @Test fun lumosIsShownByName() {
        assertTrue(WakeWords.shown("lou moss").startsWith("lumos"))
        assertTrue(WakeWords.shown("loom us").startsWith("lumos"))
        assertEquals("nox", WakeWords.shown("nox"))
        assertEquals("ajax", WakeWords.shown("ajax"))
        assertEquals("[unk] lou", WakeWords.shown("[unk] lou"))
    }

    @Test fun wordsInsideASentenceDoNothing() {
        assertNull(WakeWords.match("i told ajax about it"))
        assertNull(WakeWords.match("where is the flashlight"))
        assertNull(WakeWords.match("the lumos spell"))
        assertNull(WakeWords.match(""))
        assertNull(WakeWords.decide("", 1.0, false, null))
        assertNull(WakeWords.decide("[unk]", 1.0, true, scores(1f, 1f, 1f)))
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
