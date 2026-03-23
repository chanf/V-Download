package com.vdown.app.transcript

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume

private const val DOWNLOAD_RELATIVE_PATH = "DCIM/v-down"
private const val LEGACY_DOWNLOAD_RELATIVE_PATH = "Movies/v-down"
private const val MEDIA_TIMEOUT_US = 10_000L
private const val SPEECH_TIMEOUT_MIN_MS = 180_000L
private const val SPEECH_TIMEOUT_MAX_MS = 1_200_000L
private const val TRANSCRIBE_TARGET_SAMPLE_RATE = 16_000
private const val TRANSCRIBE_TARGET_CHANNEL_COUNT = 1
private const val TRANSCRIPT_FAST_MODE_MAX_MS = 90_000L

data class TranscriptVideoCandidate(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long
)

data class DecodedPcmAudio(
    val file: File,
    val uri: Uri,
    val sampleRate: Int,
    val channelCount: Int,
    val pcmEncoding: Int,
    val bytesWritten: Long,
    val durationMs: Long,
    val wasDurationClipped: Boolean,
    val clipLimitMs: Long?
)

private data class PcmResampleState(
    var phaseAccumulator: Long = 0L,
    var trailingBytes: ByteArray = ByteArray(0)
)

class VideoTranscriptRepository {
    suspend fun queryLatestDownloadedVideo(context: Context): TranscriptVideoCandidate? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED
            )

            val selection: String
            val selectionArgs: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection =
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf(
                    "$DOWNLOAD_RELATIVE_PATH%",
                    "$LEGACY_DOWNLOAD_RELATIVE_PATH%"
                )
            } else {
                selection =
                    "${MediaStore.Video.Media.DATA} LIKE ? OR ${MediaStore.Video.Media.DATA} LIKE ?"
                selectionArgs = arrayOf(
                    "%/DCIM/v-down/%",
                    "%/Movies/v-down/%"
                )
            }

            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null

                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)).orEmpty()
                val size = cursor.getLongOrNull(MediaStore.Video.Media.SIZE) ?: -1L
                val duration = cursor.getLongOrNull(MediaStore.Video.Media.DURATION) ?: -1L

                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                return@withContext TranscriptVideoCandidate(
                    uri = uri,
                    displayName = name.ifBlank { "video_$id" },
                    durationMs = duration,
                    sizeBytes = size
                )
            }

            null
        }

    suspend fun resolveVideoDisplayName(context: Context, videoUri: Uri): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            resolver.query(
                videoUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val value = cursor.getString(nameIndex)
                        if (!value.isNullOrBlank()) {
                            return@withContext value
                        }
                    }
                }
            }
            videoUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "selected_video"
        }

    suspend fun decodeVideoAudioToPcm(
        context: Context,
        videoUri: Uri,
        maxDurationMs: Long = TRANSCRIPT_FAST_MODE_MAX_MS
    ): DecodedPcmAudio =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, videoUri, emptyMap())

                var audioTrackIndex = -1
                var audioInputFormat: MediaFormat? = null
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = track
                        audioInputFormat = format
                        break
                    }
                }

                if (audioTrackIndex < 0 || audioInputFormat == null) {
                    throw IllegalStateException("导出文案失败：该视频未检测到可用音轨")
                }

                val audioMime = audioInputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                if (audioMime.isBlank()) {
                    throw IllegalStateException("导出文案失败：无法识别音轨编码格式")
                }

                val targetFile = createPcmFile(context)
                extractor.selectTrack(audioTrackIndex)

                val decoder = MediaCodec.createDecoderByType(audioMime)
                var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
                var outputSampleRate = audioInputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 16_000)
                var outputChannelCount = audioInputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
                val resampleState = PcmResampleState()
                var totalBytes = 0L
                var durationClipped = false
                val normalizedMaxDurationMs = maxDurationMs.coerceAtLeast(0L)
                val maxDurationUs = if (normalizedMaxDurationMs > 0L) {
                    normalizedMaxDurationMs.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
                } else {
                    Long.MAX_VALUE
                }

                try {
                    decoder.configure(audioInputFormat, null, null, 0)
                    decoder.start()

                    FileOutputStream(targetFile).use { outputStream ->
                        val bufferInfo = MediaCodec.BufferInfo()
                        var inputDone = false
                        var outputDone = false

                        while (!outputDone) {
                            if (!inputDone) {
                                val inputBufferIndex = decoder.dequeueInputBuffer(MEDIA_TIMEOUT_US)
                                if (inputBufferIndex >= 0) {
                                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                        ?: throw IllegalStateException("音频解码输入缓冲区不可用")
                                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                    if (sampleSize < 0) {
                                        decoder.queueInputBuffer(
                                            inputBufferIndex,
                                            0,
                                            0,
                                            0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputDone = true
                                    } else {
                                        val presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                                        if (presentationTimeUs >= maxDurationUs) {
                                            durationClipped = true
                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                0,
                                                0,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            )
                                            inputDone = true
                                        } else {
                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                sampleSize,
                                                presentationTimeUs,
                                                0
                                            )
                                            extractor.advance()
                                        }
                                    }
                                }
                            }

                            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, MEDIA_TIMEOUT_US)
                            when {
                                outputBufferIndex >= 0 -> {
                                    if (bufferInfo.size > 0) {
                                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                                        if (outputBuffer != null) {
                                            outputBuffer.position(bufferInfo.offset)
                                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                            val chunk = ByteArray(bufferInfo.size)
                                            outputBuffer.get(chunk)

                                            val converted = convertPcmIfNeeded(chunk, outputEncoding)
                                            val normalized = normalizeToTargetPcm(
                                                pcm16Bytes = converted,
                                                sourceSampleRate = outputSampleRate.coerceAtLeast(8_000),
                                                sourceChannelCount = outputChannelCount.coerceAtLeast(1),
                                                state = resampleState
                                            )
                                            if (normalized.isNotEmpty()) {
                                                outputStream.write(normalized)
                                                totalBytes += normalized.size
                                            }
                                        }
                                    }

                                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outputDone = true
                                    }
                                }

                                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val outputFormat = decoder.outputFormat
                                    outputSampleRate = outputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                                    outputChannelCount = outputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannelCount)
                                    outputEncoding = outputFormat.getIntOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                                }
                            }
                        }
                    }
                } finally {
                    runCatching { decoder.stop() }
                    runCatching { decoder.release() }
                }

                if (totalBytes <= 0L) {
                    throw IllegalStateException("导出文案失败：音频分离后未得到有效 PCM 数据")
                }

                DecodedPcmAudio(
                    file = targetFile,
                    uri = targetFile.toUri(),
                    sampleRate = TRANSCRIBE_TARGET_SAMPLE_RATE,
                    channelCount = TRANSCRIBE_TARGET_CHANNEL_COUNT,
                    pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
                    bytesWritten = totalBytes,
                    durationMs = estimatePcmDurationMs(
                        bytes = totalBytes,
                        sampleRate = TRANSCRIBE_TARGET_SAMPLE_RATE,
                        channelCount = TRANSCRIBE_TARGET_CHANNEL_COUNT
                    ),
                    wasDurationClipped = durationClipped,
                    clipLimitMs = normalizedMaxDurationMs.takeIf { it > 0L }
                )
            } finally {
                extractor.release()
            }
        }

    suspend fun transcribePcmAudio(
        context: Context,
        pcmAudio: DecodedPcmAudio,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ): String = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException("导出文案失败：当前设备未检测到可用的语音识别服务")
        }

        val timeoutMs = resolveSpeechTimeoutMs(pcmAudio.durationMs)

        runCatching {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    val audioFd = ParcelFileDescriptor.open(pcmAudio.file, ParcelFileDescriptor.MODE_READ_ONLY)

                    fun finish(result: Result<String>) {
                        runCatching { audioFd.close() }
                        runCatching { recognizer.destroy() }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit

                        override fun onBeginningOfSpeech() = Unit

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() = Unit

                        override fun onError(error: Int) {
                            val readable = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "语音服务音频输入异常"
                                SpeechRecognizer.ERROR_CLIENT -> "语音服务客户端异常"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限（RECORD_AUDIO）"
                                SpeechRecognizer.ERROR_NETWORK -> "语音服务网络错误"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络超时"
                                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到有效语音内容"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务忙，请稍后重试"
                                SpeechRecognizer.ERROR_SERVER -> "语音服务端异常"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音识别超时"
                                else -> "语音识别失败（错误码=$error）"
                            }
                            finish(Result.failure(IllegalStateException("导出文案失败：$readable")))
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            if (text.isBlank()) {
                                finish(Result.failure(IllegalStateException("导出文案失败：语音识别返回空结果")))
                            } else {
                                finish(Result.success(text))
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    continuation.invokeOnCancellation {
                        runCatching { audioFd.close() }
                        runCatching { recognizer.cancel() }
                        runCatching { recognizer.destroy() }
                    }

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioFd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, pcmAudio.channelCount)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, pcmAudio.sampleRate)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, pcmAudio.pcmEncoding)
                    }

                    runCatching {
                        recognizer.startListening(intent)
                    }.onFailure { error ->
                        finish(Result.failure(IllegalStateException("导出文案失败：无法启动语音识别 - ${error.message}", error)))
                    }
                }.getOrThrow()
            }
        }.getOrElse { error ->
            if (error is TimeoutCancellationException) {
                val seconds = timeoutMs / 1000
                throw IllegalStateException(
                    "导出文案失败：语音识别等待超时（${seconds}s）。可能是音频较长或当前语音服务不支持文件流识别，请更换更短视频或稍后重试。",
                    error
                )
            }
            throw error
        }
    }

    suspend fun exportPcmToWav(
        context: Context,
        pcmAudio: DecodedPcmAudio
    ): File = withContext(Dispatchers.IO) {
        if (!pcmAudio.file.exists() || pcmAudio.file.length() <= 0L) {
            throw IllegalStateException("导出文案失败：PCM 缓存文件不存在或为空")
        }

        val wavFile = createWavFile(context)
        val audioDataLength = pcmAudio.file.length()
        val channelCount = pcmAudio.channelCount.coerceAtLeast(1)
        val sampleRate = pcmAudio.sampleRate.coerceAtLeast(8_000)
        val bitsPerSample = 16
        val byteRate = sampleRate * channelCount * bitsPerSample / 8

        FileOutputStream(wavFile).use { output ->
            output.write(
                buildWavHeader(
                    audioDataLength = audioDataLength,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    byteRate = byteRate,
                    bitsPerSample = bitsPerSample
                )
            )
            FileInputStream(pcmAudio.file).use { input ->
                input.copyTo(output)
            }
            output.flush()
        }

        wavFile
    }

    private fun createPcmFile(context: Context): File {
        val dir = File(context.cacheDir, "transcript")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("导出文案失败：无法创建音频缓存目录")
        }

        val file = File(dir, "audio_${System.currentTimeMillis()}.pcm")
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("导出文案失败：无法覆盖旧音频缓存文件")
        }
        return file
    }

    private fun createWavFile(context: Context): File {
        val dir = File(context.cacheDir, "transcript")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("导出文案失败：无法创建音频缓存目录")
        }

        val file = File(dir, "audio_${System.currentTimeMillis()}.wav")
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("导出文案失败：无法覆盖旧 WAV 缓存文件")
        }
        return file
    }

    private fun buildWavHeader(
        audioDataLength: Long,
        sampleRate: Int,
        channelCount: Int,
        byteRate: Int,
        bitsPerSample: Int
    ): ByteArray {
        val totalDataLength = audioDataLength + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLength.toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channelCount * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(audioDataLength.toInt())
        return header.array()
    }

    private fun convertPcmIfNeeded(bytes: ByteArray, pcmEncoding: Int): ByteArray {
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> bytes
            AudioFormat.ENCODING_PCM_FLOAT -> convertFloatToPcm16(bytes)
            AudioFormat.ENCODING_PCM_8BIT -> convertPcm8ToPcm16(bytes)
            else -> bytes
        }
    }

    private fun convertFloatToPcm16(source: ByteArray): ByteArray {
        val floatBuffer = ByteBuffer.wrap(source)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val shortBuffer = ShortArray(floatBuffer.remaining())
        for (index in shortBuffer.indices) {
            val sample = floatBuffer.get(index).coerceIn(-1f, 1f)
            shortBuffer[index] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
        val out = ByteBuffer.allocate(shortBuffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        shortBuffer.forEach { out.putShort(it) }
        return out.array()
    }

    private fun convertPcm8ToPcm16(source: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(source.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        source.forEach { value ->
            val normalized = (value.toInt() and 0xFF) - 128
            out.putShort((normalized shl 8).toShort())
        }
        return out.array()
    }

    private fun normalizeToTargetPcm(
        pcm16Bytes: ByteArray,
        sourceSampleRate: Int,
        sourceChannelCount: Int,
        state: PcmResampleState
    ): ByteArray {
        if (pcm16Bytes.isEmpty()) return ByteArray(0)
        if (sourceChannelCount <= 0 || sourceSampleRate <= 0) return ByteArray(0)

        val frameSizeBytes = sourceChannelCount * 2
        val combined = if (state.trailingBytes.isNotEmpty()) {
            state.trailingBytes + pcm16Bytes
        } else {
            pcm16Bytes
        }
        if (combined.isEmpty()) return ByteArray(0)

        val usableBytes = combined.size - (combined.size % frameSizeBytes)
        if (usableBytes <= 0) {
            state.trailingBytes = combined
            return ByteArray(0)
        }
        state.trailingBytes = if (usableBytes < combined.size) {
            combined.copyOfRange(usableBytes, combined.size)
        } else {
            ByteArray(0)
        }

        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < usableBytes) {
            var monoSum = 0
            var channel = 0
            while (channel < sourceChannelCount) {
                val low = combined[offset + channel * 2].toInt() and 0xFF
                val high = combined[offset + channel * 2 + 1].toInt()
                val sample = (high shl 8) or low
                monoSum += sample.toShort().toInt()
                channel += 1
            }
            val monoSample = (monoSum / sourceChannelCount).toShort()

            if (sourceSampleRate == TRANSCRIBE_TARGET_SAMPLE_RATE) {
                writeShortLittleEndian(output, monoSample)
            } else {
                state.phaseAccumulator += TRANSCRIBE_TARGET_SAMPLE_RATE.toLong()
                while (state.phaseAccumulator >= sourceSampleRate.toLong()) {
                    writeShortLittleEndian(output, monoSample)
                    state.phaseAccumulator -= sourceSampleRate.toLong()
                }
            }

            offset += frameSizeBytes
        }

        return output.toByteArray()
    }

    private fun writeShortLittleEndian(output: ByteArrayOutputStream, value: Short) {
        output.write(value.toInt() and 0xFF)
        output.write((value.toInt() shr 8) and 0xFF)
    }

    private fun MediaFormat.getIntOrDefault(key: String, defaultValue: Int): Int {
        return runCatching {
            if (containsKey(key)) getInteger(key) else defaultValue
        }.getOrElse { defaultValue }
    }

    private fun Cursor.getLongOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun estimatePcmDurationMs(
        bytes: Long,
        sampleRate: Int,
        channelCount: Int
    ): Long {
        if (bytes <= 0L || sampleRate <= 0 || channelCount <= 0) return 0L
        val bytesPerSecond = sampleRate.toLong() * channelCount.toLong() * 2L
        if (bytesPerSecond <= 0L) return 0L
        return (bytes * 1000L) / bytesPerSecond
    }

    private fun resolveSpeechTimeoutMs(durationMs: Long): Long {
        if (durationMs <= 0L) return SPEECH_TIMEOUT_MIN_MS
        val estimated = durationMs * 2L + 120_000L
        return estimated.coerceIn(SPEECH_TIMEOUT_MIN_MS, SPEECH_TIMEOUT_MAX_MS)
    }
}
