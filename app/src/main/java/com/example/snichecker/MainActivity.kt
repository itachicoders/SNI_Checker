package com.example.snichecker

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snichecker.ui.SniCheckerScreen
import com.example.snichecker.ui.SniCheckerViewModel
import com.example.snichecker.ui.theme.SNICheckerTheme

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<SniCheckerViewModel>()
    private var permissionDialog: AlertDialog? = null

    private val legacyStoragePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        ensureRequiredStorageAccess()
    }

    private val manageStorageAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        ensureRequiredStorageAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            SNICheckerTheme {
                SniCheckerScreen(
                    state = uiState,
                    onInputSourceChange = viewModel::updateInputSource,
                    onOutputDirChange = viewModel::updateOutputDir,
                    onConcurrencyChange = viewModel::updateConcurrency,
                    onTimeoutChange = viewModel::updateTimeout,
                    onStartClick = viewModel::startScan,
                    onStopClick = viewModel::stopScan,
                    onDismissError = viewModel::dismissError
                )
            }
        }

        ensureRequiredStorageAccess()
    }

    override fun onResume() {
        super.onResume()
        if (permissionDialog?.isShowing == true && hasRequiredStorageAccess()) {
            permissionDialog?.dismiss()
        }
    }

    private fun ensureRequiredStorageAccess() {
        if (hasRequiredStorageAccess()) {
            permissionDialog?.dismiss()
            return
        }

        if (permissionDialog?.isShowing == true) return

        permissionDialog = AlertDialog.Builder(this)
            .setTitle("Нужен доступ к файлам")
            .setMessage(
                "SNI Checker сохраняет списки и результаты в /sdcard/Download/SniChecker. " +
                    "Разрешите доступ к файлам, чтобы приложение могло читать входной список и записывать отчеты."
            )
            .setCancelable(false)
            .setPositiveButton("Выдать разрешения") { _, _ ->
                requestRequiredStorageAccess()
            }
            .setNegativeButton("Закрыть") { _, _ ->
                finish()
            }
            .show()
    }

    private fun hasRequiredStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            readGranted && writeGranted
        }
    }

    private fun requestRequiredStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettingsIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

            runCatching { manageStorageAccess.launch(appSettingsIntent) }
                .onFailure { manageStorageAccess.launch(fallbackIntent) }
        } else {
            legacyStoragePermissions.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}
