package com.rohit.one.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
        prefs.edit { putString("pw_${uuid}", enc) }
    }

    suspend fun updatePassword(password: Password, rawPassword: String?) {
        passwordDao.updatePassword(password)
        rawPassword?.let {
            val enc = CryptoUtil.encrypt(it)
            prefs.edit { putString("pw_${password.uuid}", enc) }
        }
    }

    suspend fun deletePassword(password: Password) {
        passwordDao.deletePassword(password)
        prefs.edit { remove("pw_${password.uuid}") }
    }

    fun getRawPassword(uuid: String): String? = CryptoUtil.decrypt(prefs.getString("pw_${uuid}", null))
}
