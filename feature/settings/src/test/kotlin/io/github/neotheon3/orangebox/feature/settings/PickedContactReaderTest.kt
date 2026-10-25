package io.github.neotheon3.orangebox.feature.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #60: the picker intent needs no permission, and only the picked row is read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickedContactReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    class FakeContacts : ContentProvider() {
        var lastUri: Uri? = null
        var lastProjection: List<String>? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            lastUri = uri; lastProjection = projection?.toList()
            val c = MatrixCursor(arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER))
            when (uri.lastPathSegment) {
                "1" -> c.addRow(arrayOf("Asha", "+91 98765 43210"))
                "2" -> c.addRow(arrayOf<Any?>(null, " "))
            }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    @Test fun `picker intent asks for a phone number`() {
        val intent = PickPhoneNumber.intent()
        assertEquals(Intent.ACTION_PICK, intent.action)
        assertEquals(Phone.CONTENT_TYPE, intent.type)
    }

    @Test fun `reads name and number of the picked row only`() {
        val provider = Robolectric.setupContentProvider(FakeContacts::class.java, "com.android.contacts")
        val uri = Uri.parse("content://com.android.contacts/data/1")
        assertEquals(PickedContact("Asha", "+91 98765 43210"), PickedContactReader.read(context, uri))
        assertEquals(uri, provider.lastUri)
        assertEquals(listOf(Phone.DISPLAY_NAME, Phone.NUMBER), provider.lastProjection)
        assertNull("blank number", PickedContactReader.read(context, Uri.parse("content://com.android.contacts/data/2")))
        assertNull("no row", PickedContactReader.read(context, Uri.parse("content://com.android.contacts/data/3")))
    }

    @Test fun `parse result needs RESULT_OK`() {
        val data = Intent().setData(Uri.parse("content://com.android.contacts/data/1"))
        assertEquals(data.data, PickPhoneNumber().parseResult(android.app.Activity.RESULT_OK, data))
        assertNull(PickPhoneNumber().parseResult(android.app.Activity.RESULT_CANCELED, data))
    }
}
