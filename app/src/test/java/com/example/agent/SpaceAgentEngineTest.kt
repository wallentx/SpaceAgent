package com.example.agent

import com.example.model.AgentType
import com.example.model.HfSpace
import com.example.model.StepStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpaceAgentEngineTest {
    private val space = HfSpace(
        id = "owner/demo",
        author = "owner",
        name = "demo",
        title = "Demo",
        description = "",
        sdk = "gradio",
        category = "General",
        likes = 0,
        endpointUrl = "https://owner-demo.hf.space"
    )

    @Test
    fun `network failure is propagated and never synthesized as success`() = runTest {
        val updates = mutableListOf<StepStatus>()
        val engine = SpaceAgentEngine(
            requestExecutor = { _, _ -> Result.failure(IllegalStateException("HTTP 503")) },
            testing = Unit
        )

        val error = runCatching {
            engine.executeAgentTask(
                goal = "real request",
                agentType = AgentType.GENERAL,
                space = space,
                onStepUpdate = { _, step -> updates += step.status },
                onLog = {}
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("HTTP 503", error?.message)
        assertEquals(StepStatus.FAILED, updates.last())
        assertFalse(updates.dropLast(1).contains(StepStatus.FAILED))
    }

    @Test
    fun `plain live response is returned verbatim without a stock image`() = runTest {
        val engine = SpaceAgentEngine(
            requestExecutor = { _, _ -> Result.success("actual Space output") },
            testing = Unit
        )

        val result = engine.executeAgentTask(
            goal = "real request",
            agentType = AgentType.IMAGE_STUDIO,
            space = space,
            onStepUpdate = { _, _ -> },
            onLog = {}
        )

        assertEquals("actual Space output", result.finalOutput)
        assertNull(result.imageUrl)
        assertTrue(result.steps.all { it.status == StepStatus.SUCCESS })
    }
}
