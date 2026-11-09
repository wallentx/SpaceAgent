package com.example.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object SpaceWebPolicy {
    fun sameOrigin(url: String, origin: String): Boolean {
        val candidate = url.toHttpUrlOrNull() ?: return false
        val expected = origin.toHttpUrlOrNull() ?: return false
        return candidate.isHttps && expected.isHttps && candidate.host == expected.host && candidate.port == expected.port
    }

    fun canNavigateInside(url: String, spaceOrigin: String): Boolean {
        val address = url.toHttpUrlOrNull() ?: return false
        return address.isHttps && (sameOrigin(url, spaceOrigin) || address.host == "huggingface.co" && address.port == 443)
    }

    fun isWebLink(url: String): Boolean = url.toHttpUrlOrNull()?.isHttps == true
}
