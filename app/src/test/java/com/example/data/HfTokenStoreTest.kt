package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HfTokenStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val encrypted = context.getSharedPreferences("space_agent_credentials", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences("space_agent_prefs", Context.MODE_PRIVATE)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val store = HfTokenStore(context) { key }

    @Before fun clearPreferences() {
        encrypted.edit().clear().commit()
        legacy.edit().clear().commit()
    }

    @Test fun `plaintext is removed only after encrypted storage succeeds`() {
        legacy.edit().putString("hf_api_token", "hf_test_value").commit()
        assertEquals("hf_test_value", store.read())
        assertFalse(legacy.contains("hf_api_token"))
        assertFalse(encrypted.getString("token", "")!!.contains("hf_test_value"))
        assertEquals("hf_test_value", HfTokenStore(context) { key }.read())
    }

    @Test fun `failed key access preserves the token for a later migration`() {
        legacy.edit().putString("hf_api_token", "hf_test_value").commit()
        val failing = HfTokenStore(context) { throw IllegalStateException("Key unavailable") }
        assertThrows(IllegalStateException::class.java) { failing.read() }
        assertEquals("hf_test_value", legacy.getString("hf_api_token", ""))
        assertFalse(encrypted.contains("token"))
    }

    @Test fun `successful read retries interrupted plaintext cleanup`() {
        store.write("hf_current")
        legacy.edit().putString("hf_api_token", "hf_old").commit()
        assertEquals("hf_current", store.read())
        assertFalse(legacy.contains("hf_api_token"))
    }

    @Test fun `corrupt ciphertext does not silently use an old plaintext token`() {
        encrypted.edit().putString("token", "corrupt").commit()
        legacy.edit().putString("hf_api_token", "hf_old").commit()
        assertThrows(IllegalArgumentException::class.java) { store.read() }
        store.write("")
        assertEquals("", store.read())
        assertFalse(legacy.contains("hf_api_token"))
    }

    @Test fun `credentials and session database are excluded from every backup mode`() {
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val exclusions = mutableMapOf<String, MutableSet<Pair<String, String>>>()
            context.resources.getXml(resource).use { xml ->
                var mode = ""
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.START_TAG) {
                        if (xml.name in listOf("full-backup-content", "cloud-backup", "device-transfer")) mode = xml.name
                        if (xml.name == "exclude") exclusions.getOrPut(mode) { mutableSetOf() } +=
                            xml.getAttributeValue(null, "domain") to xml.getAttributeValue(null, "path")
                    }
                    xml.next()
                }
            }
            assertEquals(if (resource == R.xml.backup_rules) 1 else 2, exclusions.size)
            exclusions.values.forEach { rules ->
                assertTrue(rules.contains("sharedpref" to "space_agent_credentials.xml"))
                assertTrue(rules.contains("sharedpref" to "space_agent_prefs.xml"))
                assertTrue(rules.contains("database" to "."))
                assertTrue(rules.contains("root" to "app_webview/"))
            }
        }
    }
}
