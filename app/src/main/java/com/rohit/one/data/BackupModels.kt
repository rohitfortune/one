package com.rohit.one.data

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PasswordExport(val uuid: String, val title: String, val username: String, val rawPassword: String?, val createdAt: Long)

@JsonClass(generateAdapter = true)
data class CardExport(
    val uuid: String,
    val cardholderName: String,
    val last4: String,
    val fullNumber: String?,
    val brand: String?,
    val expiry: String?,
    val securityCode: String?,
    val createdAt: Long
)

@JsonClass(generateAdapter = true)
data class NoteExport(
    val title: String,
    val content: String,
    val attachments: List<Note.Attachment> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BackupPayload(
    val notes: List<NoteExport>,
    val passwords: List<PasswordExport>,
    val cards: List<CardExport>
)
