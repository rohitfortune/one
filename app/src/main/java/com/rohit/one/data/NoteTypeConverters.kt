package com.rohit.one.data

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object NoteTypeConverters {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val attachmentListType = Types.newParameterizedType(List::class.java, Note.Attachment::class.java)
    private val adapter = moshi.adapter<List<Note.Attachment>>(attachmentListType)

    @TypeConverter
    @JvmStatic
    fun attachmentsToString(value: List<Note.Attachment>?): String? {
        if (value == null) return null
        return adapter.toJson(value)
    }

    @TypeConverter
    @JvmStatic
    fun stringToAttachments(value: String?): List<Note.Attachment> {
        if (value.isNullOrBlank()) return emptyList()
        return adapter.fromJson(value) ?: emptyList()
    }
}

