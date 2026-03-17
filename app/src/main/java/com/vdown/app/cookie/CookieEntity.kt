package com.vdown.app.cookie

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cookies",
    indices = [
        Index(value = ["domain", "path", "name"], unique = true),
        Index(value = ["domain"]),
        Index(value = ["expiresAtEpochSeconds"])
    ]
)
data class CookieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val includeSubDomains: Boolean,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAtEpochSeconds: Long?,
    val name: String,
    val value: String,
    val sourceFileName: String?,
    val importedAtEpochMillis: Long
)
