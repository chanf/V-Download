package com.vdown.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.vdown.app.ui.VDownloadApp

class MainActivity : ComponentActivity() {
    private var sharedUrl by mutableStateOf<String?>(null)
    private var hasStorageWritePermission by mutableStateOf(true)

    private val requestWritePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasStorageWritePermission = granted || !requiresLegacyWritePermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sharedUrl = extractSharedUrl(intent)
        hasStorageWritePermission = isStorageWritePermissionGranted()
        requestStorageWritePermissionIfNeeded()

        setContent {
            VDownloadApp(
                initialSharedUrl = sharedUrl,
                hasStorageWritePermission = hasStorageWritePermission,
                onRequestStoragePermission = ::requestStorageWritePermissionIfNeeded
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hasStorageWritePermission = isStorageWritePermissionGranted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl = extractSharedUrl(intent)
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent == null) return null
        val candidates = linkedSetOf<String>()
        when (intent.action) {
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_PROCESS_TEXT,
            Intent.ACTION_VIEW -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let(candidates::add)
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(candidates::add)
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let(candidates::add)
                intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let(candidates::add)
                intent.dataString?.let(candidates::add)
                intent.clipData?.let { clip ->
                    repeat(clip.itemCount) { index ->
                        clip.getItemAt(index).coerceToText(this)?.toString()?.let(candidates::add)
                        clip.getItemAt(index).uri?.toString()?.let(candidates::add)
                    }
                }
            }
            else -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let(candidates::add)
                intent.dataString?.let(candidates::add)
            }
        }

        candidates.forEach { text ->
            extractFirstUrl(text)?.let { return it }
        }
        return null
    }

    private fun extractFirstUrl(text: String): String? {
        val source = text.trim()
        if (source.isBlank()) return null

        STRICT_HTTP_URL_PATTERN.find(source)?.value?.let { matched ->
            return normalizeSharedUrl(matched)
        }

        val matcher = Patterns.WEB_URL.matcher(source)
        while (matcher.find()) {
            val raw = matcher.group().trim()
            if (raw.isBlank()) continue
            val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else {
                "https://$raw"
            }
            return normalizeSharedUrl(withScheme)
        }

        return null
    }

    private fun normalizeSharedUrl(raw: String): String {
        var value = raw.trim()
        while (value.isNotEmpty() && value.last() in URL_TRAILING_CHARS) {
            value = value.dropLast(1)
        }
        if (value.startsWith("http://", ignoreCase = true)) {
            val host = Uri.parse(value).host?.lowercase()?.removePrefix(".")
            val shouldForceHttps = host != null && HTTPS_PREFERRED_HOST_SUFFIXES.any { suffix ->
                host == suffix || host.endsWith(".$suffix")
            }
            if (shouldForceHttps) {
                value = value.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            }
        }
        return value
    }

    private fun requestStorageWritePermissionIfNeeded() {
        if (!requiresLegacyWritePermission()) {
            hasStorageWritePermission = true
            return
        }

        val granted = isStorageWritePermissionGranted()
        hasStorageWritePermission = granted
        if (!granted) {
            requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun isStorageWritePermissionGranted(): Boolean {
        if (!requiresLegacyWritePermission()) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiresLegacyWritePermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }

    companion object {
        private val STRICT_HTTP_URL_PATTERN =
            Regex("""https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%-]+""", RegexOption.IGNORE_CASE)
        private val URL_TRAILING_CHARS = setOf(
            '，', '。', '！', '？', '；', '：',
            ',', '.', '!', '?', ';', ':',
            ')', ']', '}', '>', '"', '\''
        )
        private val HTTPS_PREFERRED_HOST_SUFFIXES = setOf(
            "tiktok.com",
            "musical.ly",
            "douyin.com",
            "iesdouyin.com",
            "instagram.com",
            "youtube.com",
            "youtu.be",
            "bilibili.com",
            "b23.tv",
            "xiaohongshu.com",
            "xhslink.com",
            "rednote.com"
        )
    }
}
