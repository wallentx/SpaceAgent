package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import com.example.model.SpaceResultFile

internal class SaveSpaceResult : ActivityResultContract<SpaceResultFile, Uri?>() {
    override fun createIntent(context: Context, input: SpaceResultFile): Intent {
        val name = input.name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), "_").take(180).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "result"
        val mime = input.mimeType?.substringBefore(';')?.takeIf { it.matches(Regex("[\\w.+-]+/[\\w.+-]+")) }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_TITLE, name)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data?.takeIf { it.scheme == "content" } else null
}
