package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSessionGateTest {
    @Test
    fun `overlapping session is rejected until active session finishes`() {
        val gate = ActiveSessionGate()

        assertTrue(gate.tryStart("first"))
        assertFalse(gate.tryStart("second"))
        assertEquals("first", gate.currentId())
        assertTrue(gate.finish("first"))
        assertTrue(gate.tryStart("second"))
    }

    @Test
    fun `stale callbacks cannot finish the active session`() {
        val gate = ActiveSessionGate()
        gate.tryStart("current")

        assertFalse(gate.accepts("stale"))
        assertFalse(gate.finish("stale"))
        assertTrue(gate.accepts("current"))
        assertTrue(gate.finish("current"))
        assertNull(gate.currentId())
    }
}
