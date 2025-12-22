@file:Suppress("UNUSED")
package com.rohit.one.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import androidx.core.content.edit

class CreditCardRepository(private val creditCardDao: CreditCardDao, private val context: Context) {

    private val prefsName = "com.rohit.one.cards"

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun getAllCards(): Flow<List<CreditCard>> = creditCardDao.getAllCards()

    suspend fun addCard(
        cardholderName: String,
        fullNumber: String,
        brand: String?,
        expiry: String?,
        securityCode: String?
    ) {
        val uuid = UUID.randomUUID().toString()
        val last4 = if (fullNumber.length >= 4) fullNumber.takeLast(4) else fullNumber
        val card = CreditCard(
            uuid = uuid,
            cardholderName = cardholderName,
            last4 = last4,
            brand = brand,
            expiry = expiry,
            securityCode = securityCode
        )
        creditCardDao.insertCard(card)
        val enc = CryptoUtil.encrypt(fullNumber)
        prefs.edit { putString("card_${uuid}", enc) }
    }

    suspend fun upsertCardFromBackup(
        uuid: String,
        cardholderName: String,
        fullNumber: String?,
        brand: String?,
        expiry: String?,
        securityCode: String?,
        createdAt: Long
    ) {
        val existing = creditCardDao.getByUuid(uuid)
        val last4 = fullNumber?.let { if (it.length >= 4) it.takeLast(4) else it } ?: ""
        if (existing == null) {
            val card = CreditCard(
                uuid = uuid,
                cardholderName = cardholderName,
                last4 = last4,
                brand = brand,
                expiry = expiry,
                securityCode = securityCode,
                createdAt = createdAt
            )
            creditCardDao.insertCard(card)
        } else {
            val updated = existing.copy(
                cardholderName = cardholderName,
                last4 = last4,
                brand = brand,
                expiry = expiry,
                securityCode = securityCode,
                createdAt = createdAt
            )
            creditCardDao.updateCard(updated)
        }
        fullNumber?.let {
            val enc = CryptoUtil.encrypt(it)
            prefs.edit { putString("card_${uuid}", enc) }
        }
    }

    suspend fun updateCard(card: CreditCard, fullNumber: String?) {
        creditCardDao.updateCard(card)
        fullNumber?.let {
            val enc = CryptoUtil.encrypt(it)
            prefs.edit { putString("card_${card.uuid}", enc) }
        }
    }

    suspend fun deleteCard(card: CreditCard) {
        creditCardDao.deleteCard(card)
        prefs.edit { remove("card_${card.uuid}") }
    }

    fun getFullNumber(uuid: String): String? = CryptoUtil.decrypt(prefs.getString("card_${uuid}", null))
}
