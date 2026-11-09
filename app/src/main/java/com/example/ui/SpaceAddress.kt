package com.example.ui

import java.net.URI

internal object SpaceAddress {
    fun parse(input: String): String {
        val text = input.trim()
        val id = if (text.startsWith("https://") || text.startsWith("http://")) {
            val uri = URI(text)
            require(uri.scheme == "https" && uri.host == "huggingface.co" && uri.path.startsWith("/spaces/")) {
                "Use a huggingface.co/spaces/owner/name URL or an owner/name ID."
            }
            uri.path.removePrefix("/spaces/").trimEnd('/')
        } else text
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*").matches(id)) {
            "Enter a Space ID in owner/name format."
        }
        return id
    }
}
