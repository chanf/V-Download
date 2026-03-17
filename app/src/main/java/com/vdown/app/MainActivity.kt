package com.vdown.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        if (intent?.action != Intent.ACTION_SEND) return null
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        return sharedText.takeIf { it.isNotBlank() }
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
}
