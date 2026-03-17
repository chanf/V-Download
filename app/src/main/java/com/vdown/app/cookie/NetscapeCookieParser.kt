package com.vdown.app.cookie

import java.io.BufferedReader

class NetscapeCookieParser {
    fun parse(reader: BufferedReader): CookieParseResult {
        val cookies = mutableListOf<ParsedCookie>()
        var skippedCommentLines = 0
        var skippedInvalidLines = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                return@forEachLine
            }

            val isHttpOnly = line.startsWith(HTTP_ONLY_PREFIX)
            val cookieLine = when {
                isHttpOnly -> line.removePrefix(HTTP_ONLY_PREFIX)
                line.startsWith("#") -> {
                    skippedCommentLines += 1
                    return@forEachLine
                }
                else -> line
            }

            val parts = cookieLine.split('\t', limit = FIELD_SIZE)
            if (parts.size != FIELD_SIZE) {
                skippedInvalidLines += 1
                return@forEachLine
            }

            val domain = parts[IDX_DOMAIN].trim().lowercase()
            val includeSubDomains = parseBoolean(parts[IDX_INCLUDE_SUBDOMAINS])
            val path = parts[IDX_PATH].ifBlank { "/" }
            val secure = parseBoolean(parts[IDX_SECURE])
            val expiresAtEpochSeconds = parts[IDX_EXPIRES].trim().toLongOrNull()
            val name = parts[IDX_NAME]
            val value = parts[IDX_VALUE]

            if (domain.isBlank() || name.isBlank() || includeSubDomains == null || secure == null || expiresAtEpochSeconds == null) {
                skippedInvalidLines += 1
                return@forEachLine
            }

            cookies += ParsedCookie(
                domain = domain,
                includeSubDomains = includeSubDomains,
                path = path,
                secure = secure,
                httpOnly = isHttpOnly,
                expiresAtEpochSeconds = expiresAtEpochSeconds,
                name = name,
                value = value
            )
        }

        return CookieParseResult(
            cookies = cookies,
            skippedCommentLines = skippedCommentLines,
            skippedInvalidLines = skippedInvalidLines
        )
    }

    private fun parseBoolean(raw: String): Boolean? {
        return when (raw.trim().uppercase()) {
            "TRUE" -> true
            "FALSE" -> false
            else -> null
        }
    }

    private companion object {
        const val HTTP_ONLY_PREFIX = "#HttpOnly_"
        const val FIELD_SIZE = 7

        const val IDX_DOMAIN = 0
        const val IDX_INCLUDE_SUBDOMAINS = 1
        const val IDX_PATH = 2
        const val IDX_SECURE = 3
        const val IDX_EXPIRES = 4
        const val IDX_NAME = 5
        const val IDX_VALUE = 6
    }
}
