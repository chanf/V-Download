package com.vdown.app.ui

import android.app.Application
import android.net.Uri
import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vdown.app.cookie.AppDatabase
import com.vdown.app.cookie.CookieImportRepository
import com.vdown.app.cookie.CookieImportResult
import com.vdown.app.cookie.VideoCookieSourceReport
import com.vdown.app.download.VideoDownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class DownloadQueueItem(
    val rawText: String,
    val normalizedUrl: String
)

data class CookieImportUiState(
    val urlDraft: String = "",
    val queuedUrls: List<DownloadQueueItem> = emptyList(),
    val isImporting: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadingUrl: String? = null,
    val downloadMessage: String? = null,
    val downloadDiagnostics: String? = null,
    val totalCookies: Int = 0,
    val lastImportResult: CookieImportResult? = null,
    val storedVideoSourceReports: List<VideoCookieSourceReport> = emptyList(),
    val warningMessage: String? = null,
    val errorMessage: String? = null
)

class CookieImportViewModel(application: Application) : AndroidViewModel(application) {
    private val cookieDao = AppDatabase.getInstance(application).cookieDao()
    private val repository = CookieImportRepository(cookieDao)
    private val videoDownloadRepository = VideoDownloadRepository(cookieDao)

    var uiState by mutableStateOf(CookieImportUiState())
        private set

    init {
        viewModelScope.launch {
            refreshCookieStatus()
        }
    }

    fun onSharedUrlReceived(sharedUrl: String?) {
        val rawShared = sharedUrl?.trim().orEmpty()
        if (rawShared.isBlank()) return
        val normalized = normalizeUrl(rawShared) ?: return
        val displayText = rawShared.takeIf { it.length <= 240 } ?: normalized
        if (uiState.urlDraft.isBlank()) {
            uiState = uiState.copy(urlDraft = displayText)
        }
    }

    fun updateUrlDraft(value: String) {
        uiState = uiState.copy(urlDraft = value, errorMessage = null, downloadDiagnostics = null)
    }

    fun addDraftUrlToQueue() {
        val rawInput = uiState.urlDraft.trim()
        val normalized = normalizeUrl(rawInput)
        if (normalized == null) {
            uiState = uiState.copy(
                errorMessage = "请输入有效 URL",
                downloadDiagnostics = buildDownloadDiagnostics(
                    phase = "输入校验失败",
                    providedText = rawInput,
                    normalizedUrl = null,
                    hasStorageWritePermission = true,
                    error = IllegalArgumentException("未检测到可用的 URL")
                )
            )
            return
        }

        if (uiState.queuedUrls.any { it.normalizedUrl == normalized }) {
            uiState = uiState.copy(
                urlDraft = "",
                errorMessage = "该 URL 已在待下载列表中",
                downloadDiagnostics = buildDownloadDiagnostics(
                    phase = "加入队列失败",
                    providedText = rawInput,
                    normalizedUrl = normalized,
                    hasStorageWritePermission = true,
                    error = IllegalStateException("URL 去重命中")
                )
            )
            return
        }

        val queueItem = DownloadQueueItem(
            rawText = rawInput.ifBlank { normalized },
            normalizedUrl = normalized
        )
        uiState = uiState.copy(
            urlDraft = "",
            queuedUrls = listOf(queueItem) + uiState.queuedUrls,
            errorMessage = null,
            downloadDiagnostics = null
        )
    }

    fun startDownload(hasStorageWritePermission: Boolean) {
        if (uiState.isDownloading) return

        val queuedItem = uiState.queuedUrls.firstOrNull()
        val providedText = queuedItem?.rawText ?: uiState.urlDraft.trim()
        val candidate = queuedItem?.normalizedUrl ?: normalizeUrl(providedText)
        if (candidate == null) {
            uiState = uiState.copy(
                errorMessage = "请先输入可下载的 URL",
                downloadDiagnostics = buildDownloadDiagnostics(
                    phase = "下载前校验失败",
                    providedText = providedText,
                    normalizedUrl = null,
                    hasStorageWritePermission = hasStorageWritePermission,
                    error = IllegalArgumentException("未提取到有效 URL")
                )
            )
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(
                isDownloading = true,
                downloadProgress = 0,
                downloadingUrl = candidate,
                downloadMessage = null,
                downloadDiagnostics = buildDownloadDiagnostics(
                    phase = "开始下载",
                    providedText = providedText,
                    normalizedUrl = candidate,
                    hasStorageWritePermission = hasStorageWritePermission
                ),
                warningMessage = null,
                errorMessage = null
            )

            runCatching {
                videoDownloadRepository.downloadVideo(
                    context = getApplication(),
                    sourceUrl = candidate,
                    hasStorageWritePermission = hasStorageWritePermission
                ) { progress ->
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        uiState = uiState.copy(downloadProgress = progress.coerceIn(0, 100))
                    }
                }
            }.onSuccess { result ->
                val remainQueue = if (queuedItem != null && uiState.queuedUrls.firstOrNull() == queuedItem) {
                    uiState.queuedUrls.drop(1)
                } else {
                    uiState.queuedUrls
                }
                val sizeMb = result.bytesWritten.toDouble() / (1024.0 * 1024.0)
                uiState = uiState.copy(
                    isDownloading = false,
                    downloadProgress = 100,
                    downloadingUrl = null,
                    queuedUrls = remainQueue,
                    downloadMessage = "下载完成：${result.displayName}（${(sizeMb * 100).roundToInt() / 100.0} MB），已保存到相册 v-down。",
                    downloadDiagnostics = buildDownloadDiagnostics(
                        phase = "下载成功",
                        providedText = providedText,
                        normalizedUrl = candidate,
                        hasStorageWritePermission = hasStorageWritePermission,
                        extra = listOf(
                            "文件名 = ${result.displayName}",
                            "MIME = ${result.mimeType}",
                            "字节数 = ${result.bytesWritten}",
                            "输出 URI = ${result.outputUri}"
                        )
                    ),
                    errorMessage = null
                )
            }.onFailure { error ->
                val concise = error.message ?: "下载失败，请稍后重试。"
                uiState = uiState.copy(
                    isDownloading = false,
                    downloadingUrl = null,
                    downloadMessage = null,
                    errorMessage = concise,
                    downloadDiagnostics = buildDownloadDiagnostics(
                        phase = "下载失败",
                        providedText = providedText,
                        normalizedUrl = candidate,
                        hasStorageWritePermission = hasStorageWritePermission,
                        error = error
                    )
                )
            }
        }
    }

    fun importCookies(uri: Uri) {
        if (uiState.isImporting) return

        viewModelScope.launch {
            uiState = uiState.copy(
                isImporting = true,
                warningMessage = null,
                errorMessage = null
            )

            runCatching {
                repository.importFromUri(getApplication(), uri)
            }.onSuccess { result ->
                val warningMessage = buildCookieExpiryWarning(result)
                    val errorMessage = when {
                        result.videoSiteParsedCount == 0 ->
                        "未检测到支持的视频网站 Cookies（TikTok/YouTube/bilibili/抖音/Instagram/小红书）。"
                        result.importedCount == 0 && result.skippedExpiredLines > 0 ->
                        "导入失败：检测到视频网站 Cookie 已全部过期，请重新导出后再导入。"
                    result.importedCount == 0 ->
                        "未导入任何 Cookie，请检查 cookies.txt 格式。"
                    else -> null
                }
                uiState = uiState.copy(
                    isImporting = false,
                    lastImportResult = result,
                    totalCookies = result.totalStoredVideoCookieCount,
                    storedVideoSourceReports = result.storedVideoSourceReports,
                    warningMessage = warningMessage,
                    errorMessage = errorMessage
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    isImporting = false,
                    warningMessage = null,
                    errorMessage = error.message ?: "导入失败，请检查文件格式"
                )
            }
        }
    }

    private suspend fun refreshCookieStatus() {
        val total = withContext(Dispatchers.IO) {
            repository.countAllCookies()
        }
        val reports = withContext(Dispatchers.IO) {
            repository.loadStoredVideoSourceReports()
        }
        uiState = uiState.copy(
            totalCookies = total,
            storedVideoSourceReports = reports
        )
    }

    private fun normalizeUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null

        STRICT_HTTP_URL_PATTERN.find(text)?.value?.let { direct ->
            normalizeMatchedUrl(
                rawMatched = direct,
                requireSupportedHost = false
            )?.let { return it }
        }

        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val extracted = matcher.group() ?: continue
            normalizeMatchedUrl(
                rawMatched = extracted,
                requireSupportedHost = true
            )?.let { return it }
        }

        return null
    }

    private fun normalizeMatchedUrl(rawMatched: String, requireSupportedHost: Boolean): String? {
        val cleaned = trimTrailingUrlChars(rawMatched.trim())
        if (cleaned.isBlank()) return null

        val withScheme = if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            cleaned
        } else {
            "https://$cleaned"
        }

        val upgraded = forceHttpsForKnownVideoHosts(withScheme)
        val host = Uri.parse(upgraded).host?.lowercase()?.removePrefix(".") ?: return null
        if (requireSupportedHost && !isSupportedVideoHost(host)) {
            return null
        }
        return upgraded
    }

    private fun trimTrailingUrlChars(input: String): String {
        var cleaned = input
        while (cleaned.isNotEmpty() && cleaned.last() in trailingUrlCharsToTrim) {
            cleaned = cleaned.dropLast(1)
        }
        return cleaned
    }

    private fun forceHttpsForKnownVideoHosts(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val host = Uri.parse(url).host?.lowercase()?.removePrefix(".") ?: return url
        if (!isSupportedVideoHost(host)) return url
        return url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
    }

    private fun isSupportedVideoHost(host: String): Boolean {
        return supportedVideoHostSuffixes.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    private fun buildCookieExpiryWarning(result: CookieImportResult): String? {
        if (result.skippedExpiredLines <= 0) return null
        return "注意：检测到 ${result.skippedExpiredLines} 条视频站点 Cookie 已过期，已自动跳过。请重新导出最新 cookies.txt。"
    }

    private fun buildDownloadDiagnostics(
        phase: String,
        providedText: String,
        normalizedUrl: String?,
        hasStorageWritePermission: Boolean,
        error: Throwable? = null,
        extra: List<String> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val safeProvided = providedText.ifBlank { "(空)" }
        val safeNormalized = normalizedUrl ?: "(未提取)"
        val lines = mutableListOf<String>()
        lines += "【下载诊断日志】"
        lines += "阶段 = $phase"
        lines += "时间戳 = $now"
        lines += "原始输入文本 = $safeProvided"
        lines += "提取URL = $safeNormalized"
        lines += "写入权限标志 = $hasStorageWritePermission"
        lines += "队列长度 = ${uiState.queuedUrls.size}"

        if (extra.isNotEmpty()) {
            lines += "附加信息:"
            extra.forEach { lines += "- $it" }
        }

        if (error != null) {
            val root = findRootCause(error)
            lines += "异常类型 = ${error::class.java.name}"
            lines += "异常信息 = ${error.message ?: "(空)"}"
            lines += "根因类型 = ${root::class.java.name}"
            lines += "根因信息 = ${root.message ?: "(空)"}"
            val stack = error.stackTraceToString()
                .lineSequence()
                .take(24)
                .joinToString(separator = "\n")
            lines += "堆栈摘要(前24行):"
            lines += stack
        }

        return lines.joinToString(separator = "\n")
    }

    private fun findRootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private companion object {
        val STRICT_HTTP_URL_PATTERN = Regex("""https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""", RegexOption.IGNORE_CASE)
        val trailingUrlCharsToTrim = setOf(
            '，', '。', '！', '？', '；', '：',
            ',', '.', '!', '?', ';', ':',
            ')', ']', '}', '>', '"', '\''
        )
        val supportedVideoHostSuffixes = setOf(
            "douyin.com",
            "iesdouyin.com",
            "tiktok.com",
            "tiktokcdn.com",
            "tiktokv.com",
            "muscdn.com",
            "instagram.com",
            "cdninstagram.com",
            "fbcdn.net",
            "xiaohongshu.com",
            "xhscdn.com",
            "xhslink.com",
            "rednote.com",
            "youtube.com",
            "youtu.be",
            "googlevideo.com",
            "x.com",
            "twitter.com",
            "twimg.com",
            "bilibili.com",
            "b23.tv",
            "bilivideo.com",
            "hdslb.com"
        )
    }
}
