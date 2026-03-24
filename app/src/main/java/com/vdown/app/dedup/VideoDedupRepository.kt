package com.vdown.app.dedup

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

private const val DOWNLOAD_RELATIVE_PATH = "DCIM/v-down"
private const val BUFFER_SIZE = 2 * 1024 * 1024
private const val MIN_VALID_OUTPUT_DURATION_US = 100_000L
private const val DEDUP_JITTER_PERIOD_US = 2_000_000.0
private const val OUTPUT_DURATION_GATE_MIN_RATIO = 0.85
private const val OUTPUT_DURATION_GATE_MAX_RATIO = 1.15
private const val INTRO_FRAME_MIN = 1
private const val INTRO_FRAME_MAX = 60
private const val INTRO_FPS_FALLBACK = 30.0
private const val ENDING_IMAGE_DURATION_MIN_MS = 300
private const val ENDING_IMAGE_DURATION_MAX_MS = 10_000
private const val DEDUP_REPO_LOG_TAG = "VDownDedupRepo"

private data class DedupTraceContext(
    val traceId: String,
    val startMs: Long,
    var step: Int = 0
)

private data class VideoLayout(
    val rawWidth: Int,
    val rawHeight: Int,
    val rotationDegrees: Int,
    val displayWidth: Int,
    val displayHeight: Int
)

data class VideoDedupRequest(
    val sourceVideoUri: Uri,
    val sourceVideoName: String?,
    val presetName: String,
    val speedPercent: Int,
    val trimStartMs: Int,
    val trimEndMs: Int,
    val ptsJitterMs: Int,
    val randomTrimJitterMs: Int,
    val shuffleTrackOrder: Boolean,
    val outputPrefix: String,
    val randomSuffixEnabled: Boolean,
    val seed: Long?,
    val coverImageUri: Uri?,
    val introCoverMode: DedupIntroCoverMode,
    val introFrameCount: Int,
    val endingType: DedupEndingType,
    val endingMediaUri: Uri?,
    val endingImageDurationMs: Int
)

data class VideoDedupResult(
    val outputUri: Uri,
    val outputName: String,
    val bytesWritten: Long,
    val durationUs: Long,
    val actualSeed: Long,
    val startTrimExtraMs: Int,
    val endTrimExtraMs: Int,
    val sourceMd5: String,
    val outputMd5: String,
    val coverApplied: Boolean,
    val introModeApplied: String,
    val sourceFrameRate: Double,
    val introFrameCountApplied: Int,
    val overlayApplied: Boolean,
    val endingApplied: String,
    val strategySummary: String
)

class VideoDedupRepository {
    suspend fun dedupVideo(
        context: Context,
        request: VideoDedupRequest,
        hasStorageWritePermission: Boolean,
        onProgress: suspend (Int) -> Unit
    ): VideoDedupResult = withContext(Dispatchers.IO) {
        val trace = newTraceContext(request)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasStorageWritePermission) {
            throw IllegalStateException("未授予文件写入权限，无法保存去重视频")
        }
        logStep(trace, "权限校验通过", "sdk=${Build.VERSION.SDK_INT} hasStorageWritePermission=$hasStorageWritePermission")

        val presetName = request.presetName.ifBlank { "自定义" }
        val speedPercent = request.speedPercent.coerceIn(95, 105)
        val randomTrimJitterMs = request.randomTrimJitterMs.coerceIn(0, 600)
        val ptsJitterMs = request.ptsJitterMs.coerceIn(0, 40)
        val introCoverMode = request.introCoverMode
        val introFrameCount = request.introFrameCount.coerceIn(INTRO_FRAME_MIN, INTRO_FRAME_MAX)
        val endingImageDurationMs = request.endingImageDurationMs
            .coerceIn(ENDING_IMAGE_DURATION_MIN_MS, ENDING_IMAGE_DURATION_MAX_MS)
        val jitterSeed = request.seed ?: System.currentTimeMillis()
        val random = Random(jitterSeed)
        val startExtraMs = if (randomTrimJitterMs > 0) {
            random.nextInt(randomTrimJitterMs + 1)
        } else {
            0
        }
        val endExtraMs = if (randomTrimJitterMs > 0) {
            random.nextInt(randomTrimJitterMs + 1)
        } else {
            0
        }
        var trimStartMs = request.trimStartMs.coerceAtLeast(0) + startExtraMs
        var trimEndMs = request.trimEndMs.coerceAtLeast(0) + endExtraMs
        val speedScale = speedPercent / 100.0
        logStep(
            trace,
            "参数归一化完成",
            "preset=$presetName speed=$speedPercent trimStart=$trimStartMs trimEnd=$trimEndMs ptsJitterMs=$ptsJitterMs randomTrimJitterMs=$randomTrimJitterMs seed=$jitterSeed"
        )
        val sourceMd5 = calculateUriMd5(context, request.sourceVideoUri)
        val sourceFrameRate = resolveSourceFrameRate(context, request.sourceVideoUri).coerceAtLeast(1.0)
        logStep(
            trace,
            "输入媒体分析完成",
            "sourceMd5=${sourceMd5.take(8)}... sourceFps=${String.format(Locale.US, "%.2f", sourceFrameRate)}"
        )

        val sourceDurationMs = resolveSourceDurationMs(context, request.sourceVideoUri)
        if (sourceDurationMs > 0L) {
            val maxTotalTrimMs = (sourceDurationMs - 200L).coerceAtLeast(0L).toInt()
            val currentTotalTrim = trimStartMs + trimEndMs
            if (currentTotalTrim > maxTotalTrimMs) {
                var overflow = currentTotalTrim - maxTotalTrimMs
                val endDeduction = minOf(trimEndMs, overflow)
                trimEndMs -= endDeduction
                overflow -= endDeduction
                if (overflow > 0) {
                    trimStartMs = (trimStartMs - overflow).coerceAtLeast(0)
                }
            }
        }
        logStep(
            trace,
            "时长与裁剪校准完成",
            "sourceDurationMs=$sourceDurationMs adjustedTrimStartMs=$trimStartMs adjustedTrimEndMs=$trimEndMs"
        )

        val trimStartUs = trimStartMs * 1_000L
        val trimEndUs = trimEndMs * 1_000L
        val ptsJitterUs = ptsJitterMs * 1_000L
        val trimStopUs = if (sourceDurationMs > 0L) {
            (sourceDurationMs * 1_000L - trimEndUs).coerceAtLeast(trimStartUs + MIN_VALID_OUTPUT_DURATION_US)
        } else {
            Long.MAX_VALUE
        }
        if (trimStopUs <= trimStartUs) {
            throw IllegalStateException("去重失败：裁剪参数不合法，可用时长不足")
        }
        logStep(trace, "时间窗口计算完成", "trimStartUs=$trimStartUs trimStopUs=$trimStopUs ptsJitterUs=$ptsJitterUs")

        val tempOutput = File.createTempFile("vdown_dedup_", ".mp4", context.cacheDir)
        val tempComposedOutput = File.createTempFile("vdown_dedup_compose_", ".mp4", context.cacheDir)
        var tempEndingVideoFile: File? = null
        var muxer: MediaMuxer? = null
        var extractorForTracks: MediaExtractor? = null
        var targetUri: Uri? = null
        logStep(
            trace,
            "临时文件创建完成",
            "tempOutput=${tempOutput.absolutePath} tempComposedOutput=${tempComposedOutput.absolutePath}"
        )

        try {
            extractorForTracks = MediaExtractor().apply {
                setDataSource(context, request.sourceVideoUri, emptyMap())
            }
            val trackCount = extractorForTracks.trackCount
            logStep(trace, "读取输入轨道信息", "trackCount=$trackCount")
            if (trackCount <= 0) {
                throw IllegalStateException("去重失败：未读取到视频轨道")
            }

            val trackMap = mutableMapOf<Int, Int>()
            val selectedTrackIndices = mutableListOf<Int>()
            var primaryVideoTrack = -1
            muxer = MediaMuxer(tempOutput.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (trackIndex in 0 until trackCount) {
                val format = extractorForTracks.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val outputTrack = muxer.addTrack(format)
                    trackMap[trackIndex] = outputTrack
                    selectedTrackIndices += trackIndex
                    if (primaryVideoTrack < 0 && mime.startsWith("video/")) {
                        primaryVideoTrack = trackIndex
                    }
                }
            }
            logStep(
                trace,
                "输出轨道映射完成",
                "selectedTracks=${selectedTrackIndices.joinToString()} primaryVideoTrack=$primaryVideoTrack outputTrackCount=${trackMap.size}"
            )

            if (primaryVideoTrack < 0) {
                throw IllegalStateException("去重失败：当前文件不包含可处理的视频轨")
            }
            if (trackMap.isEmpty()) {
                throw IllegalStateException("去重失败：没有可重封装的音视频轨")
            }

            muxer.start()
            val activeMuxer = requireNotNull(muxer)
            var lastProgress = -1
            val progressRangeUs = if (trimStopUs == Long.MAX_VALUE) -1L else (trimStopUs - trimStartUs).coerceAtLeast(1L)
            val baseTrackOrder = selectedTrackIndices.sorted()
            val trackOrder = if (request.shuffleTrackOrder && baseTrackOrder.size > 1) {
                baseTrackOrder.shuffled(random)
            } else {
                baseTrackOrder
            }
            logStep(trace, "开始重封装写入", "trackOrder=${trackOrder.joinToString()} progressRangeUs=$progressRangeUs")

            trackOrder.forEach { inputTrack ->
                val outputTrack = trackMap.getValue(inputTrack)
                val extractor = MediaExtractor()
                var sampleCount = 0
                var firstPtsUs = -1L
                var lastPtsUs = -1L
                try {
                    extractor.setDataSource(context, request.sourceVideoUri, emptyMap())
                    extractor.selectTrack(inputTrack)
                    if (trimStartUs > 0L) {
                        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }
                    logStep(trace, "轨道开始处理", "inputTrack=$inputTrack outputTrack=$outputTrack trimStartUs=$trimStartUs")

                    val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    val info = MediaCodec.BufferInfo()
                    var lastWrittenPtsUs = -1L

                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L) {
                            extractor.advance()
                            continue
                        }
                        if (sampleTimeUs < trimStartUs) {
                            extractor.advance()
                            continue
                        }
                        if (trimStopUs != Long.MAX_VALUE && sampleTimeUs > trimStopUs) {
                            break
                        }

                        val normalizedUs = (sampleTimeUs - trimStartUs).coerceAtLeast(0L)
                        val basePtsUs = (normalizedUs / speedScale).roundToLong()
                        val jitterOffsetUs = if (ptsJitterUs > 0L) {
                            val radians = (normalizedUs / DEDUP_JITTER_PERIOD_US) * (2.0 * Math.PI)
                            (sin(radians) * ptsJitterUs).roundToLong()
                        } else {
                            0L
                        }
                        var outputPtsUs = basePtsUs + jitterOffsetUs
                        if (outputPtsUs <= lastWrittenPtsUs) {
                            outputPtsUs = lastWrittenPtsUs + 1L
                        }

                        info.presentationTimeUs = outputPtsUs
                        info.flags = extractor.sampleFlags
                        buffer.position(0)
                        buffer.limit(info.size)
                        activeMuxer.writeSampleData(outputTrack, buffer, info)
                        lastWrittenPtsUs = outputPtsUs
                        sampleCount += 1
                        if (firstPtsUs < 0L) firstPtsUs = outputPtsUs
                        lastPtsUs = outputPtsUs

                        if (inputTrack == primaryVideoTrack && progressRangeUs > 0L) {
                            val progress = ((normalizedUs * 100L) / progressRangeUs).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                        extractor.advance()
                    }
                    logStep(
                        trace,
                        "轨道处理完成",
                        "inputTrack=$inputTrack samples=$sampleCount firstPtsUs=$firstPtsUs lastPtsUs=$lastPtsUs"
                    )
                } finally {
                    runCatching { extractor.release() }
                }
            }

            runCatching {
                activeMuxer.stop()
            }.onFailure {
                throw IllegalStateException("去重失败：封装输出失败（${it.message ?: "未知错误"}）")
            }
            activeMuxer.release()
            muxer = null
            logStep(trace, "重封装完成", "tempOutputBytes=${tempOutput.length()}")

            val expectedDurationUs = if (trimStopUs == Long.MAX_VALUE) {
                if (sourceDurationMs > 0L) ((sourceDurationMs * 1_000L - trimEndUs - trimStartUs) / speedScale).roundToLong()
                else -1L
            } else {
                ((trimStopUs - trimStartUs) / speedScale).roundToLong()
            }.coerceAtLeast(0L)

            val actualOutputDurationMs = resolveFileDurationMs(tempOutput.absolutePath)
            if (expectedDurationUs > 0L && actualOutputDurationMs > 0L) {
                val expectedDurationMs = expectedDurationUs / 1_000.0
                val ratio = actualOutputDurationMs / expectedDurationMs
                if (ratio < OUTPUT_DURATION_GATE_MIN_RATIO || ratio > OUTPUT_DURATION_GATE_MAX_RATIO) {
                    throw IllegalStateException(
                        "去重失败：质量门禁未通过，输出时长异常（预期约${expectedDurationMs.toLong()}ms，实际${actualOutputDurationMs}ms）。"
                    )
                }
            }
            logStep(
                trace,
                "质量门禁校验完成",
                "expectedDurationUs=$expectedDurationUs actualOutputDurationMs=$actualOutputDurationMs"
            )

            val normalizedEndingMediaUri = if (request.endingType == DedupEndingType.VIDEO && request.endingMediaUri != null) {
                tempEndingVideoFile = copyUriToTempFile(
                    context = context,
                    sourceUri = request.endingMediaUri,
                    prefix = "vdown_ending_",
                    suffix = ".mp4",
                    trace = trace
                )
                Uri.fromFile(tempEndingVideoFile)
            } else {
                request.endingMediaUri
            }
            logStep(
                trace,
                "合成输入准备完成",
                "introMode=${introCoverMode.name} introFrames=$introFrameCount fps=${String.format(Locale.US, "%.2f", sourceFrameRate)} " +
                    "coverUri=${describeUri(request.coverImageUri)} endingType=${request.endingType.name} " +
                    "endingIn=${describeUri(request.endingMediaUri)} endingNormalized=${describeUri(normalizedEndingMediaUri)} " +
                    "endingTemp=${tempEndingVideoFile?.absolutePath ?: "N/A"}"
            )

            val shouldApplyCover = request.coverImageUri != null && introCoverMode != DedupIntroCoverMode.NONE
            val introDurationMs = if (shouldApplyCover) {
                ceil((introFrameCount * 1000.0) / sourceFrameRate).toLong().coerceAtLeast(1L)
            } else {
                0L
            }
            val shouldApplyEnding = request.endingType != DedupEndingType.NONE && normalizedEndingMediaUri != null
            val overlayApplied = shouldApplyCover && introCoverMode == DedupIntroCoverMode.OVERLAY_FRAMES

            val finalOutputFile = if (shouldApplyCover || shouldApplyEnding) {
                onProgress(99)
                logStep(trace, "进入合成阶段", "shouldApplyCover=$shouldApplyCover shouldApplyEnding=$shouldApplyEnding overlayApplied=$overlayApplied")
                runCatching {
                    composeIntroAndEnding(
                        context = context,
                        sourceVideoFile = tempOutput,
                        outputFile = tempComposedOutput,
                        coverImageUri = request.coverImageUri,
                        introCoverMode = introCoverMode,
                        introFrameCount = introFrameCount,
                        introDurationMs = introDurationMs,
                        sourceFrameRate = sourceFrameRate,
                        endingType = request.endingType,
                        endingMediaUri = normalizedEndingMediaUri,
                        endingImageDurationMs = endingImageDurationMs,
                        trace = trace
                    )
                }.recoverCatching { firstError ->
                    val fallbackEligible = introCoverMode == DedupIntroCoverMode.INSERT_FRAMES &&
                        request.coverImageUri != null &&
                        request.endingType == DedupEndingType.VIDEO &&
                        normalizedEndingMediaUri != null
                    if (!fallbackEligible) throw firstError

                    logStep(trace, "触发合成兜底", "reason=${firstError.message} mode=${introCoverMode.name} ending=${request.endingType.name}")
                    val tempIntroOnly = File.createTempFile("vdown_intro_only_", ".mp4", context.cacheDir)
                    try {
                        composeIntroAndEnding(
                            context = context,
                            sourceVideoFile = tempOutput,
                            outputFile = tempIntroOnly,
                            coverImageUri = request.coverImageUri,
                            introCoverMode = DedupIntroCoverMode.INSERT_FRAMES,
                            introFrameCount = introFrameCount,
                            introDurationMs = introDurationMs,
                            sourceFrameRate = sourceFrameRate,
                            endingType = DedupEndingType.NONE,
                            endingMediaUri = null,
                            endingImageDurationMs = endingImageDurationMs,
                            trace = trace
                        )
                        logStep(trace, "兜底步骤1完成", "introOnlyFile=${tempIntroOnly.absolutePath} bytes=${tempIntroOnly.length()}")
                        composeVideoConcat(
                            context = context,
                            firstVideoUri = Uri.fromFile(tempIntroOnly),
                            secondVideoUri = requireNotNull(normalizedEndingMediaUri),
                            outputFile = tempComposedOutput,
                            trace = trace
                        )
                        logStep(trace, "兜底步骤2完成", "concatOutput=${tempComposedOutput.absolutePath} bytes=${tempComposedOutput.length()}")
                    } finally {
                        runCatching { tempIntroOnly.delete() }
                    }
                }.getOrThrow()
                logStep(trace, "合成阶段完成", "tempComposedOutputBytes=${tempComposedOutput.length()}")
                tempComposedOutput
            } else {
                logStep(trace, "跳过合成阶段", "onlyRemux=true")
                tempOutput
            }

            val outputBytes = finalOutputFile.length()
            if (outputBytes <= 0L) {
                throw IllegalStateException("去重失败：输出文件为空")
            }
            val outputMd5 = calculateFileMd5(finalOutputFile)
            if (sourceMd5.equals(outputMd5, ignoreCase = true)) {
                throw IllegalStateException("去重失败：输出文件与原视频哈希一致，未产生有效差异，请调整参数后重试。")
            }
            logStep(trace, "输出哈希校验完成", "sourceMd5=${sourceMd5.take(8)}... outputMd5=${outputMd5.take(8)}...")

            val displayName = buildOutputName(
                sourceName = request.sourceVideoName,
                prefix = request.outputPrefix,
                randomSuffixEnabled = request.randomSuffixEnabled
            )
            targetUri = createTargetUri(context, displayName)
            copyFileToUri(context, finalOutputFile, targetUri)
            finalizePendingIfNeeded(context, targetUri)
            onProgress(100)
            logStep(trace, "写入系统相册完成", "displayName=$displayName targetUri=$targetUri bytes=$outputBytes")

            val outputDurationUs = resolveFileDurationMs(finalOutputFile.absolutePath)
                .takeIf { it > 0L }
                ?.times(1_000L)
                ?: expectedDurationUs

            VideoDedupResult(
                outputUri = targetUri,
                outputName = displayName,
                bytesWritten = outputBytes,
                durationUs = outputDurationUs,
                actualSeed = jitterSeed,
                startTrimExtraMs = startExtraMs,
                endTrimExtraMs = endExtraMs,
                sourceMd5 = sourceMd5,
                outputMd5 = outputMd5,
                coverApplied = shouldApplyCover,
                introModeApplied = if (shouldApplyCover) introCoverMode.title else DedupIntroCoverMode.NONE.title,
                sourceFrameRate = sourceFrameRate,
                introFrameCountApplied = if (shouldApplyCover) introFrameCount else 0,
                overlayApplied = overlayApplied,
                endingApplied = when {
                    shouldApplyEnding && request.endingType == DedupEndingType.IMAGE -> "图片"
                    shouldApplyEnding && request.endingType == DedupEndingType.VIDEO -> "视频"
                    else -> "无"
                },
                strategySummary = "模板=${presetName}, 速度=${speedPercent}%, 起始裁剪=${trimStartMs}ms, 结尾裁剪=${trimEndMs}ms, " +
                    "时间微扰=${ptsJitterMs}ms, 随机裁剪抖动=${randomTrimJitterMs}ms, 起始附加=${startExtraMs}ms, 结尾附加=${endExtraMs}ms, " +
                    "轨道扰动=${request.shuffleTrackOrder}, Seed=${jitterSeed}, 片头模式=${if (shouldApplyCover) introCoverMode.title else DedupIntroCoverMode.NONE.title}, " +
                    "片头帧数=${if (shouldApplyCover) introFrameCount else 0}, 源帧率=${String.format(Locale.US, "%.2f", sourceFrameRate)}fps, 覆盖=${if (overlayApplied) "是" else "否"}, " +
                    "片尾=${if (shouldApplyEnding) request.endingType.title else "无"}," +
                    " 片尾URI方案=${normalizedEndingMediaUri?.scheme ?: "N/A"}, 源MD5=${sourceMd5.take(8)}..., 输出MD5=${outputMd5.take(8)}..., 重封装=开启"
            ).also {
                logStep(trace, "去重流程成功结束", "outputName=${it.outputName} outputDurationUs=${it.durationUs}")
            }
        } catch (error: Exception) {
            logStepError(trace, "去重流程失败", error)
            targetUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            throw error
        } finally {
            logStep(
                trace,
                "资源清理",
                "releaseExtractor=${extractorForTracks != null} releaseMuxer=${muxer != null} " +
                    "deleteTempOutput=${tempOutput.exists()} deleteTempComposed=${tempComposedOutput.exists()} deleteEndingTemp=${tempEndingVideoFile?.exists() == true}"
            )
            runCatching { extractorForTracks?.release() }
            runCatching { muxer?.release() }
            runCatching { tempOutput.delete() }
            runCatching { tempComposedOutput.delete() }
            runCatching { tempEndingVideoFile?.delete() }
        }
    }

    private fun resolveSourceDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveFileDurationMs(filePath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveSourceFrameRate(context: Context, uri: Uri): Double {
        runCatching {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, emptyMap())
            try {
                for (index in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith("video/")) continue
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                        if (frameRate > 0) return frameRate
                    }
                }
            } finally {
                runCatching { extractor.release() }
            }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: INTRO_FPS_FALLBACK
        } catch (_: Exception) {
            INTRO_FPS_FALLBACK
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveVideoLayout(filePath: String): VideoLayout {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 720
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 1280
            val rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?.let { ((it % 360) + 360) % 360 }
                ?: 0
            val swapped = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (swapped) rawHeight else rawWidth
            val displayHeight = if (swapped) rawWidth else rawHeight
            VideoLayout(
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                rotationDegrees = rotationDegrees,
                displayWidth = displayWidth.coerceAtLeast(1),
                displayHeight = displayHeight.coerceAtLeast(1)
            )
        } catch (_: Exception) {
            VideoLayout(
                rawWidth = 720,
                rawHeight = 1280,
                rotationDegrees = 0,
                displayWidth = 720,
                displayHeight = 1280
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun normalizeImageToVideoLayout(
        context: Context,
        sourceImageUri: Uri,
        targetWidth: Int,
        targetHeight: Int,
        prefix: String,
        trace: DedupTraceContext
    ): Uri {
        val bitmap = decodeCoverBitmap(
            context = context,
            imageUri = sourceImageUri,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        val tempFile = File.createTempFile(prefix, ".png", context.cacheDir)
        return try {
            tempFile.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalStateException("去重失败：图片归一化写入失败")
                }
                output.flush()
            }
            logStep(
                trace,
                "图片素材归一化完成",
                "source=${describeUri(sourceImageUri)} target=${tempFile.absolutePath} targetSize=${targetWidth}x${targetHeight}"
            )
            Uri.fromFile(tempFile)
        } catch (error: Exception) {
            runCatching { tempFile.delete() }
            throw error
        } finally {
            runCatching { bitmap.recycle() }
        }
    }

    private fun decodeCoverBitmap(
        context: Context,
        imageUri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val sourceBitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalStateException("去重失败：无法解码封面图片")

        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        return if (sourceBitmap.width == safeWidth && sourceBitmap.height == safeHeight) {
            sourceBitmap
        } else {
            Bitmap.createScaledBitmap(sourceBitmap, safeWidth, safeHeight, true).also {
                if (it !== sourceBitmap) {
                    sourceBitmap.recycle()
                }
            }
        }
    }

    private fun copyUriToTempFile(
        context: Context,
        sourceUri: Uri,
        prefix: String,
        suffix: String,
        trace: DedupTraceContext
    ): File {
        val target = File.createTempFile(prefix, suffix, context.cacheDir)
        return try {
            logStep(
                trace,
                "片尾素材复制开始",
                "source=${describeUri(sourceUri)} target=${target.absolutePath}"
            )
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.flush()
                }
            } ?: throw IllegalStateException("去重失败：无法读取片尾视频素材")
            logStep(
                trace,
                "片尾素材复制完成",
                "bytes=${target.length()} target=${target.absolutePath}"
            )
            target
        } catch (error: Exception) {
            logStepError(trace, "片尾素材复制失败", error)
            runCatching { target.delete() }
            throw error
        }
    }

    private suspend fun composeIntroAndEnding(
        context: Context,
        sourceVideoFile: File,
        outputFile: File,
        coverImageUri: Uri?,
        introCoverMode: DedupIntroCoverMode,
        introFrameCount: Int,
        introDurationMs: Long,
        sourceFrameRate: Double,
        endingType: DedupEndingType,
        endingMediaUri: Uri?,
        endingImageDurationMs: Int,
        trace: DedupTraceContext
    ) {
        var normalizedIntroImageUri: Uri? = null
        var normalizedEndingImageUri: Uri? = null
        var normalizedIntroImageFile: File? = null
        var normalizedEndingImageFile: File? = null
        try {
            logStep(
                trace,
                "片头片尾合成准备",
                "source=${sourceVideoFile.absolutePath} output=${outputFile.absolutePath} introMode=${introCoverMode.name} " +
                    "introFrames=$introFrameCount introDurationMs=$introDurationMs sourceFps=${String.format(Locale.US, "%.2f", sourceFrameRate)} " +
                    "endingType=${endingType.name} endingUri=${describeUri(endingMediaUri)} coverUri=${describeUri(coverImageUri)}"
            )
            val sourceLayout = resolveVideoLayout(sourceVideoFile.absolutePath)
            logStep(
                trace,
                "源视频布局解析完成",
                "raw=${sourceLayout.rawWidth}x${sourceLayout.rawHeight} rotation=${sourceLayout.rotationDegrees} " +
                    "display=${sourceLayout.displayWidth}x${sourceLayout.displayHeight}"
            )
            if (coverImageUri != null && introCoverMode == DedupIntroCoverMode.INSERT_FRAMES) {
                normalizedIntroImageUri = normalizeImageToVideoLayout(
                    context = context,
                    sourceImageUri = coverImageUri,
                    targetWidth = sourceLayout.displayWidth,
                    targetHeight = sourceLayout.displayHeight,
                    prefix = "vdown_intro_norm_",
                    trace = trace
                )
                normalizedIntroImageFile = normalizedIntroImageUri.path?.let(::File)
            }
            if (endingType == DedupEndingType.IMAGE && endingMediaUri != null) {
                normalizedEndingImageUri = normalizeImageToVideoLayout(
                    context = context,
                    sourceImageUri = endingMediaUri,
                    targetWidth = sourceLayout.displayWidth,
                    targetHeight = sourceLayout.displayHeight,
                    prefix = "vdown_ending_norm_",
                    trace = trace
                )
                normalizedEndingImageFile = normalizedEndingImageUri.path?.let(::File)
            }
            val sourceMediaItem = MediaItem.fromUri(Uri.fromFile(sourceVideoFile))
            val sequenceItems = mutableListOf<EditedMediaItem>()
            val introDurationUs = ceil((introFrameCount * 1_000_000.0) / sourceFrameRate).toLong().coerceAtLeast(1L)
            val frameRateInt = sourceFrameRate.roundToLong().toInt().coerceIn(1, 240)
            var overlayFirstPtsUs = -1L
            var overlayLastPtsUs = -1L
            var overlayCallbackCount = 0L
            var overlayVisibleCount = 0L

            val sourceEditedItem = when {
                coverImageUri != null && introCoverMode == DedupIntroCoverMode.OVERLAY_FRAMES -> {
                    val coverBitmap = decodeCoverBitmap(
                        context = context,
                        imageUri = coverImageUri,
                        targetWidth = sourceLayout.displayWidth,
                        targetHeight = sourceLayout.displayHeight
                    )
                    val visibleSettings = OverlaySettings.Builder()
                        .setAlphaScale(1f)
                        .build()
                    val hiddenSettings = OverlaySettings.Builder()
                        .setAlphaScale(0f)
                        .build()
                    val timedOverlay = object : BitmapOverlay() {
                        override fun getBitmap(presentationTimeUs: Long): Bitmap {
                            return coverBitmap
                        }

                        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                            overlayCallbackCount += 1L
                            if (overlayFirstPtsUs < 0L) {
                                overlayFirstPtsUs = presentationTimeUs
                            }
                            overlayLastPtsUs = presentationTimeUs
                            val relativePtsUs = (presentationTimeUs - overlayFirstPtsUs).coerceAtLeast(0L)
                            val visible = relativePtsUs < introDurationUs
                            if (visible) {
                                overlayVisibleCount += 1L
                            }
                            return if (visible) visibleSettings else hiddenSettings
                        }
                    }
                    val overlayEffect = OverlayEffect(listOf(timedOverlay))
                    val effects = Effects(emptyList(), listOf(overlayEffect))
                    EditedMediaItem.Builder(sourceMediaItem)
                        .setEffects(effects)
                        .build()
                }

                else -> EditedMediaItem.Builder(sourceMediaItem).build()
            }

            if (coverImageUri != null && introCoverMode == DedupIntroCoverMode.INSERT_FRAMES) {
                val introItem = MediaItem.Builder()
                    .setUri(normalizedIntroImageUri ?: coverImageUri)
                    .setImageDurationMs(introDurationMs)
                    .build()
                sequenceItems += EditedMediaItem.Builder(introItem)
                    .setFrameRate(frameRateInt)
                    .build()
            }

            sequenceItems += sourceEditedItem

            when {
                endingType == DedupEndingType.IMAGE && endingMediaUri != null -> {
                    val endingImageItem = MediaItem.Builder()
                        .setUri(normalizedEndingImageUri ?: endingMediaUri)
                        .setImageDurationMs(endingImageDurationMs.toLong())
                        .build()
                    sequenceItems += EditedMediaItem.Builder(endingImageItem)
                        .setFrameRate(30)
                        .build()
                }

                endingType == DedupEndingType.VIDEO && endingMediaUri != null -> {
                    val endingVideoItem = MediaItem.fromUri(endingMediaUri)
                    sequenceItems += EditedMediaItem.Builder(endingVideoItem).build()
                }
            }

            val usesImageIntro = coverImageUri != null && introCoverMode == DedupIntroCoverMode.INSERT_FRAMES
            val usesOverlayIntro = coverImageUri != null && introCoverMode == DedupIntroCoverMode.OVERLAY_FRAMES
            val usesImageEnding = endingType == DedupEndingType.IMAGE && endingMediaUri != null
            val usesVideoEnding = endingType == DedupEndingType.VIDEO && endingMediaUri != null
            val hasAnyCompositionEffect = usesImageIntro || usesOverlayIntro || usesImageEnding || usesVideoEnding

            if (sequenceItems.size <= 1 && !hasAnyCompositionEffect) {
                logStep(trace, "片头片尾合成跳过", "sequenceSize=${sequenceItems.size} hasAnyCompositionEffect=$hasAnyCompositionEffect")
                return
            }

            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val requiresReencode = usesImageIntro || usesOverlayIntro || usesImageEnding || usesVideoEnding
                    val overlayWindowRule = if (usesOverlayIntro) {
                        "relativePtsUs < introDurationUs（relativePtsUs=presentationTimeUs-firstPresentationTimeUs）"
                    } else {
                        "N/A"
                    }
                    // Media3 要求：当序列前段是无音轨素材（如图片），后段出现有音轨视频时，需要强制补音轨。
                    val forceAudioTrack = usesImageIntro
                    logStep(
                        trace,
                        "片头片尾合成开始",
                        "sequence=${sequenceItems.size} overlayMode=$usesOverlayIntro introFrameCount=$introFrameCount " +
                            "fps=${String.format(Locale.US, "%.2f", sourceFrameRate)} introDurationUs=$introDurationUs " +
                            "overlayWindowRule=$overlayWindowRule reencode=$requiresReencode forceAudioTrack=$forceAudioTrack"
                    )
                    val compositionBuilder = Composition.Builder(
                        EditedMediaItemSequence.Builder(sequenceItems)
                            .setIsLooping(false)
                            .build()
                    ).setTransmuxVideo(!requiresReencode)
                        .setTransmuxAudio(!requiresReencode)
                    if (forceAudioTrack) {
                        compositionBuilder.experimentalSetForceAudioTrack(true)
                    }
                    val composition = compositionBuilder.build()

                    val transformer = Transformer.Builder(context)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult
                                ) {
                                    val overlayStats = if (usesOverlayIntro) {
                                        " overlayCallbacks=$overlayCallbackCount overlayVisible=$overlayVisibleCount " +
                                            "overlayFirstPtsUs=$overlayFirstPtsUs overlayLastPtsUs=$overlayLastPtsUs"
                                    } else {
                                        ""
                                    }
                                    logStep(
                                        trace,
                                        "片头片尾合成完成",
                                        "durationMs=${exportResult.durationMs} frameCount=${exportResult.videoFrameCount} " +
                                            "avgAudioBitrate=${exportResult.averageAudioBitrate} avgVideoBitrate=${exportResult.averageVideoBitrate} " +
                                            "output=${outputFile.absolutePath}$overlayStats"
                                    )
                                    if (continuation.isActive) {
                                        continuation.resume(Unit)
                                    }
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    logStepError(
                                        trace,
                                        "片头片尾合成失败 code=${exportException.getErrorCodeName()} reencode=$requiresReencode",
                                        exportException
                                    )
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            IllegalStateException(
                                                "去重失败：片头/片尾合成失败（模式=${introCoverMode.title}, 片尾=${endingType.title}, " +
                                                    "重编码=${if (requiresReencode) "开启" else "关闭"}, 错误码=${exportException.getErrorCodeName()}, " +
                                                    "消息=${exportException.message ?: "未知错误"}）",
                                                exportException
                                            )
                                        )
                                    }
                                }
                            }
                        )
                        .build()

                    runCatching {
                        transformer.start(composition, outputFile.absolutePath)
                    }.onFailure { error ->
                        logStepError(
                            trace,
                            "片头片尾合成启动失败 introMode=${introCoverMode.name} endingType=${endingType.name}",
                            error
                        )
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException(
                                    "去重失败：片头/片尾合成启动失败（模式=${introCoverMode.title}, 片尾=${endingType.title}, " +
                                        "重编码=${if (requiresReencode) "开启" else "关闭"}, 消息=${error.message ?: "未知错误"}）",
                                    error
                                )
                            )
                        }
                    }

                    continuation.invokeOnCancellation {
                        logStep(trace, "片头片尾合成取消", "output=${outputFile.absolutePath}")
                        runCatching { transformer.cancel() }
                    }
                }
            }
        } finally {
            runCatching { normalizedIntroImageFile?.delete() }
            runCatching { normalizedEndingImageFile?.delete() }
        }
    }

    private suspend fun composeVideoConcat(
        context: Context,
        firstVideoUri: Uri,
        secondVideoUri: Uri,
        outputFile: File,
        trace: DedupTraceContext
    ) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val sequenceItems = listOf(
                    EditedMediaItem.Builder(MediaItem.fromUri(firstVideoUri)).build(),
                    EditedMediaItem.Builder(MediaItem.fromUri(secondVideoUri)).build()
                )
                logStep(
                    trace,
                    "视频拼接兜底开始",
                    "first=${describeUri(firstVideoUri)} second=${describeUri(secondVideoUri)} output=${outputFile.absolutePath}"
                )
                val composition = Composition.Builder(
                    EditedMediaItemSequence.Builder(sequenceItems)
                        .setIsLooping(false)
                        .build()
                ).setTransmuxVideo(false)
                    .setTransmuxAudio(false)
                    .build()

                val transformer = Transformer.Builder(context)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult
                            ) {
                                logStep(
                                    trace,
                                    "视频拼接兜底完成",
                                    "durationMs=${exportResult.durationMs} videoFrameCount=${exportResult.videoFrameCount} output=${outputFile.absolutePath}"
                                )
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                logStepError(
                                    trace,
                                    "视频拼接兜底失败 code=${exportException.getErrorCodeName()}",
                                    exportException
                                )
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        IllegalStateException(
                                            "去重失败：视频拼接兜底失败（错误码=${exportException.getErrorCodeName()}，消息=${exportException.message ?: "未知错误"}）",
                                            exportException
                                        )
                                    )
                                }
                            }
                        }
                    )
                    .build()

                runCatching {
                    transformer.start(composition, outputFile.absolutePath)
                }.onFailure { error ->
                    logStepError(trace, "视频拼接兜底启动失败", error)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("去重失败：视频拼接兜底启动失败（${error.message ?: "未知错误"}）", error)
                        )
                    }
                }

                continuation.invokeOnCancellation {
                    logStep(trace, "视频拼接兜底取消", "output=${outputFile.absolutePath}")
                    runCatching { transformer.cancel() }
                }
            }
        }
    }

    private fun calculateUriMd5(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("去重失败：无法读取原视频内容（MD5 校验）")
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun calculateFileMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String {
        val out = StringBuilder(size * 2)
        forEach { b ->
            out.append(((b.toInt() ushr 4) and 0xF).toString(16))
            out.append((b.toInt() and 0xF).toString(16))
        }
        return out.toString()
    }

    private fun buildOutputName(
        sourceName: String?,
        prefix: String,
        randomSuffixEnabled: Boolean
    ): String {
        val sourceBase = sourceName
            .orEmpty()
            .substringBeforeLast('.')
            .ifBlank { "video_${System.currentTimeMillis()}" }
            .let(::sanitizeFileNameSegment)

        val safePrefix = prefix.trim().ifBlank { "dedup" }.let(::sanitizeFileNameSegment)
        val randomSuffix = if (randomSuffixEnabled) "_${System.currentTimeMillis().toString().takeLast(6)}" else ""
        return "${safePrefix}_${sourceBase}${randomSuffix}.mp4"
    }

    private fun sanitizeFileNameSegment(raw: String): String {
        return raw
            .replace(Regex("[\\\\/:*?<>|\\s]+"), "_")
            .trim('_')
            .ifBlank { "video" }
    }

    private fun createTargetUri(context: Context, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val targetFile = resolveLegacyTargetFile(displayName)
                put(MediaStore.MediaColumns.DATA, targetFile.absolutePath)
            }
        }
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("去重失败：无法创建输出文件")
    }

    private fun copyFileToUri(context: Context, sourceFile: File, targetUri: Uri) {
        context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
            sourceFile.inputStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } ?: throw IllegalStateException("去重失败：无法写入输出文件")
    }

    private fun describeUri(uri: Uri?): String {
        if (uri == null) return "null"
        return "scheme=${uri.scheme ?: "null"}, uri=$uri"
    }

    private fun newTraceContext(request: VideoDedupRequest): DedupTraceContext {
        val now = System.currentTimeMillis()
        val traceId = "DD-${now.toString().takeLast(6)}"
        Log.i(
            DEDUP_REPO_LOG_TAG,
            "[$traceId][step=00][+0ms] start request source=${describeUri(request.sourceVideoUri)} " +
                "name=${request.sourceVideoName ?: "N/A"} preset=${request.presetName} mode=${request.introCoverMode.name} " +
                "ending=${request.endingType.name}"
        )
        return DedupTraceContext(traceId = traceId, startMs = now)
    }

    private fun logStep(trace: DedupTraceContext, title: String, detail: String) {
        trace.step += 1
        val elapsed = (System.currentTimeMillis() - trace.startMs).coerceAtLeast(0L)
        val stepText = trace.step.toString().padStart(2, '0')
        Log.i(DEDUP_REPO_LOG_TAG, "[${trace.traceId}][step=$stepText][+${elapsed}ms] $title | $detail")
    }

    private fun logStepError(trace: DedupTraceContext, title: String, error: Throwable) {
        trace.step += 1
        val elapsed = (System.currentTimeMillis() - trace.startMs).coerceAtLeast(0L)
        val stepText = trace.step.toString().padStart(2, '0')
        Log.e(
            DEDUP_REPO_LOG_TAG,
            "[${trace.traceId}][step=$stepText][+${elapsed}ms] $title | ${error::class.java.simpleName}: ${error.message}",
            error
        )
    }

    private fun finalizePendingIfNeeded(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun resolveLegacyTargetFile(displayName: String): File {
        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val targetDir = File(dcimDir, "v-down")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("去重失败：无法创建 v-down 目录")
        }
        val dot = displayName.lastIndexOf('.')
        val name = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var candidate = File(targetDir, displayName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "${name}_$index$ext")
            index += 1
        }
        return candidate
    }
}
