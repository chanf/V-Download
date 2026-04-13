# 抖音视频下载原理与技术实现

## 1. 整体流程概览

抖音视频下载分为四个阶段：

```
用户输入 URL
    → 链接归一化与 ItemID 提取
    → 页面抓取 + 视频直链解析
    → 视频文件下载（支持分段并发）
    → 写入系统相册 DCIM/v-down
```

### 核心调用链

```
MainActivity 接收分享/粘贴 URL
  → CookieImportViewModel.downloadVideo()
    → VideoDownloadRepository.downloadVideo()
      → resolveSourceUrlForDownload()        # 路由到 resolveDouyinSourceUrl()
      → openDownloadConnection()             # 建立下载连接（带 Cookie 注入）
      → downloadToUri() / downloadInParallelToTempFile()  # 单线程/并发下载
      → 写入 MediaStore
```

---

## 2. 链接识别与归一化

### 2.1 抖音链接类型

抖音的分享链接通常有以下形式：

| 类型 | 示例 |
|---|---|
| 短链 | `https://v.douyin.com/iRNBho5M/` |
| 长链 | `https://www.douyin.com/video/7350xxxxx` |
| 分享页 | `https://m.douyin.com/share/video/7350xxxxx` |
| 图文/音频 | `https://www.douyin.com/note/7350xxxxx` |

### 2.2 平台识别

`isDouyinPageUrl()` 通过 URL host 判断是否为抖音链接：

```kotlin
private fun isDouyinPageUrl(url: URL): Boolean {
    val host = url.host.lowercase()
    val isDouyinHost = host == "douyin.com" ||
        host.endsWith(".douyin.com") ||
        host == "iesdouyin.com" ||
        host.endsWith(".iesdouyin.com")
    if (!isDouyinHost) return false
    return !url.path.lowercase().endsWith(".mp4")  // 排除已是直链的 URL
}
```

识别的域名包括：
- `douyin.com` / `*.douyin.com`
- `iesdouyin.com` / `*.iesdouyin.com`

### 2.3 ItemID 提取

抖音视频的唯一标识是 `ItemId`（纯数字，6-30 位）。通过以下正则从 URL 或页面内容中提取：

```kotlin
private val DOUYIN_ITEM_ID_PATTERNS = listOf(
    Regex("""(?:/video/|/share/video/)(\d{6,30})""", RegexOption.IGNORE_CASE),
    Regex("""(?:/xg/video/)(\d{6,30})""", RegexOption.IGNORE_CASE),
    Regex("""(?:item_id|itemId|aweme_id|modal_id)["=: ]+"?(\d{6,30})""", RegexOption.IGNORE_CASE)
)
```

对于短链，需要先跟随重定向获取最终 URL，再从中提取 ItemId。

---

## 3. 页面解析与视频直链提取

### 3.1 解析流程（`resolveDouyinSourceUrl`）

```kotlin
private suspend fun resolveDouyinSourceUrl(source: String): String {
    val candidates = linkedSetOf(source)
    var discoveredItemId = extractDouyinItemId(source)

    // 步骤1：短链重定向获取 ItemId
    if (discoveredItemId.isNullOrBlank()) {
        val redirectedUrl = resolveFinalRedirectUrl(
            pageUrl = source,
            userAgent = MOBILE_SAFARI_UA,
            referer = "https://www.douyin.com/"
        )
        discoveredItemId = redirectedUrl?.let(::extractDouyinItemId)
    }

    // 步骤2：构造候选页面 URL
    discoveredItemId?.let {
        candidates += "https://m.douyin.com/share/video/$it"
    }

    // 步骤3：依次抓取候选页面，提取视频直链
    candidates.forEach { pageUrl ->
        val page = fetchDouyinPage(pageUrl)

        // 图文/音频内容检测
        if (isLikelyDouyinAudioOnlyPage(page)) {
            throw IllegalStateException("该抖音链接是图文/音频内容（非视频）")
        }

        val resolved = extractDouyinVideoUrlFromPage(page)
        if (!resolved.isNullOrBlank()) return resolved

        // 页面中可能包含 ItemId
        if (discoveredItemId.isNullOrBlank()) {
            discoveredItemId = extractDouyinItemId(page)
        }
    }

    // 步骤4：回退到 iesdouyin API
    discoveredItemId?.let { itemId ->
        val apiUrl = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$itemId"
        val apiBody = fetchDouyinApi(apiUrl)
        val resolvedFromApi = extractDouyinVideoUrlFromPage(apiBody)
        if (!resolvedFromApi.isNullOrBlank()) return resolvedFromApi
    }

    throw IllegalStateException("抖音页面未解析到视频直链")
}
```

**关键设计**：
- **多候选页面策略**：短链、原始链接、移动端分享页三种 URL 依次尝试
- **API 回退**：页面解析失败时，使用 `iesdouyin.com` 的公开 API 作为兜底
- **图文检测**：`isLikelyDouyinAudioOnlyPage()` 通过 `aweme_type=2`、`/note/` 路径、`<audio>` 标记判断非视频内容，提前报错

### 3.2 请求伪装

```kotlin
private suspend fun fetchDouyinPage(pageUrl: String): String {
    return fetchWebPage(
        pageUrl = pageUrl,
        userAgent = MOBILE_SAFARI_UA,     // 伪装为 iPhone Safari
        referer = "https://www.douyin.com/"
    )
}
```

使用 Mobile Safari UA 抓取移动端页面（移动端页面结构更简单，视频信息更容易提取）。

### 3.3 视频直链提取（`extractDouyinVideoUrlFromPage`）

抖音页面中的视频 URL 可能出现在多个位置，以多种转义格式存在。提取策略是"穷举所有可能的模式"：

```kotlin
private fun extractDouyinVideoUrlFromPage(content: String): String? {
    val candidates = linkedSetOf<String>()

    // 1. 从 OG 视频标签提取（meta og:video）
    candidates += extractAllValidVideoUrls(content, INSTAGRAM_OG_VIDEO_PATTERNS, ::isLikelyDouyinVideoUrl)

    // 2. 从 JSON 字段提取（play_addr, download_addr 等）
    candidates += extractAllValidVideoUrls(content, DOUYIN_VIDEO_URL_PATTERNS, ::isLikelyDouyinVideoUrl)

    // 3. 对转义内容做二次提取（\\\" -> \"）
    val normalized = normalizeEscapedContent(content)
    if (normalized != content) {
        candidates += extractAllValidVideoUrls(normalized, INSTAGRAM_OG_VIDEO_PATTERNS, ::isLikelyDouyinVideoUrl)
        candidates += extractAllValidVideoUrls(normalized, DOUYIN_VIDEO_URL_PATTERNS, ::isLikelyDouyinVideoUrl)
    }

    // 4. 从 URL 列表块中提取
    extractFirstVideoUrlFromUrlListBlocks(normalized, ::isLikelyDouyinVideoUrl)?.let { candidates += it }

    // 5. 正则扫描所有 HTTP URL
    candidates += extractAllMatchingHttpUrls(normalized, ::isLikelyDouyinVideoUrl)

    return bestDouyinCandidate(candidates)
}
```

**正则模式**（`DOUYIN_VIDEO_URL_PATTERNS`）：

```kotlin
private val DOUYIN_VIDEO_URL_PATTERNS = listOf(
    // 转义 JSON 格式：\"play_addr\":\"https://...\"
    Regex("""\\\"(?:play_addr|playAddr|download_addr|downloadAddr|video_url)\\\"\s*:\s*\\\"(.*?)\\\""""),
    // 标准 JSON 格式："play_addr":"https://..."
    Regex(""""(?:play_addr|playAddr|download_addr|downloadAddr|video_url)"\s*:\s*"([^"]+)""""),
    // 单引号格式
    Regex("""['"](?:play_addr|playAddr|download_addr|downloadAddr|video_url)['"]\s*:\s*['"]([^'"]+)['"]""")
)
```

### 3.4 候选 URL 过滤（`isLikelyDouyinVideoUrl`）

并非所有匹配正则的 URL 都是有效视频链接。通过以下条件过滤：

```kotlin
private fun isLikelyDouyinVideoUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    if (isLikelyAudioOnlyUrl(url)) return false       // 排除音频 URL
    if (lower.contains("/share/video/")) return false  // 排除分享页 URL
    if (lower.contains("/aweme/v1/play/") || lower.contains("playwm")) return true
    if (lower.contains(".mp4") && (lower.contains("douyin") || lower.contains("aweme") || lower.contains("vod"))) return true
    val host = URL(url).host.lowercase()
    if (host.contains("douyinvod")) return true       // CDN 域名
    if (host.contains("amemv") || host.contains("snssdk")) {
        return lower.contains("video") || lower.contains("play") || lower.contains("vod")
    }
    return false
}
```

### 3.5 候选排序与去水印（`normalizeDouyinCandidates` + `bestDouyinCandidate`）

提取到多个候选 URL 后，系统会：

1. **生成无水印变体**：
   - `playwm` → `play`（去掉水印播放标记）
   - `watermark=1` → `watermark=0`

2. **生成 line 参数变体**：抖音 CDN 对不同 `line` 参数有可达性差异，生成 `line=0..8` 的变体：
   ```kotlin
   private fun buildDouyinLineVariants(url: String): List<String> {
       if (!(lower.contains("/aweme/v1/play/"))) return emptyList()
       val variants = linkedSetOf<String>()
       for (line in 1..8) {
           variants += withUpdatedQueryParameter(url, "line", line.toString())
       }
       return variants.toList()
   }
   ```

3. **按质量排序**：优先选择无水印、非 `line=0`、高质量（720p+）的 URL：
   ```kotlin
   private fun douyinWatermarkPenalty(url: String): Int {
       var penalty = commonWatermarkPenalty(url)  // 通用水印检测
       if (lower.contains("playwm")) penalty += 2  // 带水印播放
       if (extractQueryParameterValue(url, "line") == "0") penalty += 1  // line=0 受限
       return penalty
   }
   ```

排序策略：`watermarkPenalty 升序` → `videoQualityScore 降序`，选出最优候选。

---

## 4. Cookie 处理机制

### 4.1 为什么需要 Cookie

抖音的很多视频需要登录态才能访问：
- 未登录用户可能看到风控页（返回 HTML 而非视频流）
- 部分视频仅对登录用户可见
- 频繁请求可能触发验证码/风控

Cookie 的作用是让请求"看起来像已登录的浏览器"。

### 4.2 Cookie 导入流程

```
用户从 Chrome 导出 cookies.txt（Netscape 格式）
  → NetscapeCookieParser 解析每行（7 个 Tab 分隔字段）
  → VideoCookieSources.matchByDomain() 匹配抖音相关域名
  → 过滤过期 Cookie
  → CookieDao.upsertAll() 写入 Room 数据库
```

#### Netscape 格式

```
#HttpOnly_.douyin.com	TRUE	/	TRUE	1715673600	sessionid	a1b2c3d4...
.douyin.com	TRUE	/	FALSE	1715673600	ttwid	x1y2z3...
```

每行 7 个字段（Tab 分隔）：
1. **domain**：`.douyin.com`（前缀点表示包含子域名）
2. **includeSubDomains**：`TRUE`/`FALSE`
3. **path**：`/`
4. **secure**：`TRUE`（仅 HTTPS）
5. **expires**：Unix 时间戳（秒）
6. **name**：Cookie 名称
7. **value**：Cookie 值

#### 域名匹配（`VideoCookieSources`）

抖音相关的域名后缀定义：

```kotlin
VideoCookieSource(
    id = "douyin",
    displayName = "抖音",
    domainSuffixes = listOf("douyin.com", "iesdouyin.com", "douyinvod.com")
)
```

匹配逻辑：域名精确匹配或为已知后缀的子域名。

### 4.3 Room 数据库结构

```kotlin
@Entity(
    tableName = "cookies",
    indices = [
        Index(value = ["domain", "path", "name"], unique = true),  // 唯一约束
        Index(value = ["domain"]),
        Index(value = ["expiresAtEpochSeconds"])
    ]
)
data class CookieEntity(
    val id: Long = 0,               // 自增主键
    val domain: String,              // 域名（小写，无前缀点）
    val includeSubDomains: Boolean,  // 是否包含子域名
    val path: String,                // 路径
    val secure: Boolean,             // 是否仅 HTTPS
    val httpOnly: Boolean,           // 是否 HttpOnly
    val expiresAtEpochSeconds: Long?, // 过期时间（Unix 秒）
    val name: String,                // Cookie 名称
    val value: String,               // Cookie 值
    val sourceFileName: String?,     // 来源文件名
    val importedAtEpochMillis: Long  // 导入时间
)
```

**Upsert 策略**：同一 `(domain, path, name)` 的 Cookie 会被新值覆盖，保证多次导入不产生重复。

### 4.4 请求时 Cookie 注入

每次发起 HTTP 请求时，`buildCookieHeader()` 从数据库查询匹配的 Cookie：

```kotlin
private suspend fun buildCookieHeader(url: URL): String {
    // 1. 查询所有未过期 Cookie
    val allCookies = cookieDao.getValidCookies(System.currentTimeMillis() / 1000)
    val host = url.host.lowercase()
    val path = url.path.ifBlank { "/" }
    val isHttps = url.protocol.equals("https", ignoreCase = true)

    // 2. 按 domain + path + secure 规则过滤
    val matched = allCookies
        .filter { cookie ->
            matchDomain(host, cookie) &&      // 域名匹配
                matchPath(path, cookie.path) &&   // 路径匹配
                (!cookie.secure || isHttps)       // secure Cookie 仅用于 HTTPS
        }
        .sortedByDescending { it.path.length }   // 更具体的路径优先

    // 3. 拼接为 Cookie 请求头
    return matched.joinToString(separator = "; ") { "${it.name}=${it.value}" }
}
```

**域名匹配规则**（`matchDomain`）：
- 如果 `includeSubDomains = true`：host 精确匹配或为子域名（`host == domain || host.endsWith(".$domain")`）
- 如果 `includeSubDomains = false`：host 必须精确匹配

**路径匹配规则**（`matchPath`）：请求路径以 Cookie 路径为前缀。

### 4.5 运行时 Cookie 捕获

不仅用户手动导入的 Cookie 会生效，系统还会自动捕获 HTTP 响应中的 `Set-Cookie` 头并持久化：

```kotlin
private suspend fun persistResponseCookies(connection: HttpURLConnection, requestUrl: URL) {
    // 1. 收集所有 Set-Cookie 响应头
    val headerValues = connection.headerFields
        .filter { (key, _) -> key.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { (_, values) -> values.orEmpty() }

    // 2. 逐条解析并验证域名
    for (header in headerValues) {
        val parsedCookies = HttpCookie.parse(header)
        for (cookie in parsedCookies) {
            val domain = cookie.domain ?: requestHost
            // 验证：Cookie 域名必须匹配请求域名
            if (!domainMatches) continue

            // 3. 计算过期时间
            val expiresAtEpochSeconds = when {
                cookie.maxAge == 0L -> nowEpochSeconds - 1  // 已删除
                cookie.maxAge > 0L -> nowEpochSeconds + cookie.maxAge
                else -> null  // Session Cookie
            }

            // 4. 写入数据库
            entities += CookieEntity(...)
        }
    }

    cookieDao.upsertAll(entities)
    cookieDao.deleteExpired(nowEpochSeconds)  // 顺便清理过期 Cookie
}
```

这个机制确保了：
- 服务端设置的 Session Cookie 被自动保存
- 后续请求能携带正确的会话信息
- 不需要用户反复导入 Cookie

### 4.6 Cookie 在下载流程中的应用位置

```
openDownloadConnection()
  ├── 每次建立连接时调用 buildCookieHeader(url) → 设置 Cookie 请求头
  └── 收到响应后调用 persistResponseCookies() → 保存 Set-Cookie

fetchDouyinPage() / fetchDouyinApi()
  ├── 内部调用 fetchWebPage() → 同样注入 Cookie
  └── 同样捕获响应 Cookie

probeRangeSupport()
  └── 探测 Range 支持时也会注入 Cookie
```

即：**整个下载流程的每一个 HTTP 请求都会自动注入 Cookie，并自动捕获响应中的新 Cookie**。

---

## 5. 视频下载执行

### 5.1 连接建立

```kotlin
private suspend fun openDownloadConnection(sourceUrl: String): DownloadOpenResult {
    val connection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false  // 手动处理重定向
        setRequestProperty("User-Agent", ANDROID_CHROME_UA)
        setRequestProperty("Accept-Encoding", "identity")  // 禁用压缩
        applySourceSpecificRequestHeaders(this, url)  // 添加 Referer
    }
    // 注入 Cookie
    connection.setRequestProperty("Cookie", buildCookieHeader(url))

    // 手动跟随重定向（最多 8 次），每次都注入 Cookie 并捕获新 Cookie
    repeat(MAX_REDIRECT_FOLLOWS) {
        persistResponseCookies(connection, url)
        // ... 处理 3xx 重定向 ...
    }
}
```

### 5.2 分段并发下载

文件大于 4MB 且服务器支持 Range 时，自动启用分段并发下载：

- **最小分片数**：2
- **最大分片数**：6
- **目标分片大小**：2MB

每段独立下载到临时文件，最后合并。

### 5.3 文件保存

- **Android 10+**：通过 `MediaStore` 写入 `DCIM/v-down`（Scoped Storage）
- **Android 9 及以下**：直接写入外部存储目录（需 `WRITE_EXTERNAL_STORAGE` 权限）

### 5.4 完整性校验

```kotlin
// 字节完整性
if (contentLength > 0 && bytesWritten != contentLength) {
    throw IllegalStateException("下载不完整：期望 $contentLength 字节，实际 $bytesWritten 字节")
}

// MIME 类型校验
if (mimeType.startsWith("text/html")) {
    throw IllegalStateException("返回 text/html，可能是鉴权页/风控页")
}

// 零字节校验
if (bytesWritten == 0L) {
    throw IllegalStateException("视频流为空（0 字节），可能链接已过期或鉴权失败")
}
```

---

## 6. 关键配置常量

| 常量 | 值 | 说明 |
|---|---|---|
| `DOWNLOAD_RELATIVE_PATH` | `DCIM/v-down` | 保存目录 |
| `BUFFER_SIZE` | 8KB | 下载缓冲区 |
| `MAX_REDIRECT_FOLLOWS` | 8 | 最大重定向次数 |
| `PARALLEL_DOWNLOAD_MIN_BYTES` | 4MB | 启用并发下载的最小文件大小 |
| `PARALLEL_DOWNLOAD_TARGET_SEGMENT_BYTES` | 2MB | 目标分片大小 |
| `PARALLEL_DOWNLOAD_MAX_SEGMENTS` | 6 | 最大并发数 |
| `MOBILE_SAFARI_UA` | iPhone Safari 17.0 | 抖音页面抓取 UA |

---

## 7. 抖音特有的技术难点与解决方案

| 难点 | 解决方案 |
|---|---|
| 短链需要重定向才能获取 ItemId | `resolveFinalRedirectUrl()` 手动跟随重定向 |
| 页面 JSON 中 URL 多层转义 | `normalizeEscapedContent()` 统一反转义后二次提取 |
| 带水印/无水印 URL 并存 | `normalizeDouyinCandidates()` 生成无水印变体，`douyinWatermarkPenalty()` 排序优先选择无水印 |
| CDN `line` 参数可达性差异 | `buildDouyinLineVariants()` 生成 `line=0..8` 变体 |
| 图文/音频内容误判 | `isLikelyDouyinAudioOnlyPage()` 检测 `aweme_type=2`、`/note/` 等标记 |
| 未登录返回风控页（HTML） | MIME 校验 + `resolveRetrySourceUrlForHtmlResponse()` 重试 |
| Cookie 过期导致下载失败 | 提示用户重新导入 Cookie；运行时自动捕获新 Cookie |
| 下载不完整 | 字节校验 + MIME 校验 + 失败时删除残留文件 |
