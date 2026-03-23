package com.vdown.app.asr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class AsrTranscriptionRepository {
    suspend fun transcribeAudio(
        config: AsrProviderConfig,
        audioFile: File,
        languageTag: String = Locale.getDefault().toLanguageTag()
    ): String = withContext(Dispatchers.IO) {
        require(audioFile.exists() && audioFile.length() > 0L) { "文案导出失败：待转写音频文件不存在或为空" }
        val effectiveLanguageTag = resolveEffectiveLanguageTag(config.preferredLanguage, languageTag)

        when (config.providerType) {
            AsrProviderType.OPENAI,
            AsrProviderType.OPENAI_COMPATIBLE -> requestOpenAiCompatible(config, audioFile, effectiveLanguageTag)

            AsrProviderType.AZURE_OPENAI -> requestAzureOpenAi(config, audioFile, effectiveLanguageTag)
            AsrProviderType.VOSK_LOCAL -> requestVoskLocal(
                config = config,
                audioFile = audioFile,
                forcedLanguageHint = config.preferredLanguage.voskHint
            )
        }
    }

    private fun requestVoskLocal(
        config: AsrProviderConfig,
        audioFile: File,
        forcedLanguageHint: String?
    ): String {
        val modelPathInput = config.localModelPath.trim()
        require(modelPathInput.isNotBlank()) {
            "文案导出失败：请先配置 Vosk 本地模型目录（localModelPath）"
        }

        val wavInfo = readWavInfo(audioFile)
        require(wavInfo.isValidWav) { "文案导出失败：Vosk 仅支持 WAV 音频（PCM16）" }
        require(wavInfo.channelCount == 1) { "文案导出失败：Vosk 仅支持单声道音频（当前=${wavInfo.channelCount}）" }
        require(wavInfo.bitsPerSample == 16) { "文案导出失败：Vosk 仅支持 PCM16 音频（当前=${wavInfo.bitsPerSample}bit）" }
        require(wavInfo.dataOffset > 0) { "文案导出失败：WAV 数据区无效，无法进行本地识别" }

        val modelDirs = resolveVoskModelDirs(modelPathInput)
        Log.i(
            TAG,
            "Vosk model resolve input=$modelPathInput, preferred=${config.preferredLanguage.name}, discovered=${modelDirs.joinToString { it.name }}"
        )
        require(modelDirs.isNotEmpty()) {
            "文案导出失败：未找到可用的 Vosk 模型目录。请检查路径，或使用分号/换行配置多个模型目录。"
        }

        val selectedModelDir = selectBestVoskModelDir(
            modelDirs = modelDirs,
            audioFile = audioFile,
            wavInfo = wavInfo,
            forcedLanguageHint = forcedLanguageHint,
            preferredLanguage = config.preferredLanguage
        )
        val transcript = transcribeVoskByModelDir(selectedModelDir, audioFile, wavInfo)
        val text = punctuateChineseText(normalizeVoskText(transcript.text))
        Log.i(
            TAG,
            "Vosk final selected=${selectedModelDir.name}, hint=${detectModelLanguageHint(selectedModelDir)}, conf=${formatDouble(transcript.averageConfidence)}, textPreview=${previewText(text)}"
        )

        if (text.isBlank()) {
            throw IllegalStateException(
                "文案导出失败：Vosk 未识别到有效文本。当前模型=$selectedModelDir，请确认模型语种与音频语言匹配。"
            )
        }
        if (shouldRejectByLowConfidence(transcript, modelDirs.size)) {
            val confidence = transcript.averageConfidence?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"
            throw IllegalStateException(
                "文案导出失败：Vosk 识别置信度偏低（$confidence），可能语种不匹配。当前模型=$selectedModelDir。请在设置中配置英文/葡语等模型目录（每行一个）后重试。"
            )
        }
        return text
    }

    private fun requestOpenAiCompatible(
        config: AsrProviderConfig,
        audioFile: File,
        languageTag: String
    ): String {
        val baseUrl = config.baseUrl.ifBlank { defaultAsrBaseUrlFor(config.providerType) }.trim()
        require(baseUrl.isNotBlank()) { "文案导出失败：请先配置 ASR 服务地址（Base URL）" }
        require(config.apiKey.isNotBlank()) { "文案导出失败：请先配置 ASR API Key" }
        require(config.model.isNotBlank()) { "文案导出失败：请先配置 ASR 模型名称（Model）" }

        val endpoint = if (baseUrl.endsWith("/v1", ignoreCase = true)) {
            "$baseUrl/audio/transcriptions"
        } else {
            "${baseUrl.trimEnd('/')}/v1/audio/transcriptions"
        }

        val headers = mutableMapOf(
            "Authorization" to "Bearer ${config.apiKey}",
            "Accept" to "application/json"
        )
        if (config.organization.isNotBlank()) {
            headers["OpenAI-Organization"] = config.organization
        }

        val textParts = linkedMapOf(
            "model" to config.model,
            "response_format" to "json",
            "temperature" to "0"
        )
        resolveIso639_1(languageTag)?.let { textParts["language"] = it }

        val raw = postMultipart(
            endpoint = endpoint,
            headers = headers,
            textParts = textParts,
            fileFieldName = "file",
            file = audioFile,
            fileContentType = "audio/wav"
        )

        val text = extractTranscriptText(raw).trim()
        if (text.isBlank()) {
            throw IllegalStateException("文案导出失败：ASR 返回内容为空")
        }
        return text
    }

    private fun requestAzureOpenAi(
        config: AsrProviderConfig,
        audioFile: File,
        languageTag: String
    ): String {
        val baseUrl = config.baseUrl.ifBlank { defaultAsrBaseUrlFor(config.providerType) }.trim()
        require(baseUrl.isNotBlank()) { "文案导出失败：请先配置 Azure ASR 资源地址" }
        require(config.apiKey.isNotBlank()) { "文案导出失败：请先配置 Azure ASR API Key" }
        val deployment = config.azureDeployment.ifBlank { config.model }.trim()
        require(deployment.isNotBlank()) { "文案导出失败：请先配置 Azure ASR Deployment" }
        val apiVersion = config.azureApiVersion.ifBlank { "2024-10-21" }

        val endpoint = "${baseUrl.trimEnd('/')}/openai/deployments/${urlEncode(deployment)}/audio/transcriptions?api-version=${urlEncode(apiVersion)}"
        val headers = mapOf(
            "api-key" to config.apiKey,
            "Accept" to "application/json"
        )

        val textParts = linkedMapOf(
            "response_format" to "json",
            "temperature" to "0"
        )
        resolveIso639_1(languageTag)?.let { textParts["language"] = it }

        val raw = postMultipart(
            endpoint = endpoint,
            headers = headers,
            textParts = textParts,
            fileFieldName = "file",
            file = audioFile,
            fileContentType = "audio/wav"
        )

        val text = extractTranscriptText(raw).trim()
        if (text.isBlank()) {
            throw IllegalStateException("文案导出失败：Azure ASR 返回内容为空")
        }
        return text
    }

    private fun postMultipart(
        endpoint: String,
        headers: Map<String, String>,
        textParts: Map<String, String>,
        fileFieldName: String,
        file: File,
        fileContentType: String
    ): String {
        val boundary = "----VDownAsr${System.currentTimeMillis()}"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = estimateReadTimeoutMs(file.length())
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        headers.forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }

        return try {
            connection.outputStream.use { output ->
                textParts.forEach { (name, value) ->
                    writeTextPart(output, boundary, name, value)
                }
                writeFilePart(output, boundary, fileFieldName, file, fileContentType)
                output.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }

            val code = connection.responseCode
            val responseBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                val errorMessage = extractRemoteErrorMessage(responseBody)
                throw IllegalStateException("文案导出失败：ASR 请求返回 HTTP $code，$errorMessage")
            }

            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun writeTextPart(
        output: java.io.OutputStream,
        boundary: String,
        name: String,
        value: String
    ) {
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeFilePart(
        output: java.io.OutputStream,
        boundary: String,
        fieldName: String,
        file: File,
        contentType: String
    ) {
        val safeFileName = file.name.replace("\"", "_")
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"")
                .append(fieldName)
                .append("\"; filename=\"")
                .append(safeFileName)
                .append("\"\r\n")
            append("Content-Type: ").append(contentType).append("\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        FileInputStream(file).use { input -> input.copyTo(output) }
        output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun extractTranscriptText(responseBody: String): String {
        if (responseBody.isBlank()) return ""
        return runCatching {
            val payload = JSONObject(responseBody)
            payload.optString("text").ifBlank {
                payload.optJSONObject("result")?.optString("text").orEmpty()
            }
        }.getOrElse { responseBody }
    }

    private fun extractVoskPayload(jsonPayload: String): VoskParsedPayload {
        if (jsonPayload.isBlank()) {
            return VoskParsedPayload(
                text = "",
                confidenceSum = 0.0,
                confidenceCount = 0
            )
        }
        return runCatching {
            val payload = JSONObject(jsonPayload)
            val text = payload.optString("text").trim()
            val words = payload.optJSONArray("result")
            var confSum = 0.0
            var confCount = 0
            if (words != null) {
                for (index in 0 until words.length()) {
                    val item = words.optJSONObject(index) ?: continue
                    val conf = item.optDouble("conf", Double.NaN)
                    if (!conf.isNaN()) {
                        confSum += conf
                        confCount += 1
                    }
                }
            }
            VoskParsedPayload(
                text = text,
                confidenceSum = confSum,
                confidenceCount = confCount
            )
        }.getOrDefault(
            VoskParsedPayload(
                text = "",
                confidenceSum = 0.0,
                confidenceCount = 0
            )
        )
    }

    private fun resolveVoskModelDirs(rawPath: String): List<File> {
        val configuredPaths = rawPath
            .split('\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val discovered = linkedMapOf<String, File>()
        configuredPaths.forEach { singlePath ->
            resolvePathCandidates(singlePath).forEach pathLoop@ { pathFile ->
                if (!pathFile.exists() || !pathFile.isDirectory) return@pathLoop

                if (isVoskModelDir(pathFile)) {
                    discovered[pathFile.absolutePath] = pathFile
                    if (shouldScanSiblingModels(pathFile)) {
                        collectModelDirsRecursively(
                            root = pathFile.parentFile,
                            maxDepth = 2,
                            discovered = discovered
                        )
                    }
                } else {
                    collectModelDirsRecursively(
                        root = pathFile,
                        maxDepth = 2,
                        discovered = discovered
                    )
                }
            }
        }

        // 兜底扫描：即使用户仅填写父目录或历史别名，也能发现模型目录。
        if (discovered.isEmpty()) {
            val fallbackRoots = listOf(
                File("/sdcard/Android/data/com.vdown.app/files/vosk-models"),
                File("/storage/emulated/0/Android/data/com.vdown.app/files/vosk-models")
            )
            fallbackRoots.forEach { root ->
                collectModelDirsRecursively(
                    root = root,
                    maxDepth = 2,
                    discovered = discovered
                )
            }
        }

        return discovered.values.toList()
    }

    private fun resolvePathCandidates(rawPath: String): List<File> {
        val normalized = rawPath.trim().trim('"').trim('\'')
        if (normalized.isBlank()) return emptyList()

        val candidates = linkedSetOf<String>()
        fun addPath(path: String) {
            val value = path.trim().trimEnd('/').ifBlank { path.trim() }
            if (value.isNotBlank()) {
                candidates += value
            }
        }

        addPath(normalized)

        if (normalized.startsWith("/sdcard/")) {
            addPath("/storage/emulated/0/${normalized.removePrefix("/sdcard/")}")
        }
        if (normalized.startsWith("/storage/emulated/0/")) {
            addPath("/sdcard/${normalized.removePrefix("/storage/emulated/0/")}")
        }

        val files = linkedMapOf<String, File>()
        candidates.forEach { path ->
            val file = File(path)
            files[file.absolutePath] = file
            runCatching { file.canonicalFile }
                .onSuccess { canonical -> files[canonical.absolutePath] = canonical }
        }
        return files.values.toList()
    }

    private fun collectModelDirsRecursively(
        root: File?,
        maxDepth: Int,
        discovered: LinkedHashMap<String, File>
    ) {
        if (root == null || !root.exists() || !root.isDirectory || maxDepth < 0) return
        if (isVoskModelDir(root)) {
            discovered.putIfAbsent(root.absolutePath, root)
        }
        if (maxDepth == 0) return

        root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.forEach { child ->
                collectModelDirsRecursively(
                    root = child,
                    maxDepth = maxDepth - 1,
                    discovered = discovered
                )
            }
    }

    private fun shouldScanSiblingModels(modelDir: File): Boolean {
        val sample = modelDir.absolutePath.lowercase(Locale.US)
        if (modelDir.name.equals("vosk", ignoreCase = true)) return true
        if (sample.contains("/android/data/com.vdown.app/files")) return true
        return false
    }

    private fun isVoskModelDir(dir: File): Boolean {
        // Layout A (common): am/final.mdl + conf/*
        val amFinal = File(dir, "am/final.mdl").exists()
        val confDir = File(dir, "conf")
        val hasConfFiles = confDir.isDirectory && !confDir.listFiles().isNullOrEmpty()
        if (amFinal && hasConfFiles) return true

        // Layout B (some small models, e.g. pt): final.mdl + HCLr.fst + mfcc.conf
        val rootFinal = File(dir, "final.mdl").exists()
        val hcl = File(dir, "HCLr.fst").exists()
        val mfcc = File(dir, "mfcc.conf").exists() || File(dir, "conf/mfcc.conf").exists()
        return rootFinal && hcl && mfcc
    }

    private fun selectBestVoskModelDir(
        modelDirs: List<File>,
        audioFile: File,
        wavInfo: WavInfo,
        forcedLanguageHint: String?,
        preferredLanguage: AsrLanguageOption
    ): File {
        val candidateDirs = if (forcedLanguageHint.isNullOrBlank()) {
            modelDirs
        } else {
            modelDirs.filter { detectModelLanguageHint(it) == forcedLanguageHint }
        }
        if (forcedLanguageHint != null && candidateDirs.isEmpty()) {
            throw IllegalStateException(
                "文案导出失败：已手动指定语言为 ${preferredLanguage.title}，但未找到对应 Vosk 模型目录。请导入该语种模型，或改回“自动识别”。"
            )
        }
        if (candidateDirs.size <= 1) return candidateDirs.firstOrNull() ?: modelDirs.first()

        val probeBytes = readVoskProbeBytes(audioFile, wavInfo)
        if (probeBytes.isEmpty()) return candidateDirs.first()

        val probeResults = candidateDirs.map { modelDir ->
            val languageHint = detectModelLanguageHint(modelDir)
            val probeTranscript = runCatching {
                transcribeVoskProbe(modelDir, wavInfo.sampleRate, probeBytes)
            }.getOrDefault(
                VoskTranscriptResult(
                    text = "",
                    averageConfidence = null,
                    confidenceSamples = 0
                )
            )
            val normalized = probeTranscript.copy(
                text = normalizeVoskText(probeTranscript.text)
            )
            val score = scoreVoskProbe(normalized, languageHint)
            val stats = collectProbeTextStats(normalized.text)
            VoskProbeResult(
                modelDir = modelDir,
                languageHint = languageHint,
                score = score,
                transcript = normalized,
                cjkRatio = stats.cjkRatio,
                latinRatio = stats.latinRatio,
                uniqueRatio = stats.uniqueRatio,
                dominantCharRatio = stats.dominantCharRatio
            )
        }

        val sorted = probeResults
            .sortedWith(
                compareByDescending<VoskProbeResult> { it.score }
                    .thenByDescending { it.transcript.text.length }
                    .thenByDescending { it.transcript.averageConfidence ?: -1.0 }
            )
        val best = sorted.firstOrNull()
        val overrideBest = if (forcedLanguageHint == null) {
            chooseLatinOverrideCandidate(probeResults)
        } else {
            null
        }
        val selected = overrideBest ?: best

        probeResults.forEach { result ->
            Log.i(
                TAG,
                "Vosk probe model=${result.modelDir.name}, hint=${result.languageHint}, score=${formatDouble(result.score)}, conf=${formatDouble(result.transcript.averageConfidence)}, len=${result.transcript.text.length}, cjk=${formatDouble(result.cjkRatio)}, latin=${formatDouble(result.latinRatio)}, unique=${formatDouble(result.uniqueRatio)}, topChar=${formatDouble(result.dominantCharRatio)}, text=${previewText(result.transcript.text)}"
            )
        }

        if (overrideBest != null && overrideBest != best) {
            val enBest = probeResults
                .filter { it.languageHint == "en" }
                .maxByOrNull { it.score }
            val ptBest = probeResults
                .filter { it.languageHint == "pt" }
                .maxByOrNull { it.score }
            Log.i(
                TAG,
                "Vosk probe latin override => selected=${overrideBest.modelDir.name}, ptScore=${formatDouble(ptBest?.score)}, enScore=${formatDouble(enBest?.score)}, reason=pt_lexical_evidence"
            )
        }

        selected?.let {
            Log.i(
                TAG,
                "Vosk probe selected=${it.modelDir.name}, hint=${it.languageHint}, score=${formatDouble(it.score)}"
            )
        }

        return selected?.modelDir ?: modelDirs.first()
    }

    private fun chooseLatinOverrideCandidate(
        probeResults: List<VoskProbeResult>
    ): VoskProbeResult? {
        val bestEn = probeResults
            .filter { it.languageHint == "en" && it.latinRatio >= 0.70 }
            .maxByOrNull { it.score }
            ?: return null

        val bestPt = probeResults
            .filter { it.languageHint == "pt" && it.latinRatio >= 0.70 }
            .maxByOrNull { it.score }
            ?: return null

        val ptText = bestPt.transcript.text.lowercase(Locale.US)
        val enText = bestEn.transcript.text.lowercase(Locale.US)
        if (ptText.length < 16) return null

        val ptTokens = latinWordTokens(ptText)
        val ptKeywordHits = ptTokens.count { it in PORTUGUESE_HINT_WORDS }
        val hasPtAccent = ptText.any { it in PORTUGUESE_ACCENT_CHARS }
        val ptEvidence = portugueseEvidenceScore(ptText)
        val enEvidence = englishEvidenceScore(enText)
        val enEvidenceInPt = englishEvidenceScore(ptText)
        val ptEvidenceInEn = portugueseEvidenceScore(enText)

        val ptAdjusted = ptEvidence - enEvidenceInPt
        val enAdjusted = enEvidence - ptEvidenceInEn
        val scoreGap = bestEn.score - bestPt.score

        if (hasPtAccent && ptKeywordHits >= 2 && scoreGap <= 40.0) {
            Log.i(
                TAG,
                "Vosk latin compare enScore=${formatDouble(bestEn.score)}, ptScore=${formatDouble(bestPt.score)}, gap=${formatDouble(scoreGap)}, ptKeywords=$ptKeywordHits, ptAccent=true => prefer_pt_by_accent"
            )
            return bestPt
        }

        Log.i(
            TAG,
            "Vosk latin compare enScore=${formatDouble(bestEn.score)}, ptScore=${formatDouble(bestPt.score)}, gap=${formatDouble(scoreGap)}, ptKeywords=$ptKeywordHits, ptAccent=$hasPtAccent, ptAdjusted=$ptAdjusted, enAdjusted=$enAdjusted"
        )

        val shouldPreferPt = scoreGap <= 40.0 && ptAdjusted >= enAdjusted
        return if (shouldPreferPt) bestPt else null
    }

    private fun portugueseEvidenceScore(text: String): Int {
        val tokens = latinWordTokens(text)
        if (tokens.isEmpty()) return 0
        val hitCount = tokens.count { it in PORTUGUESE_HINT_WORDS }
        val accentCount = text.count { it in PORTUGUESE_ACCENT_CHARS }
        return hitCount * 2 + min(6, accentCount)
    }

    private fun englishEvidenceScore(text: String): Int {
        val tokens = latinWordTokens(text)
        if (tokens.isEmpty()) return 0
        return tokens.count { it in ENGLISH_HINT_WORDS } * 2
    }

    private fun latinWordTokens(text: String): List<String> {
        return Regex("[a-zA-ZÀ-ÿ']+")
            .findAll(text.lowercase(Locale.US))
            .map { it.value.trim('\'') }
            .filter { it.length >= 2 }
            .toList()
    }

    private fun readVoskProbeBytes(
        audioFile: File,
        wavInfo: WavInfo,
        maxProbeMs: Int = 12_000
    ): ByteArray {
        val fileDataBytes = (audioFile.length() - wavInfo.dataOffset).coerceAtLeast(0L)
        if (fileDataBytes <= 0L) return ByteArray(0)

        val bytesPerSecond = wavInfo.sampleRate * wavInfo.channelCount * (wavInfo.bitsPerSample / 8)
        if (bytesPerSecond <= 0) return ByteArray(0)

        val maxProbeBytes = (bytesPerSecond.toLong() * maxProbeMs / 1000L).coerceAtLeast(4096L)
        val targetBytes = min(fileDataBytes, maxProbeBytes).toInt()
        if (targetBytes <= 0) return ByteArray(0)

        FileInputStream(audioFile).use { input ->
            if (wavInfo.dataOffset > 0) {
                input.skip(wavInfo.dataOffset.toLong())
            }
            val buffer = ByteArray(targetBytes)
            var offset = 0
            while (offset < targetBytes) {
                val read = input.read(buffer, offset, targetBytes - offset)
                if (read <= 0) break
                offset += read
            }
            return if (offset == targetBytes) buffer else buffer.copyOf(offset)
        }
    }

    private fun transcribeVoskProbe(
        modelDir: File,
        sampleRate: Int,
        probeBytes: ByteArray
    ): VoskTranscriptResult {
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, sampleRate.toFloat())
            val textParts = mutableListOf<String>()
            var confSum = 0.0
            var confCount = 0
            var offset = 0
            while (offset < probeBytes.size) {
                val size = min(4096, probeBytes.size - offset)
                val chunk = probeBytes.copyOfRange(offset, offset + size)
                val accepted = recognizer.acceptWaveForm(chunk, size)
                if (accepted) {
                    val parsed = extractVoskPayload(recognizer.result.orEmpty())
                    if (parsed.text.isNotBlank()) {
                        textParts += parsed.text
                    }
                    if (parsed.confidenceCount > 0) {
                        confSum += parsed.confidenceSum
                        confCount += parsed.confidenceCount
                    }
                }
                offset += size
            }
            val finalParsed = extractVoskPayload(recognizer.finalResult.orEmpty())
            if (finalParsed.text.isNotBlank()) {
                textParts += finalParsed.text
            }
            if (finalParsed.confidenceCount > 0) {
                confSum += finalParsed.confidenceSum
                confCount += finalParsed.confidenceCount
            }
            return VoskTranscriptResult(
                text = textParts.joinToString(separator = " "),
                averageConfidence = if (confCount > 0) confSum / confCount else null,
                confidenceSamples = confCount
            )
        } finally {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    private fun transcribeVoskByModelDir(
        modelDir: File,
        audioFile: File,
        wavInfo: WavInfo
    ): VoskTranscriptResult {
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, wavInfo.sampleRate.toFloat())
            val textParts = mutableListOf<String>()
            var confSum = 0.0
            var confCount = 0

            FileInputStream(audioFile).use { input ->
                if (wavInfo.dataOffset > 0) {
                    input.skip(wavInfo.dataOffset.toLong())
                }
                val buffer = ByteArray(4096)
                while (true) {
                    val readSize = input.read(buffer)
                    if (readSize <= 0) break
                    val accepted = recognizer.acceptWaveForm(buffer, readSize)
                    if (accepted) {
                        val parsed = extractVoskPayload(recognizer.result.orEmpty())
                        if (parsed.text.isNotBlank()) {
                            textParts += parsed.text
                        }
                        if (parsed.confidenceCount > 0) {
                            confSum += parsed.confidenceSum
                            confCount += parsed.confidenceCount
                        }
                    }
                }
            }

            val finalParsed = extractVoskPayload(recognizer.finalResult.orEmpty())
            if (finalParsed.text.isNotBlank()) {
                textParts += finalParsed.text
            }
            if (finalParsed.confidenceCount > 0) {
                confSum += finalParsed.confidenceSum
                confCount += finalParsed.confidenceCount
            }
            return VoskTranscriptResult(
                text = textParts.joinToString(separator = " "),
                averageConfidence = if (confCount > 0) confSum / confCount else null,
                confidenceSamples = confCount
            )
        } finally {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    private fun scoreVoskProbe(
        transcript: VoskTranscriptResult,
        languageHint: String?
    ): Double {
        val text = transcript.text
        if (text.isBlank()) return -1.0

        val compact = text.replace(" ", "")
        if (compact.isBlank()) return -1.0

        val cjkCount = compact.count { it.code in 0x4E00..0x9FFF }
        val latinCount = compact.count { it.isLetter() && it.code in 0x0041..0x024F }
        val digitCount = compact.count { it.isDigit() }
        val uniqueChars = compact.toSet().size
        val uniqueRatio = uniqueChars.toDouble() / max(1, compact.length)
        val dominantCharRatio = compact
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.value
            ?.toDouble()
            ?.div(max(1, compact.length))
            ?: 0.0

        var score = compact.length.toDouble()
        score += uniqueChars * 0.3
        score += digitCount * 0.1
        transcript.averageConfidence?.let { avg ->
            score += avg * 120.0
            if (avg < 0.45) {
                score -= 20.0
            }
        }

        val cjkRatio = cjkCount.toDouble() / max(1, compact.length)
        val latinRatio = latinCount.toDouble() / max(1, compact.length)

        when (languageHint) {
            "zh" -> score += if (cjkRatio >= 0.40) 18.0 else -10.0
            "en", "pt", "es" -> score += if (latinRatio >= 0.45) 18.0 else -10.0
        }

        if (uniqueRatio < 0.12 && compact.length >= 18) {
            score -= 18.0
        }
        if (dominantCharRatio >= 0.30 && compact.length >= 16) {
            score -= 15.0
        }
        return score
    }

    private fun shouldRejectByLowConfidence(
        transcript: VoskTranscriptResult,
        modelCount: Int
    ): Boolean {
        val confidence = transcript.averageConfidence ?: return false
        return when {
            confidence < 0.35 -> true
            confidence < 0.45 && modelCount <= 1 -> true
            else -> false
        }
    }

    private fun detectModelLanguageHint(modelDir: File): String? {
        val sample = "${modelDir.name} ${modelDir.absolutePath}".lowercase(Locale.US)
        val tokens = sample.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        fun containsToken(value: String): Boolean = tokens.any { it == value }
        fun containsPrefix(value: String): Boolean = tokens.any { it.startsWith(value) }

        return when {
            containsToken("zh") || containsToken("cn") || containsToken("chinese") || containsToken("mandarin") -> "zh"
            containsToken("pt") || containsToken("ptbr") || containsToken("portuguese") || containsToken("portugues") || containsToken("brazilian") || containsPrefix("pt") -> "pt"
            containsToken("es") || containsToken("spanish") || containsToken("espanol") || containsToken("español") || containsPrefix("es") -> "es"
            containsToken("en") || containsToken("english") || containsPrefix("en") -> "en"
            else -> null
        }
    }

    private fun normalizeVoskText(raw: String): String {
        val compact = raw.replace(Regex("\\s+"), " ").trim()
        if (compact.isBlank()) return ""

        val cjkCount = compact.count { it.code in 0x4E00..0x9FFF }
        return if (cjkCount >= 8) {
            compact.replace(" ", "")
        } else {
            compact
        }
    }

    private fun punctuateChineseText(raw: String): String {
        if (raw.isBlank()) return ""

        val hasCjk = raw.any { it.code in 0x4E00..0x9FFF }
        if (!hasCjk) return raw

        var text = raw.trim()
        val hasSentencePunctuation = text.any { it == '。' || it == '！' || it == '？' }
        val hasColon = text.any { it == '：' || it == ':' }

        if (!hasSentencePunctuation) {
            text = text.replace(Regex("(?<!^)(第[一二三四五六七八九十百千万0-9]+句)"), "。$1")
            text = text.replace(Regex("(?<!^)(第[一二三四五六七八九十百千万0-9]+条)"), "。$1")
        }

        if (!hasColon) {
            text = text.replace(Regex("(第[一二三四五六七八九十百千万0-9]+句)(?![：:])"), "$1：")
            text = text.replace(Regex("(第[一二三四五六七八九十百千万0-9]+条)(?![：:])"), "$1：")
        }

        text = text
            .replace(Regex("[。]{2,}"), "。")
            .replace(Regex("[：:]{2,}"), "：")
            .replace(Regex("。："), "。")

        if (text.isNotBlank()) {
            val tail = text.last()
            if (tail != '。' && tail != '！' && tail != '？') {
                text += "。"
            }
        }
        return text
    }

    private fun extractRemoteErrorMessage(responseBody: String): String {
        if (responseBody.isBlank()) return "无响应正文"
        return runCatching {
            val payload = JSONObject(responseBody)
            when (val error = payload.opt("error")) {
                is JSONObject -> error.optString("message").ifBlank { error.toString() }
                is String -> error
                else -> responseBody.take(240)
            }
        }.getOrElse { responseBody.take(240) }
    }

    private fun resolveIso639_1(languageTag: String): String? {
        val language = languageTag
            .substringBefore('-')
            .trim()
            .lowercase(Locale.US)
        if (language.length !in 2..3) return null
        return language
    }

    private fun resolveEffectiveLanguageTag(
        preferredLanguage: AsrLanguageOption,
        fallbackLanguageTag: String
    ): String {
        return preferredLanguage.iso639_1 ?: fallbackLanguageTag
    }

    private fun estimateReadTimeoutMs(fileSizeBytes: Long): Int {
        val estimatedSeconds = (fileSizeBytes / (16 * 1024)).toInt()
        val timeoutMs = 180_000 + estimatedSeconds * 1_000
        return min(900_000, max(180_000, timeoutMs))
    }

    private fun readWavInfo(file: File): WavInfo {
        if (!file.exists() || file.length() < 44L) {
            return WavInfo(false, 0, 0, 0, 0)
        }

        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            val read = input.read(header)
            if (read < 44) {
                return WavInfo(false, 0, 0, 0, 0)
            }

            val riff = String(header, 0, 4, StandardCharsets.US_ASCII)
            val wave = String(header, 8, 4, StandardCharsets.US_ASCII)
            val fmt = String(header, 12, 4, StandardCharsets.US_ASCII)
            val data = String(header, 36, 4, StandardCharsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE" || fmt != "fmt " || data != "data") {
                return WavInfo(false, 0, 0, 0, 0)
            }

            val channelCount = littleEndianShort(header, 22)
            val sampleRate = littleEndianInt(header, 24)
            val bitsPerSample = littleEndianShort(header, 34)
            return WavInfo(true, channelCount, sampleRate, bitsPerSample, 44)
        }
    }

    private fun littleEndianShort(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun littleEndianInt(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun collectProbeTextStats(text: String): ProbeTextStats {
        val compact = text.replace(" ", "")
        if (compact.isBlank()) {
            return ProbeTextStats(
                cjkRatio = 0.0,
                latinRatio = 0.0,
                uniqueRatio = 0.0,
                dominantCharRatio = 0.0
            )
        }

        val total = max(1, compact.length)
        val cjkCount = compact.count { it.code in 0x4E00..0x9FFF }
        val latinCount = compact.count { it.isLetter() && it.code in 0x0041..0x024F }
        val uniqueRatio = compact.toSet().size.toDouble() / total
        val dominantCharRatio = compact
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.value
            ?.toDouble()
            ?.div(total)
            ?: 0.0
        return ProbeTextStats(
            cjkRatio = cjkCount.toDouble() / total,
            latinRatio = latinCount.toDouble() / total,
            uniqueRatio = uniqueRatio,
            dominantCharRatio = dominantCharRatio
        )
    }

    private fun formatDouble(value: Double?): String {
        return if (value == null) "N/A" else String.format(Locale.US, "%.3f", value)
    }

    private fun previewText(raw: String, maxLen: Int = 64): String {
        val compact = raw.replace('\n', ' ').trim()
        if (compact.length <= maxLen) return compact
        return compact.take(maxLen) + "..."
    }

    private data class WavInfo(
        val isValidWav: Boolean,
        val channelCount: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Int
    )

    private data class VoskParsedPayload(
        val text: String,
        val confidenceSum: Double,
        val confidenceCount: Int
    )

    private data class VoskTranscriptResult(
        val text: String,
        val averageConfidence: Double?,
        val confidenceSamples: Int
    )

    private data class VoskProbeResult(
        val modelDir: File,
        val languageHint: String?,
        val score: Double,
        val transcript: VoskTranscriptResult,
        val cjkRatio: Double,
        val latinRatio: Double,
        val uniqueRatio: Double,
        val dominantCharRatio: Double
    )

    private data class ProbeTextStats(
        val cjkRatio: Double,
        val latinRatio: Double,
        val uniqueRatio: Double,
        val dominantCharRatio: Double
    )

    private companion object {
        const val TAG = "VDownAsr"
        val PORTUGUESE_HINT_WORDS = setOf(
            "de", "do", "da", "que", "não", "nao", "uma", "para", "com", "por",
            "portugues", "português", "estamos", "modelo", "teste", "reconhecimento",
            "este", "essa", "isso", "nós", "nos", "você", "voce"
        )
        val ENGLISH_HINT_WORDS = setOf(
            "the", "and", "this", "that", "is", "are", "was", "were", "to", "of",
            "in", "with", "for", "you", "your", "we", "our", "have", "has"
        )
        val PORTUGUESE_ACCENT_CHARS = setOf(
            'á', 'à', 'â', 'ã', 'é', 'ê', 'í', 'ó', 'ô', 'õ', 'ú', 'ç'
        )
    }
}
