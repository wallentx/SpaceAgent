package com.example.service

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecutionServiceTest {
    @Test fun `late cancellation cleanup cannot clear a replacement job`() {
        val controller = Robolectric.buildService(SpaceAgentExecutionService::class.java).create()
        val service = controller.get()
        val oldJob = Job()
        val replacement = Job()
        try {
            oldJob.invokeOnCompletion { service.finishAgentSession("old") }
            ReflectionHelpers.setField(service, "activeSessionId", "replacement")
            ReflectionHelpers.setField(service, "agentJob", replacement)
            oldJob.cancel()
            assertSame(replacement, ReflectionHelpers.getField<Job>(service, "agentJob"))
            assertEquals("replacement", ReflectionHelpers.getField<String>(service, "activeSessionId"))
            assertTrue(replacement.isActive)
        } finally {
            replacement.cancel()
            controller.destroy()
        }
    }
}
