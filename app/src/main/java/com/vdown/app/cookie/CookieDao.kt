package com.vdown.app.cookie

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CookieDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cookies: List<CookieEntity>)

    @Query("SELECT COUNT(*) FROM cookies")
    suspend fun countAll(): Int

    @Query("SELECT * FROM cookies")
    suspend fun getAll(): List<CookieEntity>

    @Query("SELECT * FROM cookies WHERE expiresAtEpochSeconds IS NULL OR expiresAtEpochSeconds = 0 OR expiresAtEpochSeconds >= :nowEpochSeconds")
    suspend fun getValidCookies(nowEpochSeconds: Long): List<CookieEntity>

    @Query("DELETE FROM cookies WHERE expiresAtEpochSeconds IS NOT NULL AND expiresAtEpochSeconds > 0 AND expiresAtEpochSeconds < :nowEpochSeconds")
    suspend fun deleteExpired(nowEpochSeconds: Long): Int
}
