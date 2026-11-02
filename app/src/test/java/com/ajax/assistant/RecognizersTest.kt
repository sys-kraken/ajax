package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizersTest {
    private val own = "com.ajax.assistant"

    @Test fun prefersGoogleOverOthers() =
        assertEquals(1, Recognizers.choose(listOf(own, "com.google.android.googlequicksearchbox", "x.y"), own))

    @Test fun neverPicksItself() = assertEquals(-1, Recognizers.choose(listOf(own), own))

    @Test fun fallsBackToAnyOther() = assertEquals(1, Recognizers.choose(listOf(own, "x.y"), own))
}
