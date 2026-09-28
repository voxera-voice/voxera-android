package com.rocs.demo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.rocs.demo.ui.ChatScreen
import com.rocs.demo.ui.theme.RocsTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    // Request microphone and camera permissions before the user tries to connect
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            viewModel.onPermissionDenied(denied.joinToString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())

        setContent {
            RocsTheme {
                ChatScreen(viewModel = viewModel)
            }
        }
    }
}
