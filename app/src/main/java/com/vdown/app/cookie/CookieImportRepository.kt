package com.vdown.app.cookie

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CookieImportRepository(
    private val cookieDao: CookieDao,
    private val parser: NetscapeCookieParser = NetscapeCookieParser()
) {
    suspend fun importFromUri(context: Context, uri: Uri): CookieImportResult = withContext(Dispatchers.IO) {
        val sourceFileName = resolveDisplayName(context, uri) ?: "cookies.txt"
        val parseResult = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            parser.parse(reader)
        } ?: throw IllegalArgumentException("无法读取导入文件")

        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val importedAtEpochMillis = System.currentTimeMillis()

        val videoCandidates = parseResult.cookies.mapNotNull { parsed ->
            val source = VideoCookieSources.matchByDomain(parsed.domain) ?: return@mapNotNull null
            VideoCookieCandidate(parsed, source)
        }

        val availableBySource = mutableMapOf<String, Int>()
        val expiredBySource = mutableMapOf<String, Int>()

        val entities = videoCandidates.mapNotNull { candidate ->
            val parsed = candidate.parsedCookie
            val source = candidate.source
            val expires = parsed.expiresAtEpochSeconds.takeIf { it > 0 }
            val isExpired = expires != null && expires < nowEpochSeconds

            if (isExpired) {
                expiredBySource[source.id] = (expiredBySource[source.id] ?: 0) + 1
                return@mapNotNull null
            }

            availableBySource[source.id] = (availableBySource[source.id] ?: 0) + 1
            CookieEntity(
                domain = parsed.domain,
                includeSubDomains = parsed.includeSubDomains,
                path = parsed.path,
                secure = parsed.secure,
                httpOnly = parsed.httpOnly,
                expiresAtEpochSeconds = expires,
                name = parsed.name,
                value = parsed.value,
                sourceFileName = sourceFileName,
                importedAtEpochMillis = importedAtEpochMillis
            )
        }

        if (entities.isNotEmpty()) {
            cookieDao.upsertAll(entities)
        }

        cookieDao.deleteExpired(nowEpochSeconds)

        val importedVideoSourceReports = buildSourceReports(
            availableBySource = availableBySource,
            expiredBySource = expiredBySource
        )
        val storedVideoSourceReports = loadStoredVideoSourceReportsInternal()
        val totalStoredVideoCookieCount = storedVideoSourceReports.sumOf { it.availableCount }

        CookieImportResult(
            sourceFileName = sourceFileName,
            parsedCount = parseResult.cookies.size,
            videoSiteParsedCount = videoCandidates.size,
            importedCount = entities.size,
            skippedCommentLines = parseResult.skippedCommentLines,
            skippedInvalidLines = parseResult.skippedInvalidLines,
            skippedExpiredLines = expiredBySource.values.sum(),
            totalStoredCount = cookieDao.countAll(),
            totalStoredVideoCookieCount = totalStoredVideoCookieCount,
            importedVideoSourceReports = importedVideoSourceReports,
            storedVideoSourceReports = storedVideoSourceReports,
            importedAtEpochMillis = importedAtEpochMillis
        )
    }

    suspend fun countAllCookies(): Int = withContext(Dispatchers.IO) {
        loadStoredVideoSourceReportsInternal().sumOf { it.availableCount }
    }

    suspend fun loadStoredVideoSourceReports(): List<VideoCookieSourceReport> = withContext(Dispatchers.IO) {
        loadStoredVideoSourceReportsInternal()
    }

    private suspend fun loadStoredVideoSourceReportsInternal(): List<VideoCookieSourceReport> {
        val allCookies = cookieDao.getAll()
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val availableBySource = mutableMapOf<String, Int>()

        allCookies.forEach { cookie ->
            val source = VideoCookieSources.matchByDomain(cookie.domain) ?: return@forEach
            val expires = cookie.expiresAtEpochSeconds
            val isExpired = expires != null && expires > 0 && expires < nowEpochSeconds
            if (!isExpired) {
                availableBySource[source.id] = (availableBySource[source.id] ?: 0) + 1
            }
        }

        return buildSourceReports(
            availableBySource = availableBySource,
            expiredBySource = emptyMap()
        )
    }

    private fun buildSourceReports(
        availableBySource: Map<String, Int>,
        expiredBySource: Map<String, Int>
    ): List<VideoCookieSourceReport> {
        return VideoCookieSources.all.mapNotNull { source ->
            val availableCount = availableBySource[source.id] ?: 0
            val expiredCount = expiredBySource[source.id] ?: 0
            if (availableCount == 0 && expiredCount == 0) {
                null
            } else {
                VideoCookieSourceReport(
                    sourceId = source.id,
                    sourceName = source.displayName,
                    availableCount = availableCount,
                    expiredCount = expiredCount,
                    isUsable = availableCount > 0
                )
            }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else {
                null
            }
        }
    }
}

private data class VideoCookieCandidate(
    val parsedCookie: ParsedCookie,
    val source: VideoCookieSource
)
