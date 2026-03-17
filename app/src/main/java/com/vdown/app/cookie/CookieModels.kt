package com.vdown.app.cookie

data class ParsedCookie(
    val domain: String,
    val includeSubDomains: Boolean,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expiresAtEpochSeconds: Long,
    val name: String,
    val value: String
)

data class CookieParseResult(
    val cookies: List<ParsedCookie>,
    val skippedCommentLines: Int,
    val skippedInvalidLines: Int
)

data class CookieImportResult(
    val sourceFileName: String,
    val parsedCount: Int,
    val videoSiteParsedCount: Int,
    val importedCount: Int,
    val skippedCommentLines: Int,
    val skippedInvalidLines: Int,
    val skippedExpiredLines: Int,
    val totalStoredCount: Int,
    val totalStoredVideoCookieCount: Int,
    val importedVideoSourceReports: List<VideoCookieSourceReport>,
    val storedVideoSourceReports: List<VideoCookieSourceReport>,
    val importedAtEpochMillis: Long
)

data class VideoCookieSourceReport(
    val sourceId: String,
    val sourceName: String,
    val availableCount: Int,
    val expiredCount: Int,
    val isUsable: Boolean
)
