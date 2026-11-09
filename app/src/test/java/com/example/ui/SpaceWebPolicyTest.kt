package com.example.ui

import org.junit.Assert.*
import org.junit.Test

class SpaceWebPolicyTest {
    private val origin = "https://owner-space.hf.space"

    @Test fun `capture permission is scoped to the exact secure Space origin`() {
        assertTrue(SpaceWebPolicy.sameOrigin("$origin/", origin))
        for (url in listOf("http://owner-space.hf.space", "https://owner-space.hf.space.evil.example",
            "https://other-space.hf.space", "https://owner-space.hf.space:444", "file:///private/data")) {
            assertFalse(url, SpaceWebPolicy.sameOrigin(url, origin))
        }
    }

    @Test fun `Hub and Space navigation stay inside but arbitrary intents cannot launch`() {
        assertTrue(SpaceWebPolicy.canNavigateInside("https://huggingface.co/login", origin))
        assertTrue(SpaceWebPolicy.canNavigateInside("$origin/?theme=dark", origin))
        assertFalse(SpaceWebPolicy.canNavigateInside("https://evil.example", origin))
        assertFalse(SpaceWebPolicy.isWebLink("intent://camera/#Intent;end"))
        assertFalse(SpaceWebPolicy.isWebLink("javascript:alert(1)"))
        assertFalse(SpaceWebPolicy.isWebLink("content://private/item"))
    }
}
