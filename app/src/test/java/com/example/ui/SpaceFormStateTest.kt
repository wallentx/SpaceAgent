package com.example.ui

import com.example.model.SpaceInputType
import com.example.model.SpaceParameterSpec
import org.junit.Assert.*
import org.junit.Test

class SpaceFormStateTest {
    private fun spec(index: Int = 0, name: String = "seed", type: SpaceInputType = SpaceInputType.NUMBER, default: String = "-1") =
        SpaceParameterSpec(index, name, name, type.name, type, defaultValue = default)

    @Test fun `unbounded defaults retain negative and large numbers`() {
        assertEquals(-1.0, SpaceFormState.values(listOf(spec())).single().numberValue)
        assertEquals(1024.0, SpaceFormState.values(listOf(spec(default = "1024"))).single().numberValue)
    }

    @Test fun `schema refresh preserves edits by identity when parameter order changes`() {
        val previous = SpaceFormState.values(listOf(spec(type = SpaceInputType.FILE)))
            .map { it.copy(fileUri = "content://documents/image", fileName = "image.png") }
        val refreshed = SpaceFormState.values(listOf(spec(index = 2, type = SpaceInputType.FILE)), previous).single()
        assertEquals(2, refreshed.paramIndex)
        assertEquals("content://documents/image", refreshed.fileUri)
        assertEquals("image.png", refreshed.fileName)
    }

    @Test fun `required empty inputs and invalid numbers are rejected`() {
        val specs = listOf(spec(type = SpaceInputType.TEXT, default = ""), spec(1, "file", SpaceInputType.FILE), spec(2))
        val values = SpaceFormState.values(specs).map { if (it.paramIndex == 2) it.copy(numberValue = Double.NaN) else it }
        assertEquals(setOf(0, 1, 2), SpaceFormState.errors(specs, values).keys)
    }

    @Test fun `zero input endpoint is valid`() {
        assertTrue(SpaceFormState.errors(emptyList(), emptyList()).isEmpty())
    }

    @Test fun `required number without a default stays empty until entered`() {
        val specs = listOf(spec(default = "").copy(min = 3f))
        val values = SpaceFormState.values(specs)
        assertNull(values.single().numberValue)
        assertEquals("", values.single().textValue)
        assertEquals(setOf(0), SpaceFormState.errors(specs, values).keys)
    }

    @Test fun `nullable defaults stay null for every supported input type`() {
        val specs = SpaceInputType.entries.mapIndexed { index, type ->
            spec(index, type.name, type, "").copy(isRequired = false, defaultIsNull = true,
                choices = listOf("first", "second"))
        }
        val values = SpaceFormState.values(specs)
        assertTrue(values.all { it.isNull })
        assertTrue(SpaceFormState.errors(specs, values).isEmpty())
        assertEquals("", values.first { it.inputType == SpaceInputType.DROPDOWN }.textValue)
    }

    @Test fun `required choice does not silently select the first option`() {
        val specs = listOf(spec(type = SpaceInputType.DROPDOWN, default = "").copy(choices = listOf("a", "b")))
        assertEquals(setOf(0), SpaceFormState.errors(specs, SpaceFormState.values(specs)).keys)
    }

    @Test fun `large integer defaults retain their exact text`() {
        val value = SpaceFormState.values(listOf(spec(default = "9223372036854775807"))).single()
        assertEquals("9223372036854775807", value.textValue)
        assertTrue(value.toDisplaySummary().contains("9223372036854775807"))
    }

    @Test fun `canonical URLs ignore query and fragment`() {
        assertEquals("owner/name", SpaceAddress.parse("https://huggingface.co/spaces/owner/name/?x=1#app"))
        assertEquals("owner/name", SpaceAddress.parse(" owner/name "))
    }

    @Test fun `ambiguous embed URLs and extra path segments are rejected`() {
        for (input in listOf("https://owner-name.hf.space", "owner/name/tree/main", "https://evil.example/spaces/owner/name")) {
            assertTrue(runCatching { SpaceAddress.parse(input) }.isFailure)
        }
    }
}
