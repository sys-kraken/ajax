package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallVoiceTest {
    @Test fun repliesWhileRinging() {
        assertEquals(CallVoice.Reply.ANSWER, CallVoice.reply("answer"))
        assertEquals(CallVoice.Reply.ANSWER, CallVoice.reply("Pick up"))
        assertEquals(CallVoice.Reply.DECLINE, CallVoice.reply("decline"))
        assertEquals(CallVoice.Reply.DECLINE, CallVoice.reply("not now"))
        assertNull(CallVoice.reply("[unk] answer"))
        assertNull(CallVoice.reply("who is it"))
    }

    @Test fun everyReplyIsListenedFor() {
        for (p in CallVoice.phrases) assertTrue(p, CallVoice.reply(p) != null)
    }

    @Test fun answerAndHangUpCommands() {
        for (p in listOf("answer the call", "pick up", "answer it", "take the call"))
            assertEquals(p, "call-answer", CommandRouter.route(p)?.id)
        for (p in listOf("hang up", "end the call", "decline the call", "reject call"))
            assertEquals(p, "call-end", CommandRouter.route(p)?.id)
        assertEquals("call", CommandRouter.route("call mom")?.id)
    }

    @Test fun workWhileLocked() {
        assertTrue(WakeWords.allowedHandsFree("call-answer", false))
        assertTrue(WakeWords.allowedHandsFree("call-end", false))
    }
}
