package com.slimecastle.bingohelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.slimecastle.bingohelper.ui.theme.BingoHelperTheme

class MainActivity : ComponentActivity() {

    private val overlayGranted = mutableStateOf(false)
    private val accessibilityEnabled = mutableStateOf(false)
    private val notificationsGranted = mutableStateOf(true)
    private val serviceRunning = mutableStateOf(false)
    private val resetMode = mutableStateOf(AppPrefs.RESET_MODE_X1)
    private val calibrationSummary = mutableStateOf("нет")
    private val calibrationHasBoard = mutableStateOf(false)
    private val calibrationReady = mutableStateOf(false)
    private val missingCalibration = mutableStateOf("поле, кнопки перезапуска")
    private val message = mutableStateOf("")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayService.start(this, result.resultCode, data)
            serviceRunning.value = true
            message.value = "Меню запущено. Переключитесь в игру."
            // Return to the app that was open before (usually the game).
            moveTaskToBack(true)
        } else {
            message.value = "Захват экрана не разрешён."
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted.value = granted
        message.value = if (granted) {
            "Уведомления разрешены. Теперь можно запустить меню."
        } else {
            "Уведомления не разрешены — сначала выдайте разрешение."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        resetMode.value = AppPrefs.resetMode(this)

        setContent {
            BingoHelperTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        overlayGranted = overlayGranted.value,
                        accessibilityEnabled = accessibilityEnabled.value,
                        notificationsGranted = notificationsGranted.value,
                        needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        serviceRunning = serviceRunning.value,
                        resetModeX10 = resetMode.value == AppPrefs.RESET_MODE_X10,
                        calibrationSummary = calibrationSummary.value,
                        calibrationHasBoard = calibrationHasBoard.value,
                        calibrationReady = calibrationReady.value,
                        missingCalibration = missingCalibration.value,
                        message = message.value,
                        onGrantOverlay = ::openOverlaySettings,
                        onOpenAccessibility = ::openAccessibilitySettings,
                        onGrantNotifications = ::requestNotifications,
                        onResetModeChange = ::setResetMode,
                        onResetCalibration = ::resetCalibration,
                        onStart = ::startHelper,
                        onStop = ::stopHelper,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityEnabled.value = TapAccessibilityService.isConnected()
        notificationsGranted.value = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        serviceRunning.value = OverlayService.isRunning
        resetMode.value = AppPrefs.resetMode(this)

        val calibration = AppPrefs.calibration(this)
        if (calibration == null) {
            calibrationSummary.value = "нет"
            calibrationHasBoard.value = false
            calibrationReady.value = false
            missingCalibration.value = "поле, кнопки перезапуска"
            return
        }

        calibrationSummary.value = buildString {
            append(if (calibration.hasBoard) "поле 25 клеток" else "поле не распознано")
            append("; перезапуск: ")
            append(if (calibration.hasReset) "2 кнопки" else "нет")
        }
        calibrationHasBoard.value = calibration.hasBoard
        calibrationReady.value = calibration.ready
        missingCalibration.value = calibration.missingLabels().joinToString(", ")
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        openSettingsInSeparateTask(intent)
    }

    private fun openAccessibilitySettings() {
        openSettingsInSeparateTask(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openSettingsInSeparateTask(intent: Intent) {
        // Keep Settings out of the launcher task: reopening the app from its icon
        // should show MainActivity, not the Settings screen left on top of it.
        startActivity(intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        })
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setResetMode(useX10: Boolean) {
        val mode = if (useX10) AppPrefs.RESET_MODE_X10 else AppPrefs.RESET_MODE_X1
        AppPrefs.setResetMode(this, mode)
        resetMode.value = mode
    }

    private fun resetCalibration() {
        OverlayService.clearCalibration(this)
        message.value = "Калибровка удалена. Распознайте состояния заново в плавающей панели."
        refreshState()
    }

    private fun startHelper() {
        if (OverlayService.isRunning) {
            serviceRunning.value = true
            message.value = "Меню уже запущено."
            moveTaskToBack(true)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            message.value = "Сначала разрешите показ поверх других приложений."
            openOverlaySettings()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            message.value = "Сначала разрешите уведомления через кнопку в списке разрешений."
            return
        }

        launchProjectionRequest()
    }

    private fun launchProjectionRequest() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopHelper() {
        OverlayService.stop(this)
        serviceRunning.value = false
        message.value = "Меню остановлено."
    }
}

@Composable
private fun MainScreen(
    overlayGranted: Boolean,
    accessibilityEnabled: Boolean,
    notificationsGranted: Boolean,
    needsNotificationPermission: Boolean,
    serviceRunning: Boolean,
    resetModeX10: Boolean,
    calibrationSummary: String,
    calibrationHasBoard: Boolean,
    calibrationReady: Boolean,
    missingCalibration: String,
    message: String,
    onGrantOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onGrantNotifications: () -> Unit,
    onResetModeChange: (Boolean) -> Unit,
    onResetCalibration: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canStart = overlayGranted && notificationsGranted

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "BingoHelper",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Подсказчик и автокликер для мини-игры Bingo. Меню работает поверх игры, " +
                "распознаёт поле по сохранённым координатам и либо показывает следующий ход, " +
                "либо нажимает сам.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "1. Показ поверх других приложений",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (overlayGranted) "Разрешение выдано" else "Разрешение не выдано",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onGrantOverlay,
                    enabled = !overlayGranted,
                ) {
                    Text(if (overlayGranted) "Разрешено" else "Открыть настройки")
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "2. Спец. возможности (для автокликера)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (accessibilityEnabled) {
                        "Служба включена"
                    } else {
                        "Служба выключена — подсказка доступна, но автокликер не запустится."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onOpenAccessibility,
                    enabled = !accessibilityEnabled,
                ) {
                    Text(if (accessibilityEnabled) "Включена" else "Открыть спец. возможности")
                }
                Text(
                    text = "Для автокликера выберите «BingoHelper» в списке и включите службу.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (needsNotificationPermission) Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("3. Уведомления", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (notificationsGranted) "Разрешение выдано" else
                        "Разрешите уведомление фоновой службы — без него меню не запустится.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onGrantNotifications, enabled = !notificationsGranted) {
                    Text(if (notificationsGranted) "Разрешено" else "Разрешить уведомления")
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (needsNotificationPermission) "4. Запуск меню" else "3. Запуск меню",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (canStart) {
                        "При запуске система спросит разрешение на захват экрана — " +
                            "оно нужно, чтобы читать картинку игры. " +
                            if (accessibilityEnabled) {
                                "Автокликер доступен после калибровки."
                            } else {
                                "Без службы «BingoHelper» доступны только подсказки; " +
                                    "автокликер запустить нельзя."
                            }
                    } else {
                        "Запуск недоступен: " +
                            when {
                                !overlayGranted -> "нет разрешения «Показ поверх других приложений»."
                                else -> "не разрешены уведомления фоновой службы."
                            }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onStart,
                    enabled = canStart,
                ) {
                    Text("Запустить меню и захват экрана")
                }
                if (serviceRunning) {
                    OutlinedButton(onClick = onStop) {
                        Text("Остановить меню")
                    }
                }
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (needsNotificationPermission) "5. Калибровка" else "4. Калибровка",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = calibrationSummary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (calibrationReady) {
                        "Поле и кнопки перезапуска распознаны — подсказка доступна; " +
                            "для автокликов также нужна служба «BingoHelper»."
                    } else if (calibrationHasBoard) {
                        "Поле распознано — подсказка доступна. Для автокликов распознайте " +
                            "кнопки перезапуска на экране после 8 ходов и включите службу «BingoHelper»."
                    } else {
                        "Для подсказки нужно распознать поле до первого хода. " +
                            "Для автокликов дополнительно нужны кнопки перезапуска " +
                            "на экране после 8 ходов. Пока не хватает: $missingCalibration."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onResetCalibration) {
                    Text("Сбросить калибровку")
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (needsNotificationPermission) "6. Перезапуск игры" else "5. Перезапуск игры",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = resetModeX10,
                        onCheckedChange = onResetModeChange,
                    )
                    Text(
                        text = if (resetModeX10) "Режим ×10" else "Режим ×1",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = "Режим можно поменять и в плавающей панели во время работы.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            text = "Как пользоваться: запустите меню, перейдите в игру и распознайте поле " +
                "для подсказки. Для автокликов также распознайте кнопки перезапуска " +
                "и включите службу «BingoHelper». Автоклики можно запустить с любого момента игры: обычное поле — " +
                "клик по лучшей клетке, неизвестный экран — безопасный клик вниз-вправо, экран " +
                "конца раунда — кнопка перезапуска выбранного режима.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
