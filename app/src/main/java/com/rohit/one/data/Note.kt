package com.rohit.one.data

import androidx.compose.ui.graphics.Path
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val paths: List<Note.Path> = emptyList(),
    val lastModified: Long = System.currentTimeMillis()
) {
    data class Attachment(
        val uri: String,
        val displayName: String? = null,
        val mimeType: String? = null
    )

    data class Path(
        val points: List<Point>,
        val anchorBlockIndex: Int? = null,
        val anchorLocalTop: Float? = null,
        val anchorCreatedTop: Float? = null
    )

    data class Point(
        val x: Float,
        val y: Float
    )
}
