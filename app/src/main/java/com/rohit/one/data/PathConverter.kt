package com.rohit.one.data

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class PathConverter {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val pathListType = Types.newParameterizedType(List::class.java, Note.Path::class.java)
    private val pathListAdapter = moshi.adapter<List<Note.Path>>(pathListType)

    @TypeConverter
    fun fromPathList(paths: List<Note.Path>?): String? {
        if (paths == null) return null
        return pathListAdapter.toJson(paths)
    }

    @TypeConverter
    fun toPathList(json: String?): List<Note.Path>? {
        if (json == null) return null
        return pathListAdapter.fromJson(json)
    }
}

