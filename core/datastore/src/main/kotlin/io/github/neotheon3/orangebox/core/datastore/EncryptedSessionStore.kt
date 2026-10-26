package io.github.neotheon3.orangebox.core.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.network.BackendJson
import io.github.neotheon3.orangebox.core.network.Session
import io.github.neotheon3.orangebox.core.network.SessionStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypts small secrets. [AndroidKeystoreSecretBox] on devices; tests use a software key. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** Null if the data can't be opened (key lost or rotated, data corrupt). */
    fun open(sealed: ByteArray): ByteArray?
}

/** AES-256-GCM, 12-byte random IV prepended, so the IV travels with the ciphertext. */
open class AesGcmSecretBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }.getOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * The key lives in the Android Keystore (hardware-backed where available) and never leaves it. No Google
 * Play Services or Tink needed. Backups are off for the app, and a restored copy couldn't be opened anyway.
 */
class AndroidKeystoreSecretBox : AesGcmSecretBox({ keystoreKey() }) {
    private companion object {
        const val ALIAS = "orangebox_session_v1"

        fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}

/**
 * The backend session (access and refresh token, member id), sealed with a [SecretBox] in its own DataStore
 * file. Tokens never go to logs or to the settings file. A session that can't be opened reads as signed out.
 */
@Singleton
class EncryptedSessionStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val box: SecretBox,
) : SessionStore {

    @Inject constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(FILE) }),
        AndroidKeystoreSecretBox(),
    )

    @Serializable private data class Stored(
        val accessToken: String,
        val refreshToken: String,
        val memberId: String,
        val accessExpiresAtMillis: Long,
        val refreshExpiresAtMillis: Long,
    )

    override suspend fun read(): Session? {
        val sealed = dataStore.data.first()[KEY] ?: return null
        val plain = runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull()?.let(box::open) ?: return null
        val s = runCatching { BackendJson.decodeFromString(Stored.serializer(), plain.decodeToString()) }.getOrNull() ?: return null
        return Session(s.accessToken, s.refreshToken, s.memberId, s.accessExpiresAtMillis, s.refreshExpiresAtMillis)
    }

    override suspend fun write(session: Session) {
        val json = BackendJson.encodeToString(
            Stored.serializer(),
            Stored(session.accessToken, session.refreshToken, session.memberId, session.accessExpiresAtMillis, session.refreshExpiresAtMillis),
        )
        val sealed = Base64.encodeToString(box.seal(json.encodeToByteArray()), Base64.NO_WRAP)
        dataStore.edit { it[KEY] = sealed }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    companion object {
        internal const val FILE = "backend_session"
        internal val KEY = stringPreferencesKey("session_sealed")

        fun forTests(dataStore: DataStore<Preferences>, box: SecretBox) = EncryptedSessionStore(dataStore, box)
    }
}
