package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SaveSpaceResultTest {
    @Test fun `saving uses the system document picker with a safe filename and image type`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contract = SaveSpaceResult()
        val intent = contract.createIntent(context, SpaceResultFile("https://space.test/image.png", "../../image.png", ResultMediaKind.IMAGE))
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("image.png", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("image/png", intent.type)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(Uri.parse("content://document/new"))))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.parse("file:///private/token"))))
        assertEquals(Uri.parse("content://document/new"), contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://document/new"))))
    }
}
