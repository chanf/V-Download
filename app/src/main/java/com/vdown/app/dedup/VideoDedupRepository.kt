package com.vdown.app.dedup

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToLong

private const val DOWNLOAD_RELATIVE_PATH = "DCIM/v-down"
private const val BUFFER_SIZE = 2 * 1024 * 1024
private const val MIN_VALID_OUTPUT_DURATION_US = 100_000L

data class VideoDedupRequest(
    val sourceVideoUri: Uri,
    val sourceVideoName: String?,
    val speedPercent: Int,
    val trimStartMs: Int,
    val trimEndMs: Int,
    val outputPrefix: String,
    val randomSuffixEnabled: Boolean
)

data class VideoDedupResult(
    val outputUri: Uri,
    val outputName: String,
    val bytesWritten: Long,
    val durationUs: Long,
    val strategySummary: String
)

class VideoDedupRepository {
    suspend fun dedupVideo(
        context: Context,
        request: VideoDedupRequest,
        hasStorageWritePermission: Boolean,
        onProgress: suspend (Int) -> Unit
    ): VideoDedupResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasStorageWritePermission) {
            throw IllegalStateException("未授予文件写入权限，无法保存去重视频")
        }

        val speedPercent = request.speedPercent.coerceIn(98, 102)
        val trimStartMs = request.trimStartMs.coerceAtLeast(0)
        val trimEndMs = request.trimEndMs.coerceAtLeast(0)
        val speedScale = speedPercent / 100.0

        val sourceDurationMs = resolveSourceDurationMs(context, request.sourceVideoUri)
        val trimStartUs = trimStartMs * 1_000L
        val trimEndUs = trimEndMs * 1_000L
        val trimStopUs = if (sourceDurationMs > 0L) {
            (sourceDurationMs * 1_000L - trimEndUs).coerceAtLeast(trimStartUs + MIN_VALID_OUTPUT_DURATION_US)
        } else {
            Long.MAX_VALUE
        }
        if (trimStopUs <= trimStartUs) {
            throw IllegalStateException("去重失败：裁剪参数不合法，可用时长不足")
        }

        val tempOutput = File.createTempFile("vdown_dedup_", ".mp4", context.cacheDir)
        var muxer: MediaMuxer? = null
        var extractorForTracks: MediaExtractor? = null
        var targetUri: Uri? = null

        try {
            extractorForTracks = MediaExtractor().apply {
                setDataSource(context, request.sourceVideoUri, emptyMap())
            }
            val trackCount = extractorForTracks.trackCount
            if (trackCount <= 0) {
                throw IllegalStateException("去重失败：未读取到视频轨道")
            }

            val trackMap = mutableMapOf<Int, Int>()
            var primaryVideoTrack = -1
            muxer = MediaMuxer(tempOutput.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (trackIndex in 0 until trackCount) {
                val format = extractorForTracks.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val outputTrack = muxer.addTrack(format)
                    trackMap[trackIndex] = outputTrack
                    if (primaryVideoTrack < 0 && mime.startsWith("video/")) {
                        primaryVideoTrack = trackIndex
                    }
                }
            }

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

            trackMap.keys.sorted().forEach { inputTrack ->
                val outputTrack = trackMap.getValue(inputTrack)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, request.sourceVideoUri, emptyMap())
                    extractor.selectTrack(inputTrack)
                    if (trimStartUs > 0L) {
                        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }

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
                        var outputPtsUs = (normalizedUs / speedScale).roundToLong()
                        if (outputPtsUs <= lastWrittenPtsUs) {
                            outputPtsUs = lastWrittenPtsUs + 1L
                        }

                        info.presentationTimeUs = outputPtsUs
                        info.flags = extractor.sampleFlags
                        buffer.position(0)
                        buffer.limit(info.size)
                        activeMuxer.writeSampleData(outputTrack, buffer, info)
                        lastWrittenPtsUs = outputPtsUs

                        if (inputTrack == primaryVideoTrack && progressRangeUs > 0L) {
                            val progress = ((normalizedUs * 100L) / progressRangeUs).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                        extractor.advance()
                    }
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

            val outputBytes = tempOutput.length()
            if (outputBytes <= 0L) {
                throw IllegalStateException("去重失败：输出文件为空")
            }

            val displayName = buildOutputName(
                sourceName = request.sourceVideoName,
                prefix = request.outputPrefix,
                randomSuffixEnabled = request.randomSuffixEnabled
            )
            targetUri = createTargetUri(context, displayName)
            copyFileToUri(context, tempOutput, targetUri)
            finalizePendingIfNeeded(context, targetUri)
            onProgress(100)

            val outputDurationUs = if (trimStopUs == Long.MAX_VALUE) {
                if (sourceDurationMs > 0L) ((sourceDurationMs * 1_000L - trimEndUs - trimStartUs) / speedScale).roundToLong()
                else -1L
            } else {
                ((trimStopUs - trimStartUs) / speedScale).roundToLong()
            }.coerceAtLeast(0L)

            VideoDedupResult(
                outputUri = targetUri,
                outputName = displayName,
                bytesWritten = outputBytes,
                durationUs = outputDurationUs,
                strategySummary = "速度=${speedPercent}%, 起始裁剪=${trimStartMs}ms, 结尾裁剪=${trimEndMs}ms, 重封装=开启"
            )
        } catch (error: Exception) {
            targetUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            throw error
        } finally {
            runCatching { extractorForTracks?.release() }
            runCatching { muxer?.release() }
            runCatching { tempOutput.delete() }
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
