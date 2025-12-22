@file:Suppress("UNUSED")
package com.rohit.one.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class PasswordRepository(private val passwordDao: PasswordDao, private val context: Context) {

    private val prefsName = "com.rohit.one.passwords"

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun getAllPasswords(): Flow<List<Password>> = passwordDao.getAllPasswords()

    suspend fun addPassword(title: String, username: String, rawPassword: String) {
        val uuid = UUID.randomUUID().toString()
        val password = Password(uuid = uuid, title = title, username = username)
        passwordDao.insertPassword(password)
        val enc = CryptoUtil.encrypt(rawPassword)
        prefs.edit().putString("pw_${uuid}", enc).apply()
    }

    suspend fun upsertPasswordFromBackup(uuid: String, title: String, username: String, rawPassword: String?, createdAt: Long) {
        // check by uuid
        val existing = passwordDao.getByUuid(uuid)
        if (existing == null) {
            val password = Password(uuid = uuid, title = title, username = username, createdAt = createdAt)
            passwordDao.insertPassword(password)
        } else {
            val updated = existing.copy(title = title, username = username, createdAt = createdAt)
            passwordDao.updatePassword(updated)
        }
        rawPassword?.let {
            val enc = CryptoUtil.encrypt(it)
            prefs.edit().putString("pw_${uuid}", enc).apply()
        }
    }

    suspend fun updatePassword(password: Password, rawPassword: String?) {
        passwordDao.updatePassword(password)
        rawPassword?.let {
            val enc = CryptoUtil.encrypt(it)
            prefs.edit().putString("pw_${password.uuid}", enc).apply()
        }
    }

    suspend fun deletePassword(password: Password) {
        passwordDao.deletePassword(password)
        prefs.edit().remove("pw_${password.uuid}").apply()
    }

    fun getRawPassword(uuid: String): String? = CryptoUtil.decrypt(prefs.getString("pw_${uuid}", null))
}
