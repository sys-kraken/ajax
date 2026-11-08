package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemDumpTest {
    @Test fun recordingAppsFromTheAudioReport() {
        val report = """
            Recording activity:
              session:14665 -- source client=VOICE_RECOGNITION, dev=1ch 16000Hz -- uid:10398 -- patch:2538 -- pack:com.ajax.assistant -- format client=1ch
              session:14673 -- source client=MIC, dev=1ch 48000Hz -- uid:10327 -- patch:2541 -- pack:com.sec.android.app.voicenote -- format client=1ch
            Events log: recording activity received by AudioService
            11-08 21:51:13:021 rec stop riid:13575 uid:10398 session:14177 src:VOICE_RECOGNITION not silenced pack:com.spy.app
        """.trimIndent()
        assertEquals(listOf("com.ajax.assistant", "com.sec.android.app.voicenote"), SystemDump.recordingPackages(report))
    }

    @Test fun appsBehindSessionsEvenAfterTheyStopped() {
        val report = """
            Recording activity:
              session:345 -- source client=VOICE_RECOGNITION -- uid:10398 -- patch:57 -- pack:com.ajax.assistant -- format
            Events log: recording activity received by AudioService
            11-09 02:26:55:062 rec start riid:6495 uid:10270 session:8369 src:VOICE_RECOGNITION not silenced pack:com.google.android.tts
            11-09 02:26:56:207 rec stop riid:6495 uid:10270 session:8369 src:VOICE_RECOGNITION not silenced pack:com.google.android.tts
            11-09 02:24:46:666 rec start riid:6399 uid:10327 session:8249 src:MIC not silenced pack:com.sec.android.app.voicenote
        """.trimIndent()
        assertEquals(listOf("com.google.android.tts"), SystemDump.packagesForSessions(report, setOf(8369)))
        assertEquals(listOf("com.sec.android.app.voicenote", "com.google.android.tts"), SystemDump.packagesForSessions(report, setOf(8249, 8369)).sortedDescending())
        assertEquals(emptyList<String>(), SystemDump.packagesForSessions(report, setOf(1)))
    }

    @Test fun backgroundAppsFromTheProcessList() {
        val report = """
            ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)
              Activities:
              #52: fg     TOP  LCMN 12345:com.android.chrome/u0a123 act:activities|recents
              Other:
              #50: prcp   FGS  ---N 2222:com.spotify.music/u0a201
              #49: svc    SVC  ---- 3333:com.whatsapp:remote/u0a202
              #48: cch    CEM  ---- 4444:com.facebook.orca/u0a203
              #47: pers   PER  LCM- 1500:com.android.systemui/u0a90
              #46: fg     BFGS ---- 1600:system/1000
        """.trimIndent()
        assertEquals(listOf("com.spotify.music", "com.whatsapp"), SystemDump.backgroundPackages(report))
    }

    @Test fun batteryInAnyWords() {
        for (q in listOf("battery percent", "Battery percentage", "battery", "how much battery do I have", "whats my battery level", "battery left"))
            assertEquals(q, "info-battery", CommandRouter.route(q)?.id)
        for (q in listOf("turn on battery saver", "battery settings", "battery usage"))
            assertEquals(q, false, CommandRouter.route(q)?.id == "info-battery")
    }

    @Test fun questionsRouteToTheNewCommands() {
        assertEquals("info-running-apps", CommandRouter.route("what applications are currently running in the background")?.id)
        assertEquals("info-running-apps", CommandRouter.route("which apps are running")?.id)
        assertEquals("info-mic-users", CommandRouter.route("which app is using the microphone")?.id)
        assertEquals("info-mic-users", CommandRouter.route("who is using the mic")?.id)
    }
}
