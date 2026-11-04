package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentModelTest {
    private val c = IntentModel.Catalog

    @Test fun everyFixedCommandRoutes() {
        for (p in c.fixed) assertNotNull("router rejects catalog entry '$p'", CommandRouter.route(p))
    }

    @Test fun everySlotShapeRoutes() {
        listOf(
            "brightness 40 percent", "set an alarm for 7 30 am", "set an alarm for 6", "timer 10 minutes",
            "open spotify", "app info for spotify", "call mom", "text mom saying on my way", "draft a message to mom saying hi",
            "volume 30 percent", "search for pizza near me", "navigate to the airport", "tap ok", "type hello",
        ).forEach { assertNotNull("router rejects '$it'", CommandRouter.route(it)) }
    }

    @Test fun everyFixedCommandIsOfferedWhenSaidOutright() {
        for (p in c.fixed) assertTrue(p, c.grammar(p).contains("\"$p\""))
        assertTrue(c.grammar("anything").startsWith("root ::= "))
    }

    @Test fun grammarOffersOnlyCommandsTheRequestHintsAt() {
        val restart = c.grammar("restart the phone")
        assertTrue(restart.contains("\"restart the phone\""))
        assertFalse(restart.contains("\"turn on battery saver\""))
        val screen = c.grammar("turn the display all the way down")
        assertFalse(screen.contains("\"turn off mobile data\""))
        assertTrue(screen.contains("\"brightness min\""))
        val draft = c.grammar("write a note to franklin saying hi")
        assertFalse(draft.contains("\"whats the date\""))
        assertTrue(draft.contains("\"draft a message to \""))
        val joke = c.grammar("tell me a joke")
        assertFalse(joke.contains("\"what time is it\""))
        assertTrue(joke.contains("\"none\""))
    }

    @Test fun acceptsValidOutput() {
        assertEquals("flashlight on", c.accept("flashlight on", "could you flip the torch on"))
        assertEquals("open notifications", c.accept("open notifications", "show me what came in"))
        assertEquals("call mom", c.accept("call mom", "give mom a ring"))
    }

    @Test fun rejectsNoneAndJunk() {
        assertNull(c.accept("none", "whats the weather"))
        assertNull(c.accept("", "anything"))
    }

    @Test fun rejectsInventedNames() {
        // The user never said "dad": a guessed contact must not be called.
        assertNull(c.accept("call dad", "give mom a ring"))
        assertNull(c.accept("open netflix", "fire up spotify"))
        assertNull(c.accept("text bob saying hi", "message alice hi"))
    }

    @Test fun promptSplitsIntoCachedPrefixAndRequest() {
        assertTrue(c.prefix.endsWith("<|im_start|>user\n"))
        assertTrue(c.suffix("flip the torch on") == "flip the torch on<|im_end|>\n<|im_start|>assistant\n")
    }

    @Test fun rejectsInventedSlotText() {
        assertNull(c.accept("navigate to the airport", "how do i get to the station"))
        assertNull(c.accept("tap cancel", "press the ok button"))
        assertNull(c.accept("type hello there", "type hi"))
        assertEquals("search for weather in london", c.accept("search for weather in london", "look up the weather in london"))
    }
}
