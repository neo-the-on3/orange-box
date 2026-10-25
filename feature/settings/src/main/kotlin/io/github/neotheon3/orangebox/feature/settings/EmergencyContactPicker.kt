package io.github.neotheon3.orangebox.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.result.contract.ActivityResultContract

/** A phone number the user picked in the system contact picker. */
data class PickedContact(val name: String?, val number: String)

/**
 * The system phone-number picker (#60). The picker grants read access to just the row the user chose,
 * so Orange Box needs no READ_CONTACTS permission and never sees the rest of the address book.
 */
class PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = intent()

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }

    companion object {
        fun intent(): Intent = Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)
    }
}

/** Reads the picked row's name and number; null if it cannot be read (e.g. the grant was revoked). */
object PickedContactReader {
    fun read(context: Context, uri: Uri): PickedContact? = runCatching {
        context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val number = c.getString(c.getColumnIndexOrThrow(Phone.NUMBER))?.takeIf { it.isNotBlank() } ?: return@use null
            PickedContact(c.getString(c.getColumnIndexOrThrow(Phone.DISPLAY_NAME))?.takeIf { it.isNotBlank() }, number)
        }
    }.getOrNull()
}
