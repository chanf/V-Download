package com.vdown.app.ui

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vdown.app.asr.AsrLanguageOption
import com.vdown.app.asr.AsrProviderType
import com.vdown.app.asr.defaultAsrBaseUrlFor
import com.vdown.app.asr.defaultAsrModelFor
import com.vdown.app.llm.LlmProviderType
import com.vdown.app.llm.defaultBaseUrlFor
import com.vdown.app.llm.defaultLlmSystemPrompt
import com.vdown.app.llm.defaultModelFor
import com.vdown.app.ui.theme.VDownloadTheme

private enum class HomeTab(val title: String) {
    VIDEO_DOWNLOAD("视频下载"),
    COPY_EXTRACT("文案提取"),
    SETTINGS("设置")
}

@Composable
fun VDownloadApp(
    initialSharedUrl: String?,
    hasStorageWritePermission: Boolean,
    onRequestStoragePermission: () -> Unit
) {
    VDownloadTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val viewModel: CookieImportViewModel = viewModel()
            LaunchedEffect(initialSharedUrl) {
                viewModel.onSharedUrlReceived(initialSharedUrl)
            }
            VDownloadScreen(
                viewModel = viewModel,
                hasStorageWritePermission = hasStorageWritePermission,
                onRequestStoragePermission = onRequestStoragePermission
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VDownloadScreen(
    viewModel: CookieImportViewModel,
    hasStorageWritePermission: Boolean,
    onRequestStoragePermission: () -> Unit
) {
    var activeTab by rememberSaveable { mutableStateOf(HomeTab.VIDEO_DOWNLOAD.name) }
    val selectedTab = remember(activeTab) {
        HomeTab.entries.firstOrNull { it.name == activeTab } ?: HomeTab.VIDEO_DOWNLOAD
    }
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            HomeTab.COPY_EXTRACT -> viewModel.onCopyExtractTabOpened()
            HomeTab.SETTINGS -> {
                viewModel.refreshLlmProviderState()
                viewModel.refreshAsrProviderState()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("视频下载神器") })
        },
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { activeTab = tab.name },
                        icon = {},
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            HomeTab.VIDEO_DOWNLOAD -> VideoDownloadTabContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                viewModel = viewModel,
                hasStorageWritePermission = hasStorageWritePermission,
                onRequestStoragePermission = onRequestStoragePermission
            )

            HomeTab.COPY_EXTRACT -> CopyExtractTabContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                viewModel = viewModel
            )

            HomeTab.SETTINGS -> SettingsTabContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun VideoDownloadTabContent(
    modifier: Modifier,
    viewModel: CookieImportViewModel,
    hasStorageWritePermission: Boolean,
    onRequestStoragePermission: () -> Unit
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importCookies(uri)
        }
    )

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasStorageWritePermission) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "当前未授予文件写入权限（Android 9 及以下必需），视频保存可能失败。",
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onRequestStoragePermission) {
                        Text("立即申请写入权限")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("1) URL 输入入口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("支持系统分享自动带入 URL，也支持手动输入 URL。")

                OutlinedTextField(
                    value = state.urlDraft,
                    onValueChange = viewModel::updateUrlDraft,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("视频链接") },
                    placeholder = { Text("粘贴 TikTok / YouTube / Bilibili / 抖音 / Instagram / 小红书 链接") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val clipboardText = clipboardManager.getText()?.text?.toString().orEmpty()
                            viewModel.pasteAndDownloadFromClipboard(
                                clipboardText = clipboardText,
                                hasStorageWritePermission = hasStorageWritePermission
                            )
                        },
                        enabled = !state.isDownloading
                    ) {
                        Text("粘贴下载")
                    }
                    Button(
                        onClick = {
                            viewModel.startDownload(
                                hasStorageWritePermission = hasStorageWritePermission
                            )
                        },
                        enabled = !state.isDownloading
                    ) {
                        Text("开始下载")
                    }
                }

                if (state.isDownloading) {
                    HorizontalDivider()
                    Text("下载中：${state.downloadProgress}%")
                    state.downloadingUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                state.downloadMessage?.let { message ->
                    HorizontalDivider()
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("2) Cookies 导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("支持导入 Chrome 插件导出的 Netscape cookies.txt，应用会在本地解析并入库。")

                Button(
                    onClick = { openDocumentLauncher.launch(arrayOf("text/plain", "text/*", "*/*")) },
                    enabled = !state.isImporting
                ) {
                    Text("选择并导入 cookies.txt")
                }

                if (state.isImporting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在导入，请稍候...")
                    }
                }

                Text("当前已存储可用视频 Cookies 数量：${state.totalCookies}")

                state.lastImportResult?.let { result ->
                    HorizontalDivider()
                    Text("最近导入结果：", fontWeight = FontWeight.SemiBold)
                    Text("文件：${result.sourceFileName}")
                    Text("解析成功：${result.parsedCount}")
                    Text("识别为视频站点：${result.videoSiteParsedCount}")
                    Text("导入入库（可用）：${result.importedCount}")
                    Text("跳过注释：${result.skippedCommentLines}")
                    Text("跳过无效行：${result.skippedInvalidLines}")
                    Text("跳过过期项：${result.skippedExpiredLines}")

                    if (result.importedVideoSourceReports.isNotEmpty()) {
                        HorizontalDivider()
                        Text("本次导入视频源状态：", fontWeight = FontWeight.SemiBold)
                        result.importedVideoSourceReports.forEach { report ->
                            val status = if (report.isUsable) "可用" else "过期"
                            Text(
                                text = "- ${report.sourceName}：可用 ${report.availableCount}，过期 ${report.expiredCount}（$status）",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (state.storedVideoSourceReports.isNotEmpty()) {
                    HorizontalDivider()
                    Text("累计已存储可用视频源 Cookies：", fontWeight = FontWeight.SemiBold)
                    state.storedVideoSourceReports.forEach { report ->
                        Text(
                            text = "- ${report.sourceName}：${report.availableCount}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        state.warningMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.errorMessage?.let { message ->
            val displayMessage = "提示：$message"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(displayMessage) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(displayMessage))
                            }
                        )
                    }
            ) {
                Text(
                    text = displayMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.downloadDiagnostics?.takeIf { it.isNotBlank() }?.let { diagnostics ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(diagnostics) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(diagnostics))
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "下载诊断日志（双击复制）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = diagnostics,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

    }
}

@Composable
private fun CopyExtractTabContent(
    modifier: Modifier,
    viewModel: CookieImportViewModel
) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var draft by rememberSaveable { mutableStateOf("") }
    val extractedUrls = remember(draft) { extractUrlsFromText(draft) }
    val effectiveAsrName = remember(
        state.asrProviders,
        state.selectedAsrProviderId,
        state.llmProviders,
        state.selectedLlmProviderId
    ) {
        resolveEffectiveAsrDisplayName(state)
    }
    val explicitAsrProvider = remember(state.asrProviders, state.selectedAsrProviderId) {
        state.asrProviders.firstOrNull { it.id == state.selectedAsrProviderId }
    }
    val currentAsrLanguage = explicitAsrProvider?.preferredLanguage ?: AsrLanguageOption.AUTO
    var asrLanguageMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val hasEffectiveAsr = effectiveAsrName != null

    val openVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.selectTranscriptVideo(uri)
        }
    )

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("视频文案导出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("进入本页默认选中最近下载视频，你也可以手动重新选择。")

                val selectedVideoName = state.selectedTranscriptVideoName
                val selectedVideoUri = state.selectedTranscriptVideoUri
                if (selectedVideoName.isNullOrBlank() || selectedVideoUri == null) {
                    Text("当前未选中视频", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("当前视频：$selectedVideoName", style = MaterialTheme.typography.bodySmall)
                    Text("来源：${state.selectedTranscriptVideoSource.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    Text("URI：$selectedVideoUri", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "当前ASR：${effectiveAsrName ?: "未配置"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "识别语言：${currentAsrLanguage.title}",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        Button(
                            onClick = { asrLanguageMenuExpanded = true },
                            enabled = explicitAsrProvider != null
                        ) {
                            Text("语言")
                        }
                        DropdownMenu(
                            expanded = asrLanguageMenuExpanded,
                            onDismissRequest = { asrLanguageMenuExpanded = false }
                        ) {
                            AsrLanguageOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.title) },
                                    onClick = {
                                        asrLanguageMenuExpanded = false
                                        viewModel.updateCurrentAsrPreferredLanguage(option)
                                    }
                                )
                            }
                        }
                    }
                    Button(onClick = { openVideoLauncher.launch(arrayOf("video/*")) }) {
                        Text("选择视频")
                    }
                    Button(
                        onClick = {
                            viewModel.exportSelectedVideoTranscript()
                        },
                        enabled = !state.isExportingTranscript && state.selectedTranscriptVideoUri != null
                    ) {
                        Text("导出")
                    }
                    Button(
                        onClick = viewModel::startAudioPlayback,
                        enabled = state.selectedTranscriptVideoUri != null && !state.isAudioPreparing
                    ) {
                        Text("🎵")
                    }
                }

                if (effectiveAsrName != null && explicitAsrProvider == null) {
                    Text(
                        "提示：当前ASR来自LLM复用。本页仅支持独立ASR配置切换语言，请在“设置”中添加独立ASR服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (state.isAudioControlVisible) {
                    HorizontalDivider()
                    Text("音频播放控制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalIconButton(
                            onClick = viewModel::playAudio,
                            enabled = state.selectedTranscriptVideoUri != null && !state.isAudioPreparing && !state.isAudioPlaying,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "播放"
                            )
                        }
                        FilledTonalIconButton(
                            onClick = viewModel::pauseAudio,
                            enabled = state.isAudioPlaying,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = "暂停"
                            )
                        }
                        FilledTonalIconButton(
                            onClick = viewModel::stopAudio,
                            enabled = state.isAudioPlaying || state.isAudioPaused,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "停止"
                            )
                        }
                    }

                    when {
                        state.isAudioPreparing -> Text("音频状态：正在加载...")
                        state.isAudioPlaying -> Text("音频状态：播放中")
                        state.isAudioPaused -> Text("音频状态：已暂停")
                        else -> Text("音频状态：空闲")
                    }

                    state.audioStatusMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    state.audioErrorMessage?.let { message ->
                        Text("提示：$message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                if (!hasEffectiveAsr) {
                    Text(
                        "提示：请先在“设置”中配置可用 ASR（或可复用的 LLM 服务）。当前版本不使用麦克风识别。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (state.isExportingTranscript) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("文案导出进行中...")
                    }
                }

                state.transcriptMessage?.let { message ->
                    HorizontalDivider()
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }

                state.transcriptAudioPath?.let { path ->
                    Text("音频缓存文件：$path", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.transcriptText?.takeIf { it.isNotBlank() }?.let { text ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(text) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("导出文案（双击复制）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(text)
                }
            }
        }

        state.transcriptText?.takeIf { it.isNotBlank() }?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("AI重构文案", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "使用设置中当前选中的 LLM 服务，对已提取文案进行梳理和重构。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = viewModel::rewriteTranscriptWithAi,
                        enabled = !state.isAiRewriting
                    ) {
                        Text("AI重构")
                    }
                    if (state.isAiRewriting) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("AI 正在重构中...")
                        }
                    }
                }
            }
        }

        state.aiRewrittenText?.takeIf { it.isNotBlank() }?.let { text ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(text) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("AI重构结果（双击复制）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(text)
                }
            }
        }

        state.transcriptErrorMessage?.let { message ->
            val displayMessage = "提示：$message"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(displayMessage) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(displayMessage))
                            }
                        )
                    }
            ) {
                Text(
                    text = displayMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.aiRewriteErrorMessage?.let { message ->
            val displayMessage = "提示：$message"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(displayMessage) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(displayMessage))
                            }
                        )
                    }
            ) {
                Text(
                    text = displayMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.aiRewriteDiagnostics?.takeIf { it.isNotBlank() }?.let { diagnostics ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(diagnostics) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(diagnostics))
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "AI重构诊断日志（双击复制）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = diagnostics,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        state.transcriptDiagnostics?.takeIf { it.isNotBlank() }?.let { diagnostics ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(diagnostics) {
                        detectTapGestures(
                            onDoubleTap = {
                                clipboardManager.setText(AnnotatedString(diagnostics))
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "文案提取诊断日志（双击复制）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = diagnostics,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("分享文案 URL 提取", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("粘贴分享文案后，自动提取其中链接（可继续用于下载）。")

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("输入文案") },
                    placeholder = { Text("例如：3.87 复制打开抖音... https://v.douyin.com/xxxx/ ...") },
                    minLines = 4
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { draft = "" }) {
                        Text("清空")
                    }
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(extractedUrls.joinToString(separator = "\n")))
                        },
                        enabled = extractedUrls.isNotEmpty()
                    ) {
                        Text("复制提取结果")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("URL 提取结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (extractedUrls.isEmpty()) {
                    Text("未检测到可用链接", style = MaterialTheme.typography.bodySmall)
                } else {
                    extractedUrls.forEach { url ->
                        Text("- $url", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTabContent(
    modifier: Modifier,
    viewModel: CookieImportViewModel
) {
    val state = viewModel.uiState

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var providerTypeName by rememberSaveable { mutableStateOf(LlmProviderType.OPENAI.name) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf(defaultBaseUrlFor(LlmProviderType.OPENAI)) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(defaultModelFor(LlmProviderType.OPENAI)) }
    var systemPrompt by rememberSaveable { mutableStateOf(defaultLlmSystemPrompt()) }
    var organization by rememberSaveable { mutableStateOf("") }
    var azureDeployment by rememberSaveable { mutableStateOf("") }
    var azureApiVersion by rememberSaveable { mutableStateOf("2024-10-21") }
    var providerTypeExpanded by rememberSaveable { mutableStateOf(false) }

    var asrEditingId by rememberSaveable { mutableStateOf<String?>(null) }
    var asrProviderTypeName by rememberSaveable { mutableStateOf(AsrProviderType.OPENAI.name) }
    var asrDisplayName by rememberSaveable { mutableStateOf("") }
    var asrBaseUrl by rememberSaveable { mutableStateOf(defaultAsrBaseUrlFor(AsrProviderType.OPENAI)) }
    var asrApiKey by rememberSaveable { mutableStateOf("") }
    var asrModel by rememberSaveable { mutableStateOf(defaultAsrModelFor(AsrProviderType.OPENAI)) }
    var asrOrganization by rememberSaveable { mutableStateOf("") }
    var asrAzureDeployment by rememberSaveable { mutableStateOf("") }
    var asrAzureApiVersion by rememberSaveable { mutableStateOf("2024-10-21") }
    var asrLocalModelPath by rememberSaveable { mutableStateOf("") }
    var asrLanguageName by rememberSaveable { mutableStateOf(AsrLanguageOption.AUTO.name) }
    var asrProviderTypeExpanded by rememberSaveable { mutableStateOf(false) }
    var asrLanguageExpanded by rememberSaveable { mutableStateOf(false) }

    val selectedType = remember(providerTypeName) {
        runCatching { LlmProviderType.valueOf(providerTypeName) }.getOrDefault(LlmProviderType.OPENAI)
    }
    val selectedAsrType = remember(asrProviderTypeName) {
        runCatching { AsrProviderType.valueOf(asrProviderTypeName) }.getOrDefault(AsrProviderType.OPENAI)
    }
    val selectedAsrLanguage = remember(asrLanguageName) {
        runCatching { AsrLanguageOption.valueOf(asrLanguageName) }.getOrDefault(AsrLanguageOption.AUTO)
    }

    fun resetForm() {
        editingId = null
        providerTypeName = LlmProviderType.OPENAI.name
        displayName = ""
        baseUrl = defaultBaseUrlFor(LlmProviderType.OPENAI)
        apiKey = ""
        model = defaultModelFor(LlmProviderType.OPENAI)
        systemPrompt = defaultLlmSystemPrompt()
        organization = ""
        azureDeployment = ""
        azureApiVersion = "2024-10-21"
    }

    fun resetAsrForm() {
        asrEditingId = null
        asrProviderTypeName = AsrProviderType.OPENAI.name
        asrDisplayName = ""
        asrBaseUrl = defaultAsrBaseUrlFor(AsrProviderType.OPENAI)
        asrApiKey = ""
        asrModel = defaultAsrModelFor(AsrProviderType.OPENAI)
        asrOrganization = ""
        asrAzureDeployment = ""
        asrAzureApiVersion = "2024-10-21"
        asrLocalModelPath = ""
        asrLanguageName = AsrLanguageOption.AUTO.name
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("LLM服务选择", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val selected = state.llmProviders.firstOrNull { it.id == state.selectedLlmProviderId }
                if (selected == null) {
                    Text("当前未选择服务。请先在下方添加服务商参数。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("当前服务：${selected.displayName}", style = MaterialTheme.typography.bodySmall)
                    Text("类型：${selected.providerType.title}", style = MaterialTheme.typography.bodySmall)
                    Text("模型：${selected.model}", style = MaterialTheme.typography.bodySmall)
                    Text("地址：${selected.baseUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("System Prompt：${selected.systemPrompt.trim().length} 字符", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (editingId == null) "新增LLM服务商参数" else "编辑LLM服务商参数",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                ExposedDropdownMenuBox(
                    expanded = providerTypeExpanded,
                    onExpandedChange = { providerTypeExpanded = !providerTypeExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedType.title,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        label = { Text("服务类型（参数模版）") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerTypeExpanded)
                        }
                    )
                    DropdownMenu(
                        expanded = providerTypeExpanded,
                        onDismissRequest = { providerTypeExpanded = false }
                    ) {
                        LlmProviderType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.title) },
                                onClick = {
                                    providerTypeName = type.name
                                    providerTypeExpanded = false
                                    baseUrl = defaultBaseUrlFor(type)
                                    model = defaultModelFor(type)
                                    if (type == LlmProviderType.AZURE_OPENAI) {
                                        azureDeployment = defaultModelFor(type)
                                        if (azureApiVersion.isBlank()) {
                                            azureApiVersion = "2024-10-21"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务名称（例如：我的Deepseek）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    minLines = 2,
                    maxLines = 4
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型（Model）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System Prompt（AI重构角色指令）") },
                    placeholder = { Text("例如：你是中文短视频文案编辑...") },
                    minLines = 4,
                    maxLines = 10
                )

                if (selectedType == LlmProviderType.OPENAI || selectedType == LlmProviderType.OPENAI_COMPATIBLE) {
                    OutlinedTextField(
                        value = organization,
                        onValueChange = { organization = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenAI Organization（可选）") },
                        singleLine = true
                    )
                }

                if (selectedType == LlmProviderType.AZURE_OPENAI) {
                    OutlinedTextField(
                        value = azureDeployment,
                        onValueChange = { azureDeployment = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Azure Deployment") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = azureApiVersion,
                        onValueChange = { azureApiVersion = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Azure API Version") },
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveLlmProvider(
                                editingId = editingId,
                                displayName = displayName,
                                providerType = selectedType,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model,
                                systemPrompt = systemPrompt,
                                organization = organization,
                                azureDeployment = azureDeployment,
                                azureApiVersion = azureApiVersion
                            )
                            editingId = null
                        }
                    ) {
                        Text(if (editingId == null) "保存并设为当前" else "更新配置")
                    }
                    Button(onClick = { resetForm() }) {
                        Text("清空表单")
                    }
                }
            }
        }

        state.llmStatusMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.llmErrorMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "提示：$message",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("已添加 LLM 服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (state.llmProviders.isEmpty()) {
                    Text("暂无已配置服务", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.llmProviders.forEach { provider ->
                        val selected = provider.id == state.selectedLlmProviderId
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("名称：${provider.displayName}", style = MaterialTheme.typography.bodySmall)
                            Text("类型：${provider.providerType.title}", style = MaterialTheme.typography.bodySmall)
                            Text("模型：${provider.model}", style = MaterialTheme.typography.bodySmall)
                            Text("地址：${provider.baseUrl}", style = MaterialTheme.typography.bodySmall)
                            Text("System Prompt：${provider.systemPrompt.trim().length} 字符", style = MaterialTheme.typography.bodySmall)
                            Text("Key：${maskApiKey(provider.apiKey)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.selectLlmProvider(provider.id) }) {
                                    Text(if (selected) "当前服务" else "设为当前")
                                }
                                Button(
                                    onClick = {
                                        editingId = provider.id
                                        providerTypeName = provider.providerType.name
                                        displayName = provider.displayName
                                        baseUrl = provider.baseUrl
                                        apiKey = provider.apiKey
                                        model = provider.model
                                        systemPrompt = provider.systemPrompt.ifBlank { defaultLlmSystemPrompt() }
                                        organization = provider.organization
                                        azureDeployment = provider.azureDeployment
                                        azureApiVersion = provider.azureApiVersion.ifBlank { "2024-10-21" }
                                    }
                                ) {
                                    Text("加载编辑")
                                }
                                Button(onClick = { viewModel.removeLlmProvider(provider.id) }) {
                                    Text("删除")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ASR服务选择", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val selectedAsr = state.asrProviders.firstOrNull { it.id == state.selectedAsrProviderId }
                val fallbackAsrName = resolveEffectiveAsrDisplayName(state)
                when {
                    selectedAsr != null -> {
                        Text("当前服务：${selectedAsr.displayName}", style = MaterialTheme.typography.bodySmall)
                        Text("类型：${selectedAsr.providerType.title}", style = MaterialTheme.typography.bodySmall)
                        Text("识别语言：${selectedAsr.preferredLanguage.title}", style = MaterialTheme.typography.bodySmall)
                        if (selectedAsr.providerType == AsrProviderType.VOSK_LOCAL) {
                            Text("模型目录：${selectedAsr.localModelPath}", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("模型：${selectedAsr.model}", style = MaterialTheme.typography.bodySmall)
                            Text("地址：${selectedAsr.baseUrl}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    fallbackAsrName != null -> {
                        Text("当前未独立配置 ASR。", style = MaterialTheme.typography.bodySmall)
                        Text("导出文案将复用：$fallbackAsrName", style = MaterialTheme.typography.bodySmall)
                    }

                    else -> {
                        Text("当前未选择服务。请先在下方添加 ASR 参数。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (asrEditingId == null) "新增ASR服务商参数" else "编辑ASR服务商参数",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                ExposedDropdownMenuBox(
                    expanded = asrProviderTypeExpanded,
                    onExpandedChange = { asrProviderTypeExpanded = !asrProviderTypeExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedAsrType.title,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        label = { Text("服务类型（参数模版）") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = asrProviderTypeExpanded)
                        }
                    )
                    DropdownMenu(
                        expanded = asrProviderTypeExpanded,
                        onDismissRequest = { asrProviderTypeExpanded = false }
                    ) {
                        AsrProviderType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.title) },
                                onClick = {
                                    asrProviderTypeName = type.name
                                    asrProviderTypeExpanded = false
                                    asrBaseUrl = defaultAsrBaseUrlFor(type)
                                    asrModel = defaultAsrModelFor(type)
                                    if (type == AsrProviderType.AZURE_OPENAI) {
                                        asrAzureDeployment = defaultAsrModelFor(type)
                                        if (asrAzureApiVersion.isBlank()) {
                                            asrAzureApiVersion = "2024-10-21"
                                        }
                                    }
                                    if (type == AsrProviderType.VOSK_LOCAL) {
                                        asrApiKey = ""
                                        asrOrganization = ""
                                        asrBaseUrl = ""
                                    }
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = asrDisplayName,
                    onValueChange = { asrDisplayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务名称（例如：本地Vosk）") },
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = asrLanguageExpanded,
                    onExpandedChange = { asrLanguageExpanded = !asrLanguageExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedAsrLanguage.title,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        readOnly = true,
                        label = { Text("识别语言（可手动指定）") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = asrLanguageExpanded)
                        }
                    )
                    DropdownMenu(
                        expanded = asrLanguageExpanded,
                        onDismissRequest = { asrLanguageExpanded = false }
                    ) {
                        AsrLanguageOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.title) },
                                onClick = {
                                    asrLanguageName = option.name
                                    asrLanguageExpanded = false
                                }
                            )
                        }
                    }
                }

                if (selectedAsrType == AsrProviderType.VOSK_LOCAL) {
                    OutlinedTextField(
                        value = asrLocalModelPath,
                        onValueChange = { asrLocalModelPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Vosk 模型目录（支持多目录自动识别）") },
                        placeholder = {
                            Text(
                                "/storage/emulated/0/vosk/zh-model\n/storage/emulated/0/vosk/en-model\n或填父目录后自动扫描子目录"
                            )
                        },
                        minLines = 3,
                        maxLines = 5
                    )
                } else {
                    OutlinedTextField(
                        value = asrBaseUrl,
                        onValueChange = { asrBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        minLines = 2,
                        maxLines = 4
                    )
                    OutlinedTextField(
                        value = asrApiKey,
                        onValueChange = { asrApiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = asrModel,
                        onValueChange = { asrModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型（Model）") },
                        singleLine = true
                    )
                }

                if (selectedAsrType == AsrProviderType.OPENAI || selectedAsrType == AsrProviderType.OPENAI_COMPATIBLE) {
                    OutlinedTextField(
                        value = asrOrganization,
                        onValueChange = { asrOrganization = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenAI Organization（可选）") },
                        singleLine = true
                    )
                }

                if (selectedAsrType == AsrProviderType.AZURE_OPENAI) {
                    OutlinedTextField(
                        value = asrAzureDeployment,
                        onValueChange = { asrAzureDeployment = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Azure ASR Deployment") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = asrAzureApiVersion,
                        onValueChange = { asrAzureApiVersion = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Azure API Version") },
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.saveAsrProvider(
                                editingId = asrEditingId,
                                displayName = asrDisplayName,
                                providerType = selectedAsrType,
                                preferredLanguage = selectedAsrLanguage,
                                baseUrl = asrBaseUrl,
                                apiKey = asrApiKey,
                                model = asrModel,
                                localModelPath = asrLocalModelPath,
                                organization = asrOrganization,
                                azureDeployment = asrAzureDeployment,
                                azureApiVersion = asrAzureApiVersion
                            )
                            asrEditingId = null
                        }
                    ) {
                        Text(if (asrEditingId == null) "保存并设为当前" else "更新配置")
                    }
                    Button(onClick = { resetAsrForm() }) {
                        Text("清空表单")
                    }
                }
            }
        }

        state.asrStatusMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.asrErrorMessage?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "提示：$message",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("已添加 ASR 服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (state.asrProviders.isEmpty()) {
                    Text("暂无已配置服务", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.asrProviders.forEach { provider ->
                        val selected = provider.id == state.selectedAsrProviderId
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("名称：${provider.displayName}", style = MaterialTheme.typography.bodySmall)
                            Text("类型：${provider.providerType.title}", style = MaterialTheme.typography.bodySmall)
                            Text("识别语言：${provider.preferredLanguage.title}", style = MaterialTheme.typography.bodySmall)
                            if (provider.providerType == AsrProviderType.VOSK_LOCAL) {
                                Text("模型目录：${provider.localModelPath}", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("模型：${provider.model}", style = MaterialTheme.typography.bodySmall)
                                Text("地址：${provider.baseUrl}", style = MaterialTheme.typography.bodySmall)
                                Text("Key：${maskApiKey(provider.apiKey)}", style = MaterialTheme.typography.bodySmall)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.selectAsrProvider(provider.id) }) {
                                    Text(if (selected) "当前服务" else "设为当前")
                                }
                                Button(
                                    onClick = {
                                        asrEditingId = provider.id
                                        asrProviderTypeName = provider.providerType.name
                                        asrDisplayName = provider.displayName
                                        asrBaseUrl = provider.baseUrl
                                        asrApiKey = provider.apiKey
                                        asrModel = provider.model
                                        asrOrganization = provider.organization
                                        asrAzureDeployment = provider.azureDeployment
                                        asrAzureApiVersion = provider.azureApiVersion.ifBlank { "2024-10-21" }
                                        asrLocalModelPath = provider.localModelPath
                                        asrLanguageName = provider.preferredLanguage.name
                                    }
                                ) {
                                    Text("加载编辑")
                                }
                                Button(onClick = { viewModel.removeAsrProvider(provider.id) }) {
                                    Text("删除")
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun resolveEffectiveAsrDisplayName(state: CookieImportUiState): String? {
    val explicitAsr = state.asrProviders.firstOrNull { it.id == state.selectedAsrProviderId }
    if (explicitAsr != null) {
        return explicitAsr.displayName
    }

    val selectedLlm = state.llmProviders.firstOrNull { it.id == state.selectedLlmProviderId } ?: return null
    return when (selectedLlm.providerType) {
        LlmProviderType.OPENAI,
        LlmProviderType.OPENAI_COMPATIBLE,
        LlmProviderType.AZURE_OPENAI -> "${selectedLlm.displayName}（复用LLM配置）"

        LlmProviderType.DEEPSEEK,
        LlmProviderType.GEMINI -> null
    }
}

private fun maskApiKey(value: String): String {
    if (value.isBlank()) return "(空)"
    if (value.length <= 8) return "****"
    return "${value.take(4)}****${value.takeLast(4)}"
}

private fun extractUrlsFromText(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return emptyList()

    val urls = linkedSetOf<String>()
    STRICT_HTTP_URL_PATTERN.findAll(trimmed).forEach { match ->
        urls += trimUrlTail(match.value)
    }

    if (urls.isEmpty()) {
        val matcher = Patterns.WEB_URL.matcher(trimmed)
        while (matcher.find()) {
            val raw = matcher.group().orEmpty().trim()
            if (raw.isBlank()) continue
            val withScheme = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
                raw
            } else {
                "https://$raw"
            }
            urls += trimUrlTail(withScheme)
        }
    }

    return urls.filter { it.startsWith("http://") || it.startsWith("https://") }
}

private fun trimUrlTail(url: String): String {
    var value = url.trim()
    while (value.isNotEmpty() && value.last() in URL_TRAILING_CHARS) {
        value = value.dropLast(1)
    }
    return value
}

private val STRICT_HTTP_URL_PATTERN = Regex("""https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""", RegexOption.IGNORE_CASE)
private val URL_TRAILING_CHARS = setOf(
    '，', '。', '！', '？', '；', '：',
    ',', '.', '!', '?', ';', ':',
    ')', ']', '}', '>', '"', '\''
)
