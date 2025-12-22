package com.rohit.one.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Note::class, Password::class, CreditCard::class], version = 8, exportSchema = false)
@TypeConverters(NoteTypeConverters::class, PathConverter::class)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun passwordDao(): PasswordDao
    abstract fun creditCardDao(): CreditCardDao

    companion object {
        @Volatile
        private var Instance: NoteDatabase? = null

        fun getDatabase(context: Context): NoteDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, NoteDatabase::class.java, "note_database")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { Instance = it }
            }
        }
    }
}