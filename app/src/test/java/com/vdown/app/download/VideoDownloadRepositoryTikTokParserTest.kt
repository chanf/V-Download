package com.vdown.app.download

import com.vdown.app.cookie.CookieDao
import com.vdown.app.cookie.CookieEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDownloadRepositoryTikTokParserTest {

    @Test
    fun `should extract tiktok video id from share_item_id field`() {
        val repository = VideoDownloadRepository(EmptyCookieDao())
        val text =
            "https://www.tiktok.com/?share_item_id=7616741161406958855&u_code=0"

        val itemId = invokeExtractTikTokVideoId(repository, text)
        assertEquals("7616741161406958855", itemId)
    }

    @Test
    fun `should parse restricted status from universal data`() {
        val repository = VideoDownloadRepository(EmptyCookieDao())
        val html = """
            <html>
              <body>
                <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
                  {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"statusCode":10204,"statusMsg":"author_status,status_reviewing"}}}
                </script>
              </body>
            </html>
        """.trimIndent()

        val restriction = invokeExtractTikTokRestrictionInfo(repository, html)
        assertNotNull("应识别 TikTok 受限状态", restriction)
        assertEquals(10204, extractRestrictionFieldAsInt(restriction, "statusCode"))
        assertEquals(
            "author_status,status_reviewing",
            extractRestrictionFieldAsString(restriction, "statusMsg")
        )
    }

    @Test
    fun `should pick target aweme from feed api body only`() {
        val repository = VideoDownloadRepository(EmptyCookieDao())
        val body = """
            {
              "status_code": 0,
              "aweme_list": [
                {
                  "aweme_id":"111",
                  "video":{"playAddr":"https://v16.tiktokcdn.com/video/tos/play-one.mp4"}
                },
                {
                  "aweme_id":"222",
                  "video":{"play_addr":"https://v16.tiktokcdn.com/video/tos/play-two.mp4"}
                }
              ]
            }
        """.trimIndent()

        val resolved = invokeExtractTikTokVideoUrlFromFeedBody(repository, body, "222")
        assertNotNull("应定位到目标 aweme 对应视频直链", resolved)
        assertTrue(
            "应返回目标 aweme 的视频地址",
            resolved!!.contains("play-two.mp4")
        )
    }

    private fun invokeExtractTikTokVideoId(repository: VideoDownloadRepository, text: String): String? {
        val method = VideoDownloadRepository::class.java.getDeclaredMethod(
            "extractTikTokVideoId",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(repository, text) as? String
    }

    private fun invokeExtractTikTokRestrictionInfo(repository: VideoDownloadRepository, content: String): Any? {
        val method = VideoDownloadRepository::class.java.getDeclaredMethod(
            "extractTikTokRestrictionInfo",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(repository, content)
    }

    private fun invokeExtractTikTokVideoUrlFromFeedBody(
        repository: VideoDownloadRepository,
        body: String,
        itemId: String
    ): String? {
        val method = VideoDownloadRepository::class.java.getDeclaredMethod(
            "extractTikTokVideoUrlFromFeedBody",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(repository, body, itemId) as? String
    }

    private fun extractRestrictionFieldAsInt(restriction: Any?, fieldName: String): Int {
        requireNotNull(restriction)
        val field = restriction::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(restriction) as Int
    }

    private fun extractRestrictionFieldAsString(restriction: Any?, fieldName: String): String? {
        requireNotNull(restriction)
        val field = restriction::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(restriction) as? String
    }

    private class EmptyCookieDao : CookieDao {
        override suspend fun upsertAll(cookies: List<CookieEntity>) = Unit

        override suspend fun countAll(): Int = 0

        override suspend fun getAll(): List<CookieEntity> = emptyList()

        override suspend fun getValidCookies(nowEpochSeconds: Long): List<CookieEntity> = emptyList()

        override suspend fun deleteExpired(nowEpochSeconds: Long): Int = 0
    }
}
