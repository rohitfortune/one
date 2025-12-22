package com.rohit.one.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class CreditCard(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uuid: String,
    val cardholderName: String,
    val last4: String,
    val brand: String? = null,
    val expiry: String? = null,
    val securityCode: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
