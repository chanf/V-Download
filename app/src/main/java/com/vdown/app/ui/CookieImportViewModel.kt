package com.vdown.app.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vdown.app.asr.AsrConfigRepository
import com.vdown.app.asr.AsrLanguageOption
import com.vdown.app.asr.AsrProviderConfig
import com.vdown.app.asr.AsrProviderType
import com.vdown.app.asr.AsrTranscriptionRepository
import com.vdown.app.asr.defaultAsrBaseUrlFor
import com.vdown.app.asr.defaultAsrModelFor
import com.vdown.app.cookie.AppDatabase
import com.vdown.app.cookie.CookieImportRepository
import com.vdown.app.cookie.CookieImportResult
import com.vdown.app.cookie.VideoCookieSourceReport
import com.vdown.app.dedup.VideoDedupRepository
import com.vdown.app.dedup.VideoDedupRequest
import com.vdown.app.dedup.DedupEndingType
import com.vdown.app.dedup.DedupFeatureConfig
import com.vdown.app.dedup.DedupFeatureConfigRepository
import com.vdown.app.dedup.DedupIntroCoverMode
import com.vdown.app.download.VideoDownloadRepository
import com.vdown.app.llm.LlmConfigRepository
import com.vdown.app.llm.LlmProviderConfig
import com.vdown.app.llm.LlmProviderType
import com.vdown.app.llm.LlmRewriteRepository
import com.vdown.app.llm.defaultBaseUrlFor
import com.vdown.app.llm.defaultLlmSystemPrompt
import com.vdown.app.llm.defaultModelFor
import com.vdown.app.transcript.VideoTranscriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private data class TranscriptAttemptResult(
    val transcriptText: String,
    val route: String,
    val providerName: String? = null,
    val notes: List<String> = emptyList()
)

private data class OverlayCoverValidation(
    val isPngExtension: Boolean,
    val hasAlphaChannel: Boolean?,
    val hasTransparentPixels: Boolean?,
    val hasVisiblePixels: Boolean?,
    val decodeError: String? = null
) {
    val warningMessage: String?
        get() = when {
            !decodeError.isNullOrBlank() ->
                "覆盖模式素材检测失败（$decodeError），将继续执行但可能遮挡底层视频。"
            !isPngExtension ->
                "覆盖模式建议使用带透明区域的 PNG。当前文件扩展名不是 .png，可能整帧遮挡底层视频。"
            hasAlphaChannel == false ->
                "覆盖模式建议使用透明 PNG。当前图片不含 Alpha 通道，可能整帧遮挡底层视频。"
            hasTransparentPixels == false ->
                "覆盖模式建议使用透明 PNG。当前图片未检测到透明像素，可能整帧遮挡底层视频。"
            hasVisiblePixels == false ->
                "覆盖模式素材检测到“全透明图”，没有可见覆盖内容，画面可能看不到任何效果。"
            else -> null
        }

    fun diagnosticsLines(): List<String> {
        val alphaText = when (hasAlphaChannel) {
            true -> "是"
            false -> "否"
            null -> "未知"
        }
        val transparentText = when (hasTransparentPixels) {
            true -> "是"
            false -> "否"
            null -> "未知"
        }
        val visibleText = when (hasVisiblePixels) {
            true -> "是"
            false -> "否"
            null -> "未知"
        }
        val conclusion = warningMessage ?: "通过（建议项满足）"
        return listOf(
            "覆盖素材扩展名PNG = ${if (isPngExtension) "是" else "否"}",
            "覆盖素材Alpha通道 = $alphaText",
            "覆盖素材存在透明像素 = $transparentText",
            "覆盖素材存在可见像素 = $visibleText",
            "覆盖素材校验结论 = $conclusion"
        )
    }
}

enum class DedupPresetTemplate(
    val title: String,
    val speedPercent: Int,
    val trimStartMs: Int,
    val trimEndMs: Int,
    val ptsJitterMs: Int,
    val randomTrimJitterMs: Int,
    val shuffleTrackOrder: Boolean
) {
    NONE(
        title = "无",
        speedPercent = 100,
        trimStartMs = 0,
        trimEndMs = 0,
        ptsJitterMs = 0,
        randomTrimJitterMs = 0,
        shuffleTrackOrder = false
    ),
    LIGHT(
        title = "轻度",
        speedPercent = 98,
        trimStartMs = 80,
        trimEndMs = 100,
        ptsJitterMs = 8,
        randomTrimJitterMs = 20,
        shuffleTrackOrder = false
    ),
    BALANCED(
        title = "均衡",
        speedPercent = 97,
        trimStartMs = 120,
        trimEndMs = 140,
        ptsJitterMs = 16,
        randomTrimJitterMs = 40,
        shuffleTrackOrder = true
    ),
    STRONG(
        title = "强力",
        speedPercent = 96,
        trimStartMs = 180,
        trimEndMs = 220,
        ptsJitterMs = 24,
        randomTrimJitterMs = 60,
        shuffleTrackOrder = true
    );

    companion object {
        fun fromName(name: String?): DedupPresetTemplate {
            return entries.firstOrNull { it.name == name } ?: LIGHT
        }
    }
}

data class CookieImportUiState(
    val urlDraft: String = "",
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
    val errorMessage: String? = null,
    val selectedTranscriptVideoUri: Uri? = null,
    val selectedTranscriptVideoName: String? = null,
    val selectedTranscriptVideoSource: String? = null,
    val lastDownloadedVideoUri: Uri? = null,
    val lastDownloadedVideoName: String? = null,
    val selectedDedupVideoUri: Uri? = null,
    val selectedDedupVideoName: String? = null,
    val selectedDedupVideoSource: String? = null,
    val dedupPresetName: String = DedupPresetTemplate.LIGHT.name,
    val dedupSpeedPercentDraft: String = "98",
    val dedupTrimStartMsDraft: String = "80",
    val dedupTrimEndMsDraft: String = "100",
    val dedupPtsJitterMsDraft: String = "8",
    val dedupRandomTrimJitterMsDraft: String = "20",
    val dedupSeedDraft: String = "",
    val dedupCoverImageUri: Uri? = null,
    val dedupCoverImageName: String? = null,
    val dedupCoverOverlayWarning: String? = null,
    val dedupIntroCoverMode: DedupIntroCoverMode = DedupIntroCoverMode.NONE,
    val dedupIntroFrameCountDraft: String = "12",
    val dedupEndingType: DedupEndingType = DedupEndingType.NONE,
    val dedupEndingMediaUri: Uri? = null,
    val dedupEndingMediaName: String? = null,
    val dedupEndingImageDurationMsDraft: String = "1200",
    val dedupOutputPrefixDraft: String = "dedup",
    val dedupRandomSuffixEnabled: Boolean = true,
    val dedupShuffleTrackOrderEnabled: Boolean = false,
    val isDedupProcessing: Boolean = false,
    val dedupProgress: Int = 0,
    val dedupMessage: String? = null,
    val dedupErrorMessage: String? = null,
    val dedupDiagnostics: String? = null,
    val lastDedupVideoUri: Uri? = null,
    val lastDedupVideoName: String? = null,
    val isAudioControlVisible: Boolean = false,
    val isAudioPreparing: Boolean = false,
    val isAudioPlaying: Boolean = false,
    val isAudioPaused: Boolean = false,
    val audioStatusMessage: String? = null,
    val audioErrorMessage: String? = null,
    val isExportingTranscript: Boolean = false,
    val transcriptMessage: String? = null,
    val transcriptText: String? = null,
    val transcriptAudioPath: String? = null,
    val transcriptErrorMessage: String? = null,
    val transcriptDiagnostics: String? = null,
    val isAiRewriting: Boolean = false,
    val aiRewrittenText: String? = null,
    val aiRewriteErrorMessage: String? = null,
    val aiRewriteDiagnostics: String? = null,
    val llmProviders: List<LlmProviderConfig> = emptyList(),
    val selectedLlmProviderId: String? = null,
    val llmStatusMessage: String? = null,
    val llmErrorMessage: String? = null,
    val asrProviders: List<AsrProviderConfig> = emptyList(),
    val selectedAsrProviderId: String? = null,
    val asrStatusMessage: String? = null,
    val asrErrorMessage: String? = null
)

private const val DOWNLOAD_UI_LOG_TAG = "VDownDownloadUi"
private const val DEDUP_UI_LOG_TAG = "VDownDedupUi"

class CookieImportViewModel(application: Application) : AndroidViewModel(application) {
    private val cookieDao = AppDatabase.getInstance(application).cookieDao()
    private val repository = CookieImportRepository(cookieDao)
    private val videoDownloadRepository = VideoDownloadRepository(cookieDao)
    private val videoDedupRepository = VideoDedupRepository()
    private val dedupFeatureConfigRepository = DedupFeatureConfigRepository(application)
    private val transcriptRepository = VideoTranscriptRepository()
    private val llmConfigRepository = LlmConfigRepository(application)
    private val llmRewriteRepository = LlmRewriteRepository()
    private val asrConfigRepository = AsrConfigRepository(application)
    private val asrTranscriptionRepository = AsrTranscriptionRepository()
    private var audioPlayer: MediaPlayer? = null
    private var audioPlayerSourceUri: Uri? = null

    var uiState by mutableStateOf(CookieImportUiState())
        private set

    init {
        viewModelScope.launch {
            refreshCookieStatus()
            loadDedupFeatureConfig()
            refreshLlmProviderState()
            refreshAsrProviderState()
        }
    }

    fun onSharedUrlReceived(sharedUrl: String?) {
        val rawShared = sharedUrl?.trim().orEmpty()
        if (rawShared.isBlank()) return
        val normalized = normalizeUrl(rawShared) ?: return
        uiState = uiState.copy(
            urlDraft = normalized,
            errorMessage = null,
            downloadDiagnostics = null
        )
    }

    fun updateUrlDraft(value: String) {
        uiState = uiState.copy(urlDraft = value, errorMessage = null, downloadDiagnostics = null)
    }

    fun pasteAndDownloadFromClipboard(clipboardText: String, hasStorageWritePermission: Boolean) {
        val pastedText = clipboardText.trim()
        uiState = uiState.copy(
            urlDraft = "",
            errorMessage = null,
            downloadDiagnostics = null
        )

        val normalized = normalizeUrl(pastedText)
        if (normalized == null) {
            uiState = uiState.copy(
                errorMessage = "剪贴板中未检测到可下载的 URL",
                downloadDiagnostics = buildDownloadDiagnostics(
                    phase = "粘贴下载失败",
                    providedText = pastedText,
                    normalizedUrl = null,
                    hasStorageWritePermission = hasStorageWritePermission,
                    error = IllegalArgumentException("剪贴板内容未提取到有效 URL")
                )
            )
            return
        }

        uiState = uiState.copy(
            urlDraft = normalized,
            errorMessage = null,
            downloadDiagnostics = null
        )
        startDownload(hasStorageWritePermission)
    }

    fun startDownload(hasStorageWritePermission: Boolean) {
        if (uiState.isDownloading) return

        val providedText = uiState.urlDraft.trim()
        val candidate = normalizeUrl(providedText)
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
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(downloadProgress = progress.coerceIn(0, 100))
                    }
                }
            }.onSuccess { result ->
                val previousState = uiState
                val sizeMb = result.bytesWritten.toDouble() / (1024.0 * 1024.0)
                val transcriptDefaultUri = previousState.selectedTranscriptVideoUri ?: result.outputUri
                val transcriptDefaultName = previousState.selectedTranscriptVideoName ?: result.displayName
                val transcriptDefaultSource = previousState.selectedTranscriptVideoSource ?: "视频下载最近记录"

                uiState = previousState.copy(
                    isDownloading = false,
                    downloadProgress = 100,
                    downloadingUrl = null,
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
                            "输出 URI = ${result.outputUri}",
                            "解析直链 = ${result.resolvedVideoUrl}",
                            "最终请求URL = ${result.finalRequestUrl}",
                            "水印评分 = ${result.watermarkPenalty?.toString() ?: "N/A"}"
                        )
                    ),
                    errorMessage = null,
                    lastDownloadedVideoUri = result.outputUri,
                    lastDownloadedVideoName = result.displayName,
                    selectedTranscriptVideoUri = transcriptDefaultUri,
                    selectedTranscriptVideoName = transcriptDefaultName,
                    selectedTranscriptVideoSource = transcriptDefaultSource,
                    transcriptMessage = previousState.transcriptMessage ?: "已默认选中最近下载视频，可在文案提取页改选。"
                )
                Log.i(
                    DOWNLOAD_UI_LOG_TAG,
                    "download success url=$candidate output=${result.outputUri} bytes=${result.bytesWritten} resolved=${result.resolvedVideoUrl} final=${result.finalRequestUrl}"
                )
            }.onFailure { error ->
                val concise = error.message ?: "下载失败，请稍后重试。"
                Log.e(
                    DOWNLOAD_UI_LOG_TAG,
                    "download failed url=$candidate message=$concise",
                    error
                )
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

    fun onCopyExtractTabOpened() {
        if (uiState.selectedTranscriptVideoUri != null) return

        viewModelScope.launch {
            val latest = runCatching {
                transcriptRepository.queryLatestDownloadedVideo(getApplication())
            }.getOrNull()

            if (latest != null) {
                uiState = uiState.copy(
                    lastDownloadedVideoUri = latest.uri,
                    lastDownloadedVideoName = latest.displayName,
                    selectedTranscriptVideoUri = latest.uri,
                    selectedTranscriptVideoName = latest.displayName,
                    selectedTranscriptVideoSource = "视频下载最近记录",
                    transcriptMessage = "已默认选中最近下载视频：${latest.displayName}",
                    transcriptErrorMessage = null,
                    transcriptDiagnostics = buildTranscriptDiagnostics(
                        phase = "默认选中视频",
                        videoUri = latest.uri.toString(),
                        videoName = latest.displayName,
                        extra = listOf("来源 = v-down 最新下载记录")
                    )
                )
            } else {
                uiState = uiState.copy(
                    transcriptMessage = "未找到 v-down 最近下载视频，请手动选择一个视频。",
                    transcriptDiagnostics = buildTranscriptDiagnostics(
                        phase = "默认选中视频失败",
                        videoUri = null,
                        videoName = null,
                        extra = listOf("MediaStore 中未检索到 DCIM/v-down 视频")
                    )
                )
            }
        }
    }

    fun onVideoDedupTabOpened() {
        if (uiState.selectedDedupVideoUri != null) return

        viewModelScope.launch {
            val stateSnapshot = uiState
            val fromRecent = stateSnapshot.lastDownloadedVideoUri
            if (fromRecent != null) {
                val displayName = stateSnapshot.lastDownloadedVideoName
                    ?: runCatching {
                        transcriptRepository.resolveVideoDisplayName(getApplication(), fromRecent)
                    }.getOrElse { fromRecent.toString() }

                uiState = uiState.copy(
                    selectedDedupVideoUri = fromRecent,
                    selectedDedupVideoName = displayName,
                    selectedDedupVideoSource = "视频下载最近记录",
                    dedupMessage = "已默认选中最近下载视频：$displayName",
                    dedupErrorMessage = null,
                    dedupDiagnostics = buildDedupDiagnostics(
                        phase = "默认选中视频",
                        videoUri = fromRecent.toString(),
                        videoName = displayName,
                        extra = listOf("来源 = 视频下载最近记录")
                    )
                )
                return@launch
            }

            val latest = runCatching {
                transcriptRepository.queryLatestDownloadedVideo(getApplication())
            }.getOrNull()

            if (latest != null) {
                uiState = uiState.copy(
                    selectedDedupVideoUri = latest.uri,
                    selectedDedupVideoName = latest.displayName,
                    selectedDedupVideoSource = "MediaStore 最新记录",
                    dedupMessage = "已默认选中最近视频：${latest.displayName}",
                    dedupErrorMessage = null,
                    dedupDiagnostics = buildDedupDiagnostics(
                        phase = "默认选中视频",
                        videoUri = latest.uri.toString(),
                        videoName = latest.displayName,
                        extra = listOf("来源 = MediaStore DCIM/v-down")
                    )
                )
            } else {
                uiState = uiState.copy(
                    dedupMessage = "未找到可用视频，请手动选择。",
                    dedupDiagnostics = buildDedupDiagnostics(
                        phase = "默认选中视频失败",
                        videoUri = null,
                        videoName = null,
                        extra = listOf("MediaStore 中未检索到 DCIM/v-down 视频")
                    )
                )
            }
        }
    }

    fun selectDedupVideo(uri: Uri) {
        viewModelScope.launch {
            val displayName = runCatching {
                transcriptRepository.resolveVideoDisplayName(getApplication(), uri)
            }.getOrElse { uri.toString() }

            uiState = uiState.copy(
                selectedDedupVideoUri = uri,
                selectedDedupVideoName = displayName,
                selectedDedupVideoSource = "手动选择",
                dedupMessage = "已选择视频：$displayName",
                dedupErrorMessage = null,
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "手动选择视频",
                    videoUri = uri.toString(),
                    videoName = displayName
                )
            )
        }
    }

    fun updateDedupSpeedPercentDraft(value: String) {
        uiState = uiState.copy(
            dedupSpeedPercentDraft = value,
            dedupErrorMessage = null
        )
    }

    fun updateDedupTrimStartMsDraft(value: String) {
        uiState = uiState.copy(
            dedupTrimStartMsDraft = value,
            dedupErrorMessage = null
        )
    }

    fun updateDedupTrimEndMsDraft(value: String) {
        uiState = uiState.copy(
            dedupTrimEndMsDraft = value,
            dedupErrorMessage = null
        )
    }

    fun updateDedupPtsJitterMsDraft(value: String) {
        uiState = uiState.copy(
            dedupPtsJitterMsDraft = value,
            dedupErrorMessage = null
        )
    }

    fun updateDedupRandomTrimJitterMsDraft(value: String) {
        uiState = uiState.copy(
            dedupRandomTrimJitterMsDraft = value,
            dedupErrorMessage = null
        )
    }

    fun updateDedupSeedDraft(value: String) {
        uiState = uiState.copy(
            dedupSeedDraft = value,
            dedupErrorMessage = null
        )
    }

    fun setDedupIntroCoverMode(mode: DedupIntroCoverMode) {
        val snapshot = uiState
        uiState = uiState.copy(
            dedupIntroCoverMode = mode,
            dedupCoverOverlayWarning = if (mode == DedupIntroCoverMode.OVERLAY_FRAMES) {
                snapshot.dedupCoverOverlayWarning
            } else {
                null
            },
            dedupErrorMessage = null
        )
        persistDedupFeatureConfigAsync()

        if (mode == DedupIntroCoverMode.OVERLAY_FRAMES) {
            val coverUri = snapshot.dedupCoverImageUri
            val coverName = snapshot.dedupCoverImageName
            if (coverUri != null) {
                viewModelScope.launch {
                    val overlayValidation = inspectOverlayCoverMaterial(coverUri, coverName)
                    uiState = uiState.copy(
                        dedupCoverOverlayWarning = overlayValidation.warningMessage
                    )
                }
            }
        }
    }

    fun updateDedupIntroFrameCountDraft(value: String) {
        uiState = uiState.copy(
            dedupIntroFrameCountDraft = value,
            dedupErrorMessage = null
        )
        persistDedupFeatureConfigAsync()
    }

    fun updateDedupEndingImageDurationDraft(value: String) {
        uiState = uiState.copy(
            dedupEndingImageDurationMsDraft = value,
            dedupErrorMessage = null
        )
        persistDedupFeatureConfigAsync()
    }

    fun selectDedupCoverImage(sourceUri: Uri) {
        viewModelScope.launch {
            val sourceName = runCatching { resolveDisplayName(sourceUri) }.getOrDefault("cover")
            runCatching {
                cacheCoverImageToInternal(sourceUri)
            }.onSuccess { cached ->
                val previousCoverUri = uiState.dedupCoverImageUri
                deleteCachedCoverIfOwned(previousCoverUri, exceptPath = cached.path)
                val overlayValidation = if (uiState.dedupIntroCoverMode == DedupIntroCoverMode.OVERLAY_FRAMES) {
                    inspectOverlayCoverMaterial(cached, sourceName)
                } else {
                    null
                }
                val overlayWarning = overlayValidation?.warningMessage
                uiState = uiState.copy(
                    dedupCoverImageUri = cached,
                    dedupCoverImageName = sourceName,
                    dedupCoverOverlayWarning = overlayWarning,
                    dedupMessage = "封面已缓存，可用于片头插入或前帧覆盖。",
                    dedupErrorMessage = null
                )
                persistDedupFeatureConfigAsync()
            }.onFailure { error ->
                uiState = uiState.copy(
                    dedupErrorMessage = "封面缓存失败：${error.message ?: "无法读取图片"}"
                )
            }
        }
    }

    fun clearDedupCoverImage() {
        val oldUri = uiState.dedupCoverImageUri
        deleteCachedCoverIfOwned(oldUri, exceptPath = null)
        uiState = uiState.copy(
            dedupCoverImageUri = null,
            dedupCoverImageName = null,
            dedupCoverOverlayWarning = null,
            dedupMessage = "已清除封面设置。",
            dedupErrorMessage = null
        )
        persistDedupFeatureConfigAsync()
    }

    fun selectDedupEndingImage(uri: Uri) {
        viewModelScope.launch {
            val displayName = resolveDisplayName(uri)
            uiState = uiState.copy(
                dedupEndingType = DedupEndingType.IMAGE,
                dedupEndingMediaUri = uri,
                dedupEndingMediaName = displayName,
                dedupMessage = "已设置图片片尾：$displayName",
                dedupErrorMessage = null
            )
            persistDedupFeatureConfigAsync()
        }
    }

    fun selectDedupEndingVideo(uri: Uri) {
        viewModelScope.launch {
            val displayName = resolveDisplayName(uri)
            uiState = uiState.copy(
                dedupEndingType = DedupEndingType.VIDEO,
                dedupEndingMediaUri = uri,
                dedupEndingMediaName = displayName,
                dedupMessage = "已设置视频片尾：$displayName",
                dedupErrorMessage = null
            )
            persistDedupFeatureConfigAsync()
        }
    }

    fun clearDedupEnding() {
        uiState = uiState.copy(
            dedupEndingType = DedupEndingType.NONE,
            dedupEndingMediaUri = null,
            dedupEndingMediaName = null,
            dedupMessage = "已清除片尾设置。",
            dedupErrorMessage = null
        )
        persistDedupFeatureConfigAsync()
    }

    fun updateDedupOutputPrefixDraft(value: String) {
        uiState = uiState.copy(
            dedupOutputPrefixDraft = value,
            dedupErrorMessage = null
        )
    }

    fun setDedupRandomSuffixEnabled(enabled: Boolean) {
        uiState = uiState.copy(
            dedupRandomSuffixEnabled = enabled,
            dedupErrorMessage = null
        )
    }

    fun setDedupShuffleTrackOrderEnabled(enabled: Boolean) {
        uiState = uiState.copy(
            dedupShuffleTrackOrderEnabled = enabled,
            dedupErrorMessage = null
        )
    }

    fun applyDedupPreset(template: DedupPresetTemplate) {
        uiState = uiState.copy(
            dedupPresetName = template.name,
            dedupSpeedPercentDraft = template.speedPercent.toString(),
            dedupTrimStartMsDraft = template.trimStartMs.toString(),
            dedupTrimEndMsDraft = template.trimEndMs.toString(),
            dedupPtsJitterMsDraft = template.ptsJitterMs.toString(),
            dedupRandomTrimJitterMsDraft = template.randomTrimJitterMs.toString(),
            dedupShuffleTrackOrderEnabled = template.shuffleTrackOrder,
            dedupMessage = "已应用去重模板：${template.title}",
            dedupErrorMessage = null
        )
    }

    fun startVideoDedup(hasStorageWritePermission: Boolean) {
        if (uiState.isDedupProcessing) return

        val targetUri = uiState.selectedDedupVideoUri
        val targetName = uiState.selectedDedupVideoName ?: "(未知视频)"
        if (targetUri == null) {
            uiState = uiState.copy(
                dedupErrorMessage = "请先选择视频，再执行去重。",
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "去重前校验失败",
                    videoUri = null,
                    videoName = null,
                    hasStorageWritePermission = hasStorageWritePermission,
                    error = IllegalArgumentException("未选择视频")
                )
            )
            return
        }

        val speed = parseDedupInt(
            uiState.dedupSpeedPercentDraft,
            fieldName = "速度微调",
            range = 95..105
        ) ?: return
        val trimStart = parseDedupInt(
            uiState.dedupTrimStartMsDraft,
            fieldName = "起始裁剪",
            range = 0..3_000
        ) ?: return
        val trimEnd = parseDedupInt(
            uiState.dedupTrimEndMsDraft,
            fieldName = "结尾裁剪",
            range = 0..3_000
        ) ?: return
        val ptsJitterMs = parseDedupInt(
            uiState.dedupPtsJitterMsDraft,
            fieldName = "时间微扰",
            range = 0..40
        ) ?: return
        val randomTrimJitterMs = parseDedupInt(
            uiState.dedupRandomTrimJitterMsDraft,
            fieldName = "随机裁剪抖动",
            range = 0..600
        ) ?: return
        val seedRaw = uiState.dedupSeedDraft.trim()
        val dedupSeed = if (seedRaw.isBlank()) null else seedRaw.toLongOrNull()
        if (seedRaw.isNotBlank() && (dedupSeed == null || dedupSeed < 0L)) {
            uiState = uiState.copy(
                dedupErrorMessage = "随机种子参数无效，请输入大于等于 0 的整数，或留空自动生成。",
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "去重前校验失败",
                    videoUri = uiState.selectedDedupVideoUri?.toString(),
                    videoName = uiState.selectedDedupVideoName,
                    hasStorageWritePermission = hasStorageWritePermission,
                    error = IllegalArgumentException("随机种子非法: $seedRaw"),
                    extra = listOf("参数范围 = >=0（留空自动生成）", "当前输入 = $seedRaw")
                )
            )
            return
        }
        val introFrameCount = parseDedupInt(
            uiState.dedupIntroFrameCountDraft,
            fieldName = "片头帧数",
            range = 1..60
        ) ?: return
        val endingImageDurationMs = parseDedupInt(
            uiState.dedupEndingImageDurationMsDraft,
            fieldName = "图片片尾时长",
            range = 300..10_000
        ) ?: return
        val introCoverMode = uiState.dedupIntroCoverMode
        val coverImageUri = uiState.dedupCoverImageUri
        val coverImageName = uiState.dedupCoverImageName
        val endingType = uiState.dedupEndingType
        val endingMediaUri = uiState.dedupEndingMediaUri
        val randomSuffixEnabled = uiState.dedupRandomSuffixEnabled
        val shuffleTrackOrderEnabled = uiState.dedupShuffleTrackOrderEnabled
        if (introCoverMode != DedupIntroCoverMode.NONE && coverImageUri == null) {
            uiState = uiState.copy(
                dedupErrorMessage = "已启用片头模式，但未选择封面图片。",
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "去重前校验失败",
                    videoUri = uiState.selectedDedupVideoUri?.toString(),
                    videoName = uiState.selectedDedupVideoName,
                    hasStorageWritePermission = hasStorageWritePermission,
                    error = IllegalArgumentException("片头封面为空"),
                    extra = listOf("片头模式 = ${introCoverMode.title}")
                )
            )
            return
        }
        if (endingType != DedupEndingType.NONE && endingMediaUri == null) {
            uiState = uiState.copy(
                dedupErrorMessage = "已启用片尾，但未选择片尾素材。",
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "去重前校验失败",
                    videoUri = uiState.selectedDedupVideoUri?.toString(),
                    videoName = uiState.selectedDedupVideoName,
                    hasStorageWritePermission = hasStorageWritePermission,
                    error = IllegalArgumentException("片尾素材为空"),
                    extra = listOf("片尾类型 = ${endingType.title}")
                )
            )
            return
        }
        val outputPrefix = uiState.dedupOutputPrefixDraft.trim().ifBlank { "dedup" }
        val dedupPreset = DedupPresetTemplate.fromName(uiState.dedupPresetName)

        viewModelScope.launch {
            val overlayValidation = if (introCoverMode == DedupIntroCoverMode.OVERLAY_FRAMES && coverImageUri != null) {
                inspectOverlayCoverMaterial(coverImageUri, coverImageName)
            } else {
                null
            }
            val overlayValidationLines = overlayValidation?.diagnosticsLines().orEmpty()
            uiState = uiState.copy(
                isDedupProcessing = true,
                dedupProgress = 0,
                dedupMessage = "正在执行视频去重...",
                dedupErrorMessage = null,
                dedupCoverOverlayWarning = overlayValidation?.warningMessage,
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "开始去重",
                    videoUri = targetUri.toString(),
                    videoName = targetName,
                    hasStorageWritePermission = hasStorageWritePermission,
                    extra = listOf(
                        "速度微调(%) = $speed",
                        "起始裁剪(ms) = $trimStart",
                        "结尾裁剪(ms) = $trimEnd",
                        "时间微扰(ms) = $ptsJitterMs",
                        "随机裁剪抖动(ms) = $randomTrimJitterMs",
                        "随机种子(Seed输入) = ${dedupSeed?.toString() ?: "自动生成"}",
                        "封面图片 = ${if (coverImageUri != null) (uiState.dedupCoverImageName ?: coverImageUri.toString()) else "未设置"}",
                        "片头模式 = ${introCoverMode.title}",
                        "片头帧数 = $introFrameCount",
                        "片尾类型 = ${endingType.title}",
                        "片尾素材 = ${if (endingMediaUri != null) (uiState.dedupEndingMediaName ?: endingMediaUri.toString()) else "未设置"}",
                        "图片片尾时长(ms) = $endingImageDurationMs",
                        "输出前缀 = $outputPrefix",
                        "随机后缀 = $randomSuffixEnabled",
                        "轨道顺序扰动 = $shuffleTrackOrderEnabled",
                        "模板 = ${dedupPreset.title}",
                        "输出目录 = DCIM/v-down"
                    ) + overlayValidationLines
                )
            )

            runCatching {
                videoDedupRepository.dedupVideo(
                    context = getApplication(),
                    request = VideoDedupRequest(
                        sourceVideoUri = targetUri,
                        sourceVideoName = targetName,
                        presetName = dedupPreset.title,
                        speedPercent = speed,
                        trimStartMs = trimStart,
                        trimEndMs = trimEnd,
                        ptsJitterMs = ptsJitterMs,
                        randomTrimJitterMs = randomTrimJitterMs,
                        shuffleTrackOrder = shuffleTrackOrderEnabled,
                        outputPrefix = outputPrefix,
                        randomSuffixEnabled = randomSuffixEnabled,
                        seed = dedupSeed,
                        coverImageUri = coverImageUri,
                        introCoverMode = introCoverMode,
                        introFrameCount = introFrameCount,
                        endingType = endingType,
                        endingMediaUri = endingMediaUri,
                        endingImageDurationMs = endingImageDurationMs
                    ),
                    hasStorageWritePermission = hasStorageWritePermission
                ) { progress ->
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(dedupProgress = progress.coerceIn(0, 100))
                    }
                }
            }.onSuccess { result ->
                val durationMs = result.durationUs / 1_000L
                uiState = uiState.copy(
                    isDedupProcessing = false,
                    dedupProgress = 100,
                    dedupMessage = "去重完成：${result.outputName}（约${durationMs}ms），已保存到 DCIM/v-down。",
                    dedupErrorMessage = null,
                    dedupCoverOverlayWarning = overlayValidation?.warningMessage,
                    dedupDiagnostics = buildDedupDiagnostics(
                        phase = "去重成功",
                        videoUri = targetUri.toString(),
                        videoName = targetName,
                        hasStorageWritePermission = hasStorageWritePermission,
                        extra = listOf(
                            "输出文件 = ${result.outputName}",
                            "输出 URI = ${result.outputUri}",
                            "输出字节数 = ${result.bytesWritten}",
                            "估算时长(us) = ${result.durationUs}",
                            "实际Seed = ${result.actualSeed}",
                            "起始附加裁剪(ms) = ${result.startTrimExtraMs}",
                            "结尾附加裁剪(ms) = ${result.endTrimExtraMs}",
                            "源MD5 = ${result.sourceMd5}",
                            "输出MD5 = ${result.outputMd5}",
                            "哈希是否变化 = ${if (result.sourceMd5.equals(result.outputMd5, ignoreCase = true)) "否" else "是"}",
                            "封面应用 = ${if (result.coverApplied) "是" else "否"}",
                            "片头模式 = ${result.introModeApplied}",
                            "片头帧率(FPS) = ${result.sourceFrameRate}",
                            "片头生效帧数 = ${result.introFrameCountApplied}",
                            "覆盖模式是否生效 = ${if (result.overlayApplied) "是" else "否"}",
                            "片尾应用 = ${result.endingApplied}",
                            "策略 = ${result.strategySummary}"
                        ) + overlayValidationLines
                    ),
                    lastDedupVideoUri = result.outputUri,
                    lastDedupVideoName = result.outputName,
                    selectedTranscriptVideoUri = result.outputUri,
                    selectedTranscriptVideoName = result.outputName,
                    selectedTranscriptVideoSource = "视频去重输出",
                    transcriptMessage = "已默认选中去重输出视频，可直接导出文案。"
                )
                Log.i(
                    DEDUP_UI_LOG_TAG,
                    "dedup success input=$targetUri output=${result.outputUri} bytes=${result.bytesWritten}"
                )
            }.onFailure { error ->
                val dedupFailureContext = mutableListOf(
                    "片头模式 = ${uiState.dedupIntroCoverMode.title}",
                    "片头帧数 = ${uiState.dedupIntroFrameCountDraft}",
                    "封面素材URI方案 = ${uiState.dedupCoverImageUri?.scheme ?: "N/A"}",
                    "片尾类型 = ${uiState.dedupEndingType.title}",
                    "片尾素材URI方案 = ${uiState.dedupEndingMediaUri?.scheme ?: "N/A"}",
                    "随机种子草稿 = ${uiState.dedupSeedDraft.ifBlank { "(自动生成)" }}"
                )
                dedupFailureContext += overlayValidationLines
                uiState = uiState.copy(
                    isDedupProcessing = false,
                    dedupMessage = null,
                    dedupErrorMessage = error.message ?: "视频去重失败，请稍后重试。",
                    dedupCoverOverlayWarning = overlayValidation?.warningMessage,
                    dedupDiagnostics = buildDedupDiagnostics(
                        phase = "去重失败",
                        videoUri = targetUri.toString(),
                        videoName = targetName,
                        hasStorageWritePermission = hasStorageWritePermission,
                        error = error,
                        extra = dedupFailureContext
                    )
                )
                Log.e(DEDUP_UI_LOG_TAG, "dedup failed input=$targetUri message=${error.message}", error)
            }
        }
    }

    fun selectTranscriptVideo(uri: Uri) {
        viewModelScope.launch {
            val displayName = runCatching {
                transcriptRepository.resolveVideoDisplayName(getApplication(), uri)
            }.getOrElse {
                uri.toString()
            }

            resetAudioPlaybackState(releasePlayer = true)

            uiState = uiState.copy(
                selectedTranscriptVideoUri = uri,
                selectedTranscriptVideoName = displayName,
                selectedTranscriptVideoSource = "手动选择",
                transcriptMessage = "已选择视频：$displayName",
                transcriptText = null,
                transcriptAudioPath = null,
                transcriptErrorMessage = null,
                aiRewrittenText = null,
                aiRewriteErrorMessage = null,
                audioStatusMessage = "已切换视频，可点击“音频播放”播放音轨。",
                transcriptDiagnostics = buildTranscriptDiagnostics(
                    phase = "手动选择视频",
                    videoUri = uri.toString(),
                    videoName = displayName
                )
            )
        }
    }

    fun startAudioPlayback() {
        uiState = uiState.copy(
            isAudioControlVisible = true,
            audioErrorMessage = null
        )
        playAudio()
    }

    fun playAudio() {
        val targetUri = uiState.selectedTranscriptVideoUri
        if (targetUri == null) {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                audioStatusMessage = null,
                audioErrorMessage = "请先选择视频，再播放音频。"
            )
            return
        }

        val player = audioPlayer
        if (player != null && audioPlayerSourceUri?.toString() == targetUri.toString()) {
            if (uiState.isAudioPreparing) {
                uiState = uiState.copy(
                    isAudioControlVisible = true,
                    audioStatusMessage = "正在加载音频，请稍候...",
                    audioErrorMessage = null
                )
                return
            }

            runCatching {
                player.start()
            }.onSuccess {
                uiState = uiState.copy(
                    isAudioControlVisible = true,
                    isAudioPlaying = true,
                    isAudioPaused = false,
                    audioStatusMessage = "正在播放音频...",
                    audioErrorMessage = null
                )
            }.onFailure { error ->
                releaseAudioPlayer()
                uiState = uiState.copy(
                    isAudioControlVisible = true,
                    isAudioPreparing = false,
                    isAudioPlaying = false,
                    isAudioPaused = false,
                    audioStatusMessage = null,
                    audioErrorMessage = "音频播放失败：${error.message ?: "无法开始播放"}"
                )
            }
            return
        }

        releaseAudioPlayer()

        val newPlayer = MediaPlayer()
        audioPlayer = newPlayer
        audioPlayerSourceUri = targetUri

        newPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        newPlayer.setOnPreparedListener { prepared ->
            runCatching {
                prepared.start()
            }.onSuccess {
                uiState = uiState.copy(
                    isAudioControlVisible = true,
                    isAudioPreparing = false,
                    isAudioPlaying = true,
                    isAudioPaused = false,
                    audioStatusMessage = "正在播放音频...",
                    audioErrorMessage = null
                )
            }.onFailure { error ->
                releaseAudioPlayer()
                uiState = uiState.copy(
                    isAudioControlVisible = true,
                    isAudioPreparing = false,
                    isAudioPlaying = false,
                    isAudioPaused = false,
                    audioStatusMessage = null,
                    audioErrorMessage = "音频播放失败：${error.message ?: "无法开始播放"}"
                )
            }
        }
        newPlayer.setOnCompletionListener { completed ->
            runCatching { completed.seekTo(0) }
            uiState = uiState.copy(
                isAudioControlVisible = true,
                isAudioPreparing = false,
                isAudioPlaying = false,
                isAudioPaused = false,
                audioStatusMessage = "播放结束。点击“播放”可从头开始。",
                audioErrorMessage = null
            )
        }
        newPlayer.setOnErrorListener { _, what, extra ->
            releaseAudioPlayer()
            uiState = uiState.copy(
                isAudioControlVisible = true,
                isAudioPreparing = false,
                isAudioPlaying = false,
                isAudioPaused = false,
                audioStatusMessage = null,
                audioErrorMessage = "音频播放失败：what=$what, extra=$extra"
            )
            true
        }

        runCatching {
            newPlayer.setDataSource(getApplication(), targetUri)
            newPlayer.prepareAsync()
        }.onSuccess {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                isAudioPreparing = true,
                isAudioPlaying = false,
                isAudioPaused = false,
                audioStatusMessage = "正在加载音频...",
                audioErrorMessage = null
            )
        }.onFailure { error ->
            releaseAudioPlayer()
            uiState = uiState.copy(
                isAudioControlVisible = true,
                isAudioPreparing = false,
                isAudioPlaying = false,
                isAudioPaused = false,
                audioStatusMessage = null,
                audioErrorMessage = "音频播放失败：${error.message ?: "无法读取视频音轨"}"
            )
        }
    }

    fun pauseAudio() {
        val player = audioPlayer
        if (player == null) {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                audioStatusMessage = "当前没有可暂停的播放。",
                audioErrorMessage = null
            )
            return
        }

        if (uiState.isAudioPreparing) {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                audioStatusMessage = "音频正在加载，暂不可暂停。",
                audioErrorMessage = null
            )
            return
        }

        runCatching {
            if (player.isPlaying) {
                player.pause()
            }
        }.onSuccess {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                isAudioPlaying = false,
                isAudioPaused = true,
                audioStatusMessage = "已暂停。点击“播放”将从暂停位置继续。",
                audioErrorMessage = null
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                isAudioControlVisible = true,
                audioStatusMessage = null,
                audioErrorMessage = "暂停失败：${error.message ?: "未知错误"}"
            )
        }
    }

    fun stopAudio() {
        val player = audioPlayer
        if (player == null) {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                audioStatusMessage = "当前没有播放中的音频。",
                audioErrorMessage = null
            )
            return
        }

        runCatching {
            if (player.isPlaying) {
                player.pause()
            }
            player.seekTo(0)
        }.onSuccess {
            uiState = uiState.copy(
                isAudioControlVisible = true,
                isAudioPreparing = false,
                isAudioPlaying = false,
                isAudioPaused = false,
                audioStatusMessage = "已停止。点击“播放”将从头开始。",
                audioErrorMessage = null
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                isAudioControlVisible = true,
                audioStatusMessage = null,
                audioErrorMessage = "停止失败：${error.message ?: "未知错误"}"
            )
        }
    }

    fun exportSelectedVideoTranscript() {
        if (uiState.isExportingTranscript) return

        val targetUri = uiState.selectedTranscriptVideoUri
        val targetName = uiState.selectedTranscriptVideoName ?: "(未知视频)"
        val selectedAsrProvider = resolveEffectiveAsrProvider()
        if (targetUri == null) {
            uiState = uiState.copy(
                transcriptErrorMessage = "请先选择视频，再导出文案。",
                transcriptDiagnostics = buildTranscriptDiagnostics(
                    phase = "导出前校验失败",
                    videoUri = null,
                    videoName = null,
                    error = IllegalArgumentException("未选择视频")
                )
            )
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(
                isExportingTranscript = true,
                transcriptMessage = "步骤1/2：正在从视频分离音频...",
                transcriptText = null,
                transcriptAudioPath = null,
                transcriptErrorMessage = null,
                aiRewrittenText = null,
                aiRewriteErrorMessage = null,
                aiRewriteDiagnostics = null,
                transcriptDiagnostics = buildTranscriptDiagnostics(
                    phase = "开始导出文案",
                    videoUri = targetUri.toString(),
                    videoName = targetName,
                    extra = listOf(
                        "步骤 = 1/2 分离音频",
                        "优先ASR = ${selectedAsrProvider?.displayName ?: "(未配置)"}",
                        "手动语种 = ${selectedAsrProvider?.preferredLanguage?.title ?: AsrLanguageOption.AUTO.title}",
                        "ASR配置来源 = ${describeEffectiveAsrSource()}"
                    )
                )
            )

            runCatching {
                val pcmAudio = transcriptRepository.decodeVideoAudioToPcm(getApplication(), targetUri)
                val wavFile = transcriptRepository.exportPcmToWav(getApplication(), pcmAudio)

                uiState = uiState.copy(
                    transcriptMessage = if (pcmAudio.wasDurationClipped && pcmAudio.clipLimitMs != null) {
                        "步骤2/2：正在从音频解析文案（快速模式：仅前${pcmAudio.clipLimitMs / 1000}秒）..."
                    } else {
                        "步骤2/2：正在从音频解析文案..."
                    },
                    transcriptAudioPath = wavFile.absolutePath,
                    transcriptDiagnostics = buildTranscriptDiagnostics(
                        phase = "音频分离完成",
                        videoUri = targetUri.toString(),
                        videoName = targetName,
                        extra = buildList {
                            add("PCM 文件 = ${pcmAudio.file.absolutePath}")
                            add("WAV 文件 = ${wavFile.absolutePath}")
                            add("WAV 字节数 = ${wavFile.length()}")
                            add("PCM 字节数 = ${pcmAudio.bytesWritten}")
                            add("采样率 = ${pcmAudio.sampleRate}")
                            add("声道数 = ${pcmAudio.channelCount}")
                            add("估算时长(ms) = ${pcmAudio.durationMs}")
                            addAll(buildFastModeNotes(pcmAudio))
                        }
                    )
                )

                val attempt = transcribeWithCloudAsr(
                    wavFile = wavFile,
                    selectedAsrProvider = selectedAsrProvider
                )

                Triple(pcmAudio, wavFile, attempt)
            }.onSuccess { (pcmAudio, wavFile, attempt) ->
                uiState = uiState.copy(
                    isExportingTranscript = false,
                    transcriptMessage = if (pcmAudio.wasDurationClipped && pcmAudio.clipLimitMs != null) {
                        "文案导出完成（快速模式：仅识别前${pcmAudio.clipLimitMs / 1000}秒）。"
                    } else {
                        "文案导出完成。"
                    },
                    transcriptText = attempt.transcriptText,
                    transcriptAudioPath = wavFile.absolutePath,
                    transcriptErrorMessage = null,
                    aiRewrittenText = null,
                    aiRewriteErrorMessage = null,
                    aiRewriteDiagnostics = null,
                    transcriptDiagnostics = buildTranscriptDiagnostics(
                        phase = "文案导出成功",
                        videoUri = targetUri.toString(),
                        videoName = targetName,
                        extra = buildList {
                            add("输出文案长度 = ${attempt.transcriptText.length}")
                            add("识别路径 = ${attempt.route}")
                            attempt.providerName?.let { add("ASR服务 = $it") }
                            add("PCM 文件 = ${pcmAudio.file.absolutePath}")
                            add("WAV 文件 = ${wavFile.absolutePath}")
                            add("WAV 字节数 = ${wavFile.length()}")
                            add("PCM 字节数 = ${pcmAudio.bytesWritten}")
                            add("估算时长(ms) = ${pcmAudio.durationMs}")
                            addAll(buildFastModeNotes(pcmAudio))
                            addAll(attempt.notes)
                        }
                    )
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    isExportingTranscript = false,
                    transcriptMessage = null,
                    transcriptErrorMessage = error.message ?: "导出文案失败，请稍后重试。",
                    transcriptDiagnostics = buildTranscriptDiagnostics(
                        phase = "文案导出失败",
                        videoUri = targetUri.toString(),
                        videoName = targetName,
                        error = error
                    )
                )
            }
        }
    }

    private suspend fun transcribeWithCloudAsr(
        wavFile: File,
        selectedAsrProvider: AsrProviderConfig?
    ): TranscriptAttemptResult {
        val provider = selectedAsrProvider ?: throw IllegalStateException(
            "导出文案失败：未配置可用的ASR服务。请在“设置”中选择 OpenAI/兼容OpenAI/AzureOpenAI/Vosk(本地)，或选择可复用的 LLM 服务后重试。"
        )

        val text = asrTranscriptionRepository.transcribeAudio(
            config = provider,
            audioFile = wavFile
        )
        return TranscriptAttemptResult(
            transcriptText = text,
            route = "ASR识别",
            providerName = provider.displayName,
            notes = listOf(
                "本版本不再使用系统麦克风识别兜底"
            )
        )
    }

    private fun buildFastModeNotes(pcmAudio: com.vdown.app.transcript.DecodedPcmAudio): List<String> {
        val limitMs = pcmAudio.clipLimitMs ?: return emptyList()
        return listOf(
            "快速模式上限(ms) = $limitMs",
            if (pcmAudio.wasDurationClipped) {
                "音频截断 = 是（仅前${limitMs / 1000}秒）"
            } else {
                "音频截断 = 否（视频时长未超过上限）"
            }
        )
    }

    private fun resolveEffectiveAsrProvider(): AsrProviderConfig? {
        val explicit = uiState.asrProviders.firstOrNull { it.id == uiState.selectedAsrProviderId }
        if (explicit != null) return explicit
        return deriveAsrProviderFromSelectedLlm()
    }

    private fun deriveAsrProviderFromSelectedLlm(): AsrProviderConfig? {
        val selectedLlm = uiState.llmProviders.firstOrNull { it.id == uiState.selectedLlmProviderId } ?: return null
        val asrType = when (selectedLlm.providerType) {
            LlmProviderType.OPENAI -> AsrProviderType.OPENAI
            LlmProviderType.OPENAI_COMPATIBLE -> AsrProviderType.OPENAI_COMPATIBLE
            LlmProviderType.AZURE_OPENAI -> AsrProviderType.AZURE_OPENAI
            LlmProviderType.DEEPSEEK,
            LlmProviderType.GEMINI -> return null
        }

        val modelFromLlm = selectedLlm.model.trim()
        val effectiveModel = when {
            modelFromLlm.isBlank() -> defaultAsrModelFor(asrType)
            modelFromLlm.contains("transcribe", ignoreCase = true) -> modelFromLlm
            modelFromLlm.contains("whisper", ignoreCase = true) -> modelFromLlm
            else -> defaultAsrModelFor(asrType)
        }

        return AsrProviderConfig(
            id = "llm_proxy_${selectedLlm.id}",
            displayName = "${selectedLlm.displayName}(复用LLM)",
            providerType = asrType,
            baseUrl = selectedLlm.baseUrl,
            apiKey = selectedLlm.apiKey,
            model = effectiveModel,
            organization = selectedLlm.organization,
            azureDeployment = selectedLlm.azureDeployment.ifBlank { selectedLlm.model }.ifBlank { effectiveModel },
            azureApiVersion = selectedLlm.azureApiVersion,
            preferredLanguage = AsrLanguageOption.AUTO
        )
    }

    private fun describeEffectiveAsrSource(): String {
        if (uiState.asrProviders.any { it.id == uiState.selectedAsrProviderId }) {
            return "ASR独立配置"
        }
        return if (deriveAsrProviderFromSelectedLlm() != null) "LLM配置复用" else "未配置"
    }

    fun refreshLlmProviderState() {
        viewModelScope.launch {
            val providers = withContext(Dispatchers.IO) { llmConfigRepository.loadConfigs() }
            var selectedId = withContext(Dispatchers.IO) { llmConfigRepository.loadSelectedProviderId() }
            if (providers.none { it.id == selectedId }) {
                selectedId = providers.firstOrNull()?.id
                withContext(Dispatchers.IO) {
                    llmConfigRepository.saveSelectedProviderId(selectedId)
                }
            }

            uiState = uiState.copy(
                llmProviders = providers,
                selectedLlmProviderId = selectedId
            )
        }
    }

    fun saveLlmProvider(
        editingId: String?,
        displayName: String,
        providerType: LlmProviderType,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        organization: String,
        azureDeployment: String,
        azureApiVersion: String
    ) {
        val safeName = displayName.trim()
        if (safeName.isBlank()) {
            uiState = uiState.copy(llmErrorMessage = "请填写服务名称", llmStatusMessage = null)
            return
        }

        val safeApiKey = apiKey.trim()
        if (safeApiKey.isBlank()) {
            uiState = uiState.copy(llmErrorMessage = "请填写 API Key", llmStatusMessage = null)
            return
        }

        val safeBaseUrl = baseUrl.trim().ifBlank { defaultBaseUrlFor(providerType) }
        val safeModel = model.trim().ifBlank { defaultModelFor(providerType) }
        val safeSystemPrompt = systemPrompt.trim().ifBlank { defaultLlmSystemPrompt() }
        if (providerType != LlmProviderType.AZURE_OPENAI && safeModel.isBlank()) {
            uiState = uiState.copy(llmErrorMessage = "请填写模型名称（Model）", llmStatusMessage = null)
            return
        }

        val safeAzureDeployment = azureDeployment.trim().ifBlank { safeModel }
        if (providerType == LlmProviderType.AZURE_OPENAI && safeAzureDeployment.isBlank()) {
            uiState = uiState.copy(llmErrorMessage = "AzureOpenAI 需要 Deployment 名称", llmStatusMessage = null)
            return
        }

        val safeAzureApiVersion = azureApiVersion.trim().ifBlank { "2024-10-21" }
        val providerId = editingId?.takeIf { it.isNotBlank() } ?: "provider_${System.currentTimeMillis()}"
        val newConfig = LlmProviderConfig(
            id = providerId,
            displayName = safeName,
            providerType = providerType,
            baseUrl = safeBaseUrl,
            apiKey = safeApiKey,
            model = safeModel,
            systemPrompt = safeSystemPrompt,
            organization = organization.trim(),
            azureDeployment = safeAzureDeployment,
            azureApiVersion = safeAzureApiVersion
        )

        val oldList = uiState.llmProviders
        val nextList = oldList.toMutableList()
        val index = nextList.indexOfFirst { it.id == providerId }
        val isEdit = index >= 0
        if (isEdit) {
            nextList[index] = newConfig
        } else {
            nextList.add(0, newConfig)
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                llmConfigRepository.saveConfigs(nextList)
                llmConfigRepository.saveSelectedProviderId(providerId)
            }
            uiState = uiState.copy(
                llmProviders = nextList,
                selectedLlmProviderId = providerId,
                llmStatusMessage = if (isEdit) "LLM 配置已更新并设为当前服务" else "LLM 配置已添加并设为当前服务",
                llmErrorMessage = null
            )
        }
    }

    fun selectLlmProvider(providerId: String) {
        val exists = uiState.llmProviders.any { it.id == providerId }
        if (!exists) {
            uiState = uiState.copy(
                llmErrorMessage = "服务不存在，无法切换",
                llmStatusMessage = null
            )
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                llmConfigRepository.saveSelectedProviderId(providerId)
            }
            uiState = uiState.copy(
                selectedLlmProviderId = providerId,
                llmStatusMessage = "已切换当前 LLM 服务",
                llmErrorMessage = null
            )
        }
    }

    fun removeLlmProvider(providerId: String) {
        val nextList = uiState.llmProviders.filterNot { it.id == providerId }
        val nextSelected = when {
            uiState.selectedLlmProviderId == providerId -> nextList.firstOrNull()?.id
            else -> uiState.selectedLlmProviderId
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                llmConfigRepository.saveConfigs(nextList)
                llmConfigRepository.saveSelectedProviderId(nextSelected)
            }
            uiState = uiState.copy(
                llmProviders = nextList,
                selectedLlmProviderId = nextSelected,
                llmStatusMessage = "已删除 LLM 配置",
                llmErrorMessage = null
            )
        }
    }

    fun refreshAsrProviderState() {
        viewModelScope.launch {
            val providers = withContext(Dispatchers.IO) { asrConfigRepository.loadConfigs() }
            var selectedId = withContext(Dispatchers.IO) { asrConfigRepository.loadSelectedProviderId() }
            if (providers.none { it.id == selectedId }) {
                selectedId = providers.firstOrNull()?.id
                withContext(Dispatchers.IO) {
                    asrConfigRepository.saveSelectedProviderId(selectedId)
                }
            }

            uiState = uiState.copy(
                asrProviders = providers,
                selectedAsrProviderId = selectedId
            )
        }
    }

    fun saveAsrProvider(
        editingId: String?,
        displayName: String,
        providerType: AsrProviderType,
        preferredLanguage: AsrLanguageOption,
        baseUrl: String,
        apiKey: String,
        model: String,
        localModelPath: String,
        organization: String,
        azureDeployment: String,
        azureApiVersion: String
    ) {
        val safeName = displayName.trim()
        if (safeName.isBlank()) {
            uiState = uiState.copy(asrErrorMessage = "请填写 ASR 服务名称", asrStatusMessage = null)
            return
        }

        val safeApiKey = apiKey.trim()
        val safeBaseUrl = baseUrl.trim().ifBlank { defaultAsrBaseUrlFor(providerType) }
        val safeModel = model.trim().ifBlank { defaultAsrModelFor(providerType) }
        val safeLocalModelPath = localModelPath.trim()
        val safeAzureApiVersion = azureApiVersion.trim().ifBlank { "2024-10-21" }
        val safeAzureDeployment = azureDeployment.trim().ifBlank { safeModel }

        when (providerType) {
            AsrProviderType.VOSK_LOCAL -> {
                if (safeLocalModelPath.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "请填写 Vosk 模型目录路径", asrStatusMessage = null)
                    return
                }
            }

            AsrProviderType.AZURE_OPENAI -> {
                if (safeApiKey.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "请填写 Azure ASR API Key", asrStatusMessage = null)
                    return
                }
                if (safeBaseUrl.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "请填写 Azure ASR Base URL", asrStatusMessage = null)
                    return
                }
                if (safeAzureDeployment.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "AzureOpenAI 需要 ASR Deployment 名称", asrStatusMessage = null)
                    return
                }
            }

            AsrProviderType.OPENAI,
            AsrProviderType.OPENAI_COMPATIBLE -> {
                if (safeApiKey.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "请填写 ASR API Key", asrStatusMessage = null)
                    return
                }
                if (safeBaseUrl.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "请填写 ASR Base URL", asrStatusMessage = null)
                    return
                }
                if (safeModel.isBlank()) {
                    uiState = uiState.copy(asrErrorMessage = "请填写 ASR 模型名称（Model）", asrStatusMessage = null)
                    return
                }
            }
        }

        val providerId = editingId?.takeIf { it.isNotBlank() } ?: "asr_provider_${System.currentTimeMillis()}"
        val newConfig = AsrProviderConfig(
            id = providerId,
            displayName = safeName,
            providerType = providerType,
            baseUrl = if (providerType == AsrProviderType.VOSK_LOCAL) "" else safeBaseUrl,
            apiKey = if (providerType == AsrProviderType.VOSK_LOCAL) "" else safeApiKey,
            model = safeModel,
            organization = if (providerType == AsrProviderType.OPENAI || providerType == AsrProviderType.OPENAI_COMPATIBLE) {
                organization.trim()
            } else {
                ""
            },
            azureDeployment = if (providerType == AsrProviderType.AZURE_OPENAI) safeAzureDeployment else "",
            azureApiVersion = if (providerType == AsrProviderType.AZURE_OPENAI) safeAzureApiVersion else "",
            localModelPath = if (providerType == AsrProviderType.VOSK_LOCAL) safeLocalModelPath else "",
            preferredLanguage = preferredLanguage
        )

        val oldList = uiState.asrProviders
        val nextList = oldList.toMutableList()
        val index = nextList.indexOfFirst { it.id == providerId }
        val isEdit = index >= 0
        if (isEdit) {
            nextList[index] = newConfig
        } else {
            nextList.add(0, newConfig)
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                asrConfigRepository.saveConfigs(nextList)
                asrConfigRepository.saveSelectedProviderId(providerId)
            }
            uiState = uiState.copy(
                asrProviders = nextList,
                selectedAsrProviderId = providerId,
                asrStatusMessage = if (isEdit) "ASR 配置已更新并设为当前服务" else "ASR 配置已添加并设为当前服务",
                asrErrorMessage = null
            )
        }
    }

    fun selectAsrProvider(providerId: String) {
        val exists = uiState.asrProviders.any { it.id == providerId }
        if (!exists) {
            uiState = uiState.copy(
                asrErrorMessage = "ASR 服务不存在，无法切换",
                asrStatusMessage = null
            )
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                asrConfigRepository.saveSelectedProviderId(providerId)
            }
            uiState = uiState.copy(
                selectedAsrProviderId = providerId,
                asrStatusMessage = "已切换当前 ASR 服务",
                asrErrorMessage = null
            )
        }
    }

    fun updateCurrentAsrPreferredLanguage(language: AsrLanguageOption) {
        val selectedId = uiState.selectedAsrProviderId
        if (selectedId.isNullOrBlank()) {
            uiState = uiState.copy(
                asrErrorMessage = "当前ASR来自LLM复用，无法在此页直接切换语言。请先在“设置”中添加独立ASR配置。",
                asrStatusMessage = null
            )
            return
        }

        val oldList = uiState.asrProviders
        val index = oldList.indexOfFirst { it.id == selectedId }
        if (index < 0) {
            uiState = uiState.copy(
                asrErrorMessage = "当前ASR服务不存在，无法切换语言。",
                asrStatusMessage = null
            )
            return
        }

        val current = oldList[index]
        if (current.preferredLanguage == language) {
            uiState = uiState.copy(
                asrStatusMessage = "识别语言已是：${language.title}",
                asrErrorMessage = null
            )
            return
        }

        val nextList = oldList.toMutableList()
        nextList[index] = current.copy(preferredLanguage = language)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                asrConfigRepository.saveConfigs(nextList)
                asrConfigRepository.saveSelectedProviderId(selectedId)
            }
            uiState = uiState.copy(
                asrProviders = nextList,
                asrStatusMessage = "识别语言已切换为：${language.title}",
                asrErrorMessage = null
            )
        }
    }

    fun removeAsrProvider(providerId: String) {
        val nextList = uiState.asrProviders.filterNot { it.id == providerId }
        val nextSelected = when {
            uiState.selectedAsrProviderId == providerId -> nextList.firstOrNull()?.id
            else -> uiState.selectedAsrProviderId
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                asrConfigRepository.saveConfigs(nextList)
                asrConfigRepository.saveSelectedProviderId(nextSelected)
            }
            uiState = uiState.copy(
                asrProviders = nextList,
                selectedAsrProviderId = nextSelected,
                asrStatusMessage = "已删除 ASR 配置",
                asrErrorMessage = null
            )
        }
    }

    fun rewriteTranscriptWithAi() {
        if (uiState.isAiRewriting) return

        val sourceText = uiState.transcriptText?.trim().orEmpty()
        if (sourceText.isBlank()) {
            uiState = uiState.copy(
                aiRewriteErrorMessage = "请先导出文案，再执行 AI重构。",
                aiRewriteDiagnostics = buildAiRewriteDiagnostics(
                    phase = "AI重构前校验失败",
                    provider = null,
                    sourceLength = 0,
                    error = IllegalArgumentException("文案为空")
                )
            )
            return
        }

        val provider = uiState.llmProviders.firstOrNull { it.id == uiState.selectedLlmProviderId }
        if (provider == null) {
            uiState = uiState.copy(
                aiRewriteErrorMessage = "请先在设置中添加并选择一个 LLM 服务。",
                aiRewriteDiagnostics = buildAiRewriteDiagnostics(
                    phase = "AI重构前校验失败",
                    provider = null,
                    sourceLength = sourceText.length,
                    error = IllegalStateException("未选择 LLM 服务")
                )
            )
            return
        }

        viewModelScope.launch {
            uiState = uiState.copy(
                isAiRewriting = true,
                aiRewrittenText = null,
                aiRewriteErrorMessage = null,
                aiRewriteDiagnostics = buildAiRewriteDiagnostics(
                    phase = "开始AI重构",
                    provider = provider,
                    sourceLength = sourceText.length
                )
            )

            runCatching {
                llmRewriteRepository.rewriteTranscript(
                    config = provider,
                    transcriptText = sourceText
                )
            }.onSuccess { rewritten ->
                uiState = uiState.copy(
                    isAiRewriting = false,
                    aiRewrittenText = rewritten,
                    aiRewriteErrorMessage = null,
                    aiRewriteDiagnostics = buildAiRewriteDiagnostics(
                        phase = "AI重构成功",
                        provider = provider,
                        sourceLength = sourceText.length,
                        extra = listOf("重构后长度 = ${rewritten.length}")
                    )
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    isAiRewriting = false,
                    aiRewriteErrorMessage = error.message ?: "AI重构失败，请稍后重试。",
                    aiRewriteDiagnostics = buildAiRewriteDiagnostics(
                        phase = "AI重构失败",
                        provider = provider,
                        sourceLength = sourceText.length,
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

    override fun onCleared() {
        releaseAudioPlayer()
        super.onCleared()
    }

    private fun resetAudioPlaybackState(releasePlayer: Boolean) {
        if (releasePlayer) {
            releaseAudioPlayer()
        }
        uiState = uiState.copy(
            isAudioControlVisible = false,
            isAudioPreparing = false,
            isAudioPlaying = false,
            isAudioPaused = false,
            audioStatusMessage = null,
            audioErrorMessage = null
        )
    }

    private fun releaseAudioPlayer() {
        val player = audioPlayer
        if (player != null) {
            runCatching {
                player.setOnPreparedListener(null)
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
                player.stop()
            }
            player.release()
        }
        audioPlayer = null
        audioPlayerSourceUri = null
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

    private suspend fun loadDedupFeatureConfig() {
        val config = withContext(Dispatchers.IO) {
            dedupFeatureConfigRepository.loadConfig()
        }
        val coverUri = config.coverImageCachePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
            ?.let { Uri.fromFile(it) }
        val endingUri = config.endingMediaUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
        val endingType = if (endingUri == null) DedupEndingType.NONE else config.endingType
        val introMode = if (coverUri == null) DedupIntroCoverMode.NONE else config.introCoverMode
        uiState = uiState.copy(
            dedupCoverImageUri = coverUri,
            dedupCoverImageName = config.coverImageName
                ?: coverUri?.lastPathSegment
                ?: coverUri?.toString(),
            dedupCoverOverlayWarning = null,
            dedupIntroCoverMode = introMode,
            dedupIntroFrameCountDraft = config.introFrameCount.toString(),
            dedupEndingType = endingType,
            dedupEndingMediaUri = endingUri,
            dedupEndingMediaName = config.endingMediaName,
            dedupEndingImageDurationMsDraft = config.endingImageDurationMs.toString()
        )
        if (introMode == DedupIntroCoverMode.OVERLAY_FRAMES && coverUri != null) {
            val overlayValidation = inspectOverlayCoverMaterial(coverUri, uiState.dedupCoverImageName)
            uiState = uiState.copy(
                dedupCoverOverlayWarning = overlayValidation.warningMessage
            )
        }
    }

    private fun persistDedupFeatureConfigAsync() {
        val snapshot = uiState
        viewModelScope.launch(Dispatchers.IO) {
            val coverPath = snapshot.dedupCoverImageUri
                ?.takeIf { it.scheme == "file" }
                ?.path
                ?.takeIf { it.isNotBlank() }
            val endingUri = snapshot.dedupEndingMediaUri?.toString()
            val config = DedupFeatureConfig(
                coverImageCachePath = coverPath,
                coverImageName = snapshot.dedupCoverImageName,
                introCoverMode = snapshot.dedupIntroCoverMode,
                introFrameCount = snapshot.dedupIntroFrameCountDraft.trim()
                    .toIntOrNull()
                    ?.coerceIn(1, 60)
                    ?: 12,
                endingType = if (endingUri.isNullOrBlank()) DedupEndingType.NONE else snapshot.dedupEndingType,
                endingMediaUri = endingUri,
                endingMediaName = snapshot.dedupEndingMediaName,
                endingImageDurationMs = snapshot.dedupEndingImageDurationMsDraft.trim()
                    .toIntOrNull()
                    ?.coerceIn(300, 10_000)
                    ?: 1200
            )
            dedupFeatureConfigRepository.saveConfig(config)
        }
    }

    private suspend fun cacheCoverImageToInternal(sourceUri: Uri): Uri = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val sourceName = resolveDisplayName(sourceUri)
        val extension = sourceName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "bmp") }
            ?: "jpg"
        val assetDir = File(app.filesDir, "dedup-assets")
        if (!assetDir.exists() && !assetDir.mkdirs()) {
            throw IllegalStateException("无法创建封面缓存目录")
        }
        val targetFile = File(assetDir, "cover_cached.$extension")
        resolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
                output.flush()
            }
        } ?: throw IllegalStateException("无法读取所选封面图片")
        Uri.fromFile(targetFile)
    }

    private fun deleteCachedCoverIfOwned(uri: Uri?, exceptPath: String?) {
        val path = uri
            ?.takeIf { it.scheme == "file" }
            ?.path
            ?.takeIf { it.startsWith(getApplication<Application>().filesDir.absolutePath) }
            ?: return
        if (!exceptPath.isNullOrBlank() && path == exceptPath) return
        runCatching {
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    private suspend fun resolveDisplayName(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                val value = cursor.getString(index)
                if (!value.isNullOrBlank()) return@withContext value
            }
        }
        uri.lastPathSegment ?: uri.toString()
    }

    private suspend fun inspectOverlayCoverMaterial(
        coverUri: Uri,
        coverName: String?
    ): OverlayCoverValidation = withContext(Dispatchers.IO) {
        val candidateName = coverName
            ?.takeIf { it.isNotBlank() }
            ?: coverUri.lastPathSegment
            ?: ""
        val extension = candidateName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isPngExtension = extension == "png"

        var bitmap: Bitmap? = null
        try {
            bitmap = getApplication<Application>().contentResolver.openInputStream(coverUri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return@withContext OverlayCoverValidation(
                isPngExtension = isPngExtension,
                hasAlphaChannel = null,
                hasTransparentPixels = null,
                hasVisiblePixels = null,
                decodeError = "无法解码图片"
            )

            val hasAlphaChannel = bitmap.hasAlpha()
            val (hasTransparentPixels, hasVisiblePixels) = if (hasAlphaChannel) {
                inspectBitmapAlpha(bitmap)
            } else {
                false to true
            }
            OverlayCoverValidation(
                isPngExtension = isPngExtension,
                hasAlphaChannel = hasAlphaChannel,
                hasTransparentPixels = hasTransparentPixels,
                hasVisiblePixels = hasVisiblePixels
            )
        } catch (error: Exception) {
            OverlayCoverValidation(
                isPngExtension = isPngExtension,
                hasAlphaChannel = null,
                hasTransparentPixels = null,
                hasVisiblePixels = null,
                decodeError = error.message ?: "未知错误"
            )
        } finally {
            runCatching { bitmap?.recycle() }
        }
    }

    private fun inspectBitmapAlpha(bitmap: Bitmap): Pair<Boolean, Boolean> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false to false

        val stepX = (width / 96).coerceAtLeast(1)
        val stepY = (height / 96).coerceAtLeast(1)
        var hasTransparentPixel = false
        var hasVisiblePixel = false
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                if (alpha < 255) hasTransparentPixel = true
                if (alpha > 0) hasVisiblePixel = true
                if (hasTransparentPixel && hasVisiblePixel) {
                    return true to true
                }
                x += stepX
            }
            y += stepY
        }

        val lastAlpha = (bitmap.getPixel(width - 1, height - 1) ushr 24) and 0xFF
        if (lastAlpha < 255) hasTransparentPixel = true
        if (lastAlpha > 0) hasVisiblePixel = true
        return hasTransparentPixel to hasVisiblePixel
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

    private fun parseDedupInt(
        raw: String,
        fieldName: String,
        range: IntRange
    ): Int? {
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null || parsed !in range) {
            uiState = uiState.copy(
                dedupErrorMessage = "$fieldName 参数无效，请输入 ${range.first}~${range.last} 的整数。",
                dedupDiagnostics = buildDedupDiagnostics(
                    phase = "去重前校验失败",
                    videoUri = uiState.selectedDedupVideoUri?.toString(),
                    videoName = uiState.selectedDedupVideoName,
                    hasStorageWritePermission = true,
                    error = IllegalArgumentException("$fieldName 超出范围: $raw"),
                    extra = listOf("参数范围 = ${range.first}..${range.last}", "当前输入 = $raw")
                )
            )
            return null
        }
        return parsed
    }

    private fun buildDedupDiagnostics(
        phase: String,
        videoUri: String?,
        videoName: String?,
        hasStorageWritePermission: Boolean = true,
        error: Throwable? = null,
        extra: List<String> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        lines += "【视频去重诊断日志】"
        lines += "阶段 = $phase"
        lines += "时间戳 = $now"
        lines += "视频名称 = ${videoName ?: "(未选择)"}"
        lines += "视频URI = ${videoUri ?: "(未选择)"}"
        lines += "写入权限标志 = $hasStorageWritePermission"
        lines += "模板草稿 = ${DedupPresetTemplate.fromName(uiState.dedupPresetName).title}"
        lines += "速度微调草稿(%) = ${uiState.dedupSpeedPercentDraft}"
        lines += "起始裁剪草稿(ms) = ${uiState.dedupTrimStartMsDraft}"
        lines += "结尾裁剪草稿(ms) = ${uiState.dedupTrimEndMsDraft}"
        lines += "时间微扰草稿(ms) = ${uiState.dedupPtsJitterMsDraft}"
        lines += "随机裁剪抖动草稿(ms) = ${uiState.dedupRandomTrimJitterMsDraft}"
        lines += "随机种子草稿(seed) = ${uiState.dedupSeedDraft.ifBlank { "(自动生成)" }}"
        lines += "封面图片 = ${uiState.dedupCoverImageName ?: "(未设置)"}"
        lines += "封面图片URI = ${uiState.dedupCoverImageUri?.toString() ?: "(未设置)"}"
        lines += "覆盖素材警告 = ${uiState.dedupCoverOverlayWarning ?: "(无)"}"
        lines += "片头模式 = ${uiState.dedupIntroCoverMode.title}"
        lines += "片头帧数草稿 = ${uiState.dedupIntroFrameCountDraft}"
        lines += "片尾类型 = ${uiState.dedupEndingType.title}"
        lines += "片尾素材 = ${uiState.dedupEndingMediaName ?: "(未设置)"}"
        lines += "片尾素材URI = ${uiState.dedupEndingMediaUri?.toString() ?: "(未设置)"}"
        lines += "图片片尾时长草稿(ms) = ${uiState.dedupEndingImageDurationMsDraft}"
        lines += "输出前缀草稿 = ${uiState.dedupOutputPrefixDraft}"
        lines += "随机后缀 = ${uiState.dedupRandomSuffixEnabled}"
        lines += "轨道顺序扰动 = ${uiState.dedupShuffleTrackOrderEnabled}"

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

    private fun buildTranscriptDiagnostics(
        phase: String,
        videoUri: String?,
        videoName: String?,
        error: Throwable? = null,
        extra: List<String> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val currentAsr = resolveEffectiveAsrProvider()
        val lines = mutableListOf<String>()
        lines += "【文案提取诊断日志】"
        lines += "阶段 = $phase"
        lines += "时间戳 = $now"
        lines += "视频名称 = ${videoName ?: "(未选择)"}"
        lines += "视频URI = ${videoUri ?: "(未选择)"}"
        lines += "麦克风权限依赖 = 不需要（当前仅云端ASR）"
        lines += "ASR服务(当前) = ${currentAsr?.displayName ?: "(未配置)"}"
        lines += "ASR手动语种 = ${currentAsr?.preferredLanguage?.title ?: AsrLanguageOption.AUTO.title}"
        lines += "ASR配置来源 = ${describeEffectiveAsrSource()}"

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

    private fun buildAiRewriteDiagnostics(
        phase: String,
        provider: LlmProviderConfig?,
        sourceLength: Int,
        error: Throwable? = null,
        extra: List<String> = emptyList()
    ): String {
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        lines += "【AI重构诊断日志】"
        lines += "阶段 = $phase"
        lines += "时间戳 = $now"
        lines += "服务 = ${provider?.displayName ?: "(未选择)"}"
        lines += "服务类型 = ${provider?.providerType?.title ?: "(未选择)"}"
        lines += "模型 = ${provider?.model?.ifBlank { "(未配置)" } ?: "(未选择)"}"
        lines += "System Prompt长度 = ${provider?.systemPrompt?.trim()?.length ?: 0}"
        lines += "源文案长度 = $sourceLength"

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
