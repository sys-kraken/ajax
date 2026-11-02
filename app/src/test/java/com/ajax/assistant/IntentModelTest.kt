package com.ajax.assistant

import org.junit.Assert.assertEquals
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
            "open spotify", "app info for spotify", "call mom", "text mom saying on my way",
        ).forEach { assertNotNull("router rejects '$it'", CommandRouter.route(it)) }
    }

    @Test fun grammarListsEveryFixedCommand() {
        val g = c.grammar()
        for (p in c.fixed) assertTrue(p, g.contains("\"$p\""))
        assertTrue(g.startsWith("root ::= "))
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

    @Test fun promptEndsWithAssistantTurn() {
        val p = c.prompt("flip the torch on")
        assertTrue(p.endsWith("<|im_start|>user\nflip the torch on<|im_end|>\n<|im_start|>assistant\n"))
    }
}
