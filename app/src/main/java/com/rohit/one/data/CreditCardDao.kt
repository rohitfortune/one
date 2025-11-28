package com.rohit.one.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CreditCardDao {

    @Query("SELECT * FROM creditcard ORDER BY createdAt DESC")
    fun getAllCards(): Flow<List<CreditCard>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CreditCard)

    @Update
    suspend fun updateCard(card: CreditCard)

    @Delete
    suspend fun deleteCard(card: CreditCard)
}

