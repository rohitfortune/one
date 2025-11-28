package com.rohit.one.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CreditCardRepository(private val creditCardDao: CreditCardDao, private val context: Context) {

    private val prefsName = "com.rohit.one.cards"

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    fun getAllCards(): Flow<List<CreditCard>> = creditCardDao.getAllCards()

    suspend fun addCard(cardholderName: String, fullNumber: String, brand: String?) {
        val uuid = UUID.randomUUID().toString()
        val last4 = if (fullNumber.length >= 4) fullNumber.takeLast(4) else fullNumber
        val card = CreditCard(uuid = uuid, cardholderName = cardholderName, last4 = last4, brand = brand)
        creditCardDao.insertCard(card)
        val enc = CryptoUtil.encrypt(fullNumber)
        prefs.edit { putString("card_${uuid}", enc) }
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
