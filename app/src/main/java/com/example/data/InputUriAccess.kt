package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.model.SpaceInputValue

/** Grants belong to both the editable form and any execution using its snapshot. */
internal class UriGrantTracker(
    persisted: Set<String> = emptySet(),
    private val acquire: (String) -> Boolean,
    private val release: (String) -> Boolean
) {
    private val owners = mutableMapOf<Any, Set<String>>()
    private val held = persisted.toMutableSet()

    @Synchronized
    fun replace(owner: Any, uris: Set<String>) {
        if (uris.isEmpty()) owners.remove(owner) else owners[owner] = uris.toSet()
        val needed = owners.values.flatten().toSet()
        (needed - held).forEach { if (acquire(it)) held.add(it) }
        (held - needed).forEach { if (release(it)) held.remove(it) }
    }
}

internal object InputUriAccess {
    private var tracker: UriGrantTracker? = null

    @Synchronized
    fun retain(context: Context, owner: Any, inputs: List<SpaceInputValue>) {
        val resolver = context.applicationContext.contentResolver
        val grants = tracker ?: UriGrantTracker(
            persisted = resolver.persistedUriPermissions.filter { it.isReadPermission }
                .map { it.uri.toString() }.toSet(),
            acquire = { uri ->
                try {
                    resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                } catch (_: SecurityException) {
                    // Some providers offer only temporary access; the request still reports read failures.
                    false
                }
            },
            release = { uri ->
                try {
                    resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                } catch (_: SecurityException) {
                    // The provider may already have revoked this grant.
                    true
                }
            }
        ).also { tracker = it }
        grants.replace(owner, inputs.filter { !it.isNull && it.inputType.isMediaOrFile }
            .mapNotNull { it.fileUri }.filter { Uri.parse(it).scheme == "content" }.toSet())
    }

    @Synchronized
    fun release(owner: Any) {
        tracker?.replace(owner, emptySet())
    }

    fun sessionOwner(sessionId: String): String = "session:$sessionId"
}
