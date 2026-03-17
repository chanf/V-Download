package com.vdown.app.cookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCookieSourcesTest {
    @Test
    fun `should match known video source domains`() {
        assertEquals("TikTok", VideoCookieSources.matchByDomain("www.tiktok.com")?.displayName)
        assertEquals("YouTube", VideoCookieSources.matchByDomain(".youtube.com")?.displayName)
        assertEquals("bilibili", VideoCookieSources.matchByDomain("api.bilibili.com")?.displayName)
        assertEquals("抖音", VideoCookieSources.matchByDomain("open.douyin.com")?.displayName)
        assertEquals("Instagram", VideoCookieSources.matchByDomain("scontent.cdninstagram.com")?.displayName)
        assertEquals("小红书", VideoCookieSources.matchByDomain("www.xiaohongshu.com")?.displayName)
    }

    @Test
    fun `should return null for non-video domains`() {
        assertNull(VideoCookieSources.matchByDomain("cloudflare.com"))
        assertNull(VideoCookieSources.matchByDomain("example.org"))
    }
}
