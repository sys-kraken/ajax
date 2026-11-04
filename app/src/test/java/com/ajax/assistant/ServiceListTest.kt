package com.ajax.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceListTest {
    private val pkg = "com.ajax.assistant"
    private val cls = "com.ajax.assistant.AjaxAccessibilityService"
    private val full = "$pkg/$cls"
    private val short = "$pkg/.AjaxAccessibilityService"
    private val other = "com.other/.Reader"

    @Test fun addsToEmptyOrMissingList() {
        assertEquals(full, ServiceList.with(null, pkg, cls))
        assertEquals(full, ServiceList.with("", pkg, cls))
    }

    @Test fun addingKeepsOtherServices() {
        assertEquals("$other:$full", ServiceList.with(other, pkg, cls))
    }

    @Test fun addingTwiceChangesNothing() {
        assertEquals("$other:$full", ServiceList.with("$other:$full", pkg, cls))
        assertEquals(short, ServiceList.with(short, pkg, cls))
    }

    @Test fun removesFullAndShortNames() {
        assertEquals(other, ServiceList.without("$full:$other", pkg, cls))
        assertEquals(other, ServiceList.without("$other:$short", pkg, cls))
        assertEquals("", ServiceList.without(full, pkg, cls))
        assertEquals("", ServiceList.without(null, pkg, cls))
    }

    @Test fun otherServicesAreLeftAlone() {
        assertEquals(other, ServiceList.without(other, pkg, cls))
        assertFalse(ServiceList.has("$pkg/.SomethingElse", pkg, cls))
        assertTrue(ServiceList.has(short, pkg, cls))
    }
}
