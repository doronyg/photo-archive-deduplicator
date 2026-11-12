package com.doronyg.photoarchivededuplicator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import com.doronyg.photoarchivededuplicator.ui.InventoryApp
import com.doronyg.photoarchivededuplicator.ui.ScanTarget

class MainActivity : ComponentActivity() {
    private val mediaPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private var onMediaAccessGranted: (() -> Unit)? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val callback = onMediaAccessGranted
        onMediaAccessGranted = null
        // Any grant is enough: with partial access the scan simply sees fewer items
        if (results.values.any { it }) {
            callback?.invoke()
        } else {
            Toast.makeText(this, AppStrings.platform.mediaPermissionNeeded, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val owner = application as PhotoArchiveApplication
        val scanTarget = ScanTarget.Fixed(
            root = ANDROID_STORAGE_ROOT,
            buttonLabel = AppStrings.platform.scanPhotoLibrary,
            requestAccess = ::withMediaAccess,
        )
        val pathActions = androidPathActions(this, owner::container)
        setContent { InventoryApp(loadContainer = owner::container, scanTarget, pathActions) }
    }

    private fun withMediaAccess(onGranted: () -> Unit) {
        if (mediaPermissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            onGranted()
        } else {
            onMediaAccessGranted = onGranted
            permissionRequest.launch(mediaPermissions)
        }
    }
}
