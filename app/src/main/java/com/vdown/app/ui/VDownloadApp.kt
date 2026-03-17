package com.vdown.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vdown.app.ui.theme.VDownloadTheme

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("V-Download 开发版") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                        Button(onClick = viewModel::addDraftUrlToQueue) {
                            Text("加入待下载列表")
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

                    if (state.queuedUrls.isNotEmpty()) {
                        HorizontalDivider()
                        Text("待下载 URL：")
                        state.queuedUrls.take(10).forEach { item ->
                            Text("- ${item.normalizedUrl}", style = MaterialTheme.typography.bodySmall)
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
}
