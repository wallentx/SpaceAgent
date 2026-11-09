package com.example.data

import org.junit.Assert.*
import org.junit.Test

class UriGrantTrackerTest {
    @Test fun `changing or closing form retains the file used by a running upload`() {
        val acquired = mutableListOf<String>()
        val released = mutableListOf<String>()
        val tracker = UriGrantTracker(acquire = { acquired += it; true }, release = { released += it; true })
        tracker.replace("form", setOf("old"))
        tracker.replace("request", setOf("old"))
        tracker.replace("form", setOf("new"))
        assertTrue(released.isEmpty())
        tracker.replace("form", emptySet())
        assertEquals(listOf("new"), released)
        tracker.replace("request", emptySet())
        assertEquals(listOf("new", "old"), released)
        assertEquals(listOf("old", "new"), acquired)
    }

    @Test fun `startup releases abandoned grants but preserves restored input`() {
        val released = mutableListOf<String>()
        val tracker = UriGrantTracker(setOf("abandoned", "selected"), acquire = { true },
            release = { released += it; true })
        tracker.replace("form", setOf("selected"))
        assertEquals(listOf("abandoned"), released)
    }

    @Test fun `failed cleanup is retried and temporary grants are not released as persisted`() {
        var releases = 0
        val tracker = UriGrantTracker(acquire = { it != "temporary" }, release = { ++releases > 1 })
        tracker.replace("form", setOf("persisted", "temporary"))
        tracker.replace("form", emptySet())
        assertEquals(1, releases)
        tracker.replace("form", emptySet())
        assertEquals(2, releases)
        tracker.replace("form", emptySet())
        assertEquals(2, releases)
    }
}
