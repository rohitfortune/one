package com.rohit.one.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rohit.one.data.Password
import com.rohit.one.data.PasswordRepository
import com.rohit.one.data.CreditCard
import com.rohit.one.data.CreditCardRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VaultsViewModel(
    private val passwordRepository: PasswordRepository,
    private val cardRepository: CreditCardRepository
) : ViewModel() {

    val passwords: StateFlow<List<Password>> = passwordRepository.getAllPasswords().map { it.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<CreditCard>> = cardRepository.getAllCards().map { it.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addPassword(title: String, username: String, rawPassword: String) {
        viewModelScope.launch { passwordRepository.addPassword(title, username, rawPassword) }
    }

    fun updatePassword(password: Password, rawPassword: String?) {
        viewModelScope.launch { passwordRepository.updatePassword(password, rawPassword) }
    }

    fun deletePassword(password: Password) {
        viewModelScope.launch { passwordRepository.deletePassword(password) }
    }

    fun getRawPassword(uuid: String): String? = passwordRepository.getRawPassword(uuid)

    // Cards
    fun addCard(cardholderName: String, fullNumber: String, brand: String?) {
        viewModelScope.launch { cardRepository.addCard(cardholderName, fullNumber, brand) }
    }

    fun updateCard(card: CreditCard, fullNumber: String?) {
        viewModelScope.launch { cardRepository.updateCard(card, fullNumber) }
    }

    fun deleteCard(card: CreditCard) {
        viewModelScope.launch { cardRepository.deleteCard(card) }
    }

    fun getFullNumber(uuid: String): String? = cardRepository.getFullNumber(uuid)

    companion object {
        fun provideFactory(passwordRepo: PasswordRepository, cardRepo: CreditCardRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(VaultsViewModel::class.java)) {
                        return VaultsViewModel(passwordRepo, cardRepo) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}
