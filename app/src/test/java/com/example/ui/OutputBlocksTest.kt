package com.example.ui

import org.junit.Assert.*
import org.junit.Test

class OutputBlocksTest {
    @Test fun `prose and multiple code languages are retained in order`() {
        val blocks = parseOutputBlocks("Intro\n```kotlin\nval x = 1\n```\nExplanation\n```python\nprint(x)\n```\nDone")
        assertEquals(listOf("Intro", "val x = 1", "Explanation", "print(x)", "Done"), blocks.map { it.text })
        assertEquals(listOf(false, true, false, true, false), blocks.map { it.isCode })
    }

    @Test fun `an unfinished fence still exposes all returned code`() {
        assertEquals(listOf(OutputBlock("first\nsecond", true)), parseOutputBlocks("```js\nfirst\nsecond"))
    }

    @Test fun `inline backticks do not swallow prose and longer fences can contain shorter ones`() {
        assertEquals(listOf(OutputBlock("Use ``` inline", false)), parseOutputBlocks("Use ``` inline"))
        assertEquals(listOf(OutputBlock("```", true)), parseOutputBlocks("````text\n```\n````"))
    }
}
