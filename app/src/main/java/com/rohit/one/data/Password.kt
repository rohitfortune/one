package com.rohit.one.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Password(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val uuid: String,
    val title: String,
    val username: String,
    val createdAt: Long = System.currentTimeMillis()
)

