package com.rohit.one.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachment",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val noteId: Int,
    val uri: String,
    val displayName: String? = null,
    val mimeType: String? = null
)

