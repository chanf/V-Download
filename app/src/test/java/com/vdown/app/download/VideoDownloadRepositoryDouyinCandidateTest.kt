package com.vdown.app.download

import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDownloadRepositoryDouyinCandidateTest {

    @Test
    fun `should expand douyin play url with line variants and prefer non zero line`() {
        val repository = VideoDownloadRepository(EmptyCookieDao())
        val source =
            "https://aweme.snssdk.com/aweme/v1/playwm/?line=0&logo_name=aweme_diversion_search&ratio=720p&video_id=v2700fgi0000d6v394fog65ufl4s6g4g"

        val normalized = invokeNormalizeDouyinCandidates(repository, listOf(source))
        assertTrue(
            "应生成 aweme play 无水印线路",
            normalized.any { it.contains("/aweme/v1/play/") }
        )
        assertTrue(
            "应生成 line=1 线路",
            normalized.any { it.contains("line=1") }
        )

        val best = invokeBestDouyinCandidate(repository, listOf(source))
        assertNotNull("应选出可用的抖音候选直链", best)
        assertFalse("应优先避开 line=0 线路", best!!.contains("line=0"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeNormalizeDouyinCandidates(
        repository: VideoDownloadRepository,
        candidates: Collection<String>
    ): List<String> {
        val method = VideoDownloadRepository::class.java.getDeclaredMethod(
            "normalizeDouyinCandidates",
            Collection::class.java
        )
        method.isAccessible = true
        return method.invoke(repository, candidates) as List<String>
    }

    private fun invokeBestDouyinCandidate(
        repository: VideoDownloadRepository,
        candidates: Collection<String>
    ): String? {
        val method = VideoDownloadRepository::class.java.getDeclaredMethod(
            "bestDouyinCandidate",
            Collection::class.java
        )
        method.isAccessible = true
        return method.invoke(repository, candidates) as? String
    }

    private class EmptyCookieDao : CookieDao {
        override suspend fun upsertAll(cookies: List<CookieEntity>) = Unit

        override suspend fun countAll(): Int = 0

        override suspend fun getAll(): List<CookieEntity> = emptyList()

        override suspend fun getValidCookies(nowEpochSeconds: Long): List<CookieEntity> = emptyList()

        override suspend fun deleteExpired(nowEpochSeconds: Long): Int = 0
    }
}

