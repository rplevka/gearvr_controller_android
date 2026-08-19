package com.gearvrctl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gearvrctl.app.ble.ControllerRepository
import com.gearvrctl.app.config.ActivePointerSource
import com.gearvrctl.app.config.AppSettings
import com.gearvrctl.app.config.ButtonAction
import com.gearvrctl.app.config.GearVrButton
import com.gearvrctl.app.config.GyroMode
import com.gearvrctl.app.config.ScrollTrigger
import com.gearvrctl.app.config.SettingsRepository
import com.gearvrctl.app.input.MagnetometerCalibrator
import com.gearvrctl.app.protocol.GearVrPacketParser
import com.gearvrctl.app.protocol.Vector3
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ACCELERATION_RANGE = 0f..0.3f
private val SENSITIVITY_RANGE = 1f..15f
private val GYRO_SENSITIVITY_RANGE = 1f..40f
private val GYRO_DEADZONE_RANGE = 0f..5f
private val FOV_RANGE = 30f..150f
private const val MAG_CALIBRATION_COUNTDOWN_SECONDS = 3
private const val MAG_CALIBRATION_RECORD_SECONDS = 10

@Composable
fun SettingsScreen(settingsRepository: SettingsRepository, controllerRepository: ControllerRepository) {
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(
        initial = AppSettings(
            touchpadSensitivity = SettingsRepository.DEFAULT_SENSITIVITY,
            touchpadAcceleration = SettingsRepository.DEFAULT_ACCELERATION,
            buttonMapping = SettingsRepository.DEFAULT_BUTTON_MAPPING,
            gyroSensitivity = SettingsRepository.DEFAULT_GYRO_SENSITIVITY,
            gyroDeadzone = SettingsRepository.DEFAULT_GYRO_DEADZONE,
            scrollTrigger = SettingsRepository.DEFAULT_SCROLL_TRIGGER,
            gyroMode = SettingsRepository.DEFAULT_GYRO_MODE,
            activePointerSource = SettingsRepository.DEFAULT_ACTIVE_POINTER_SOURCE,
            motionSmoothingEnabled = SettingsRepository.DEFAULT_MOTION_SMOOTHING_ENABLED,
            magHardIronBias = Vector3(0.0, 0.0, 0.0),
            orientationFovDegrees = SettingsRepository.DEFAULT_ORIENTATION_FOV_DEGREES,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Touchpad Pointer", style = MaterialTheme.typography.titleLarge)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.motionSmoothingEnabled,
                onCheckedChange = { scope.launch { settingsRepository.setMotionSmoothingEnabled(it) } },
            )
            Text("Smooth motion between BLE bursts (may add slight lag; try both)")
        }

        Text("Sensitivity: %.1f".format(settings.touchpadSensitivity))
        Slider(
            value = settings.touchpadSensitivity,
            valueRange = SENSITIVITY_RANGE,
            onValueChange = { scope.launch { settingsRepository.setTouchpadSensitivity(it) } },
        )

        Text("Acceleration: %.2f".format(settings.touchpadAcceleration))
        Slider(
            value = settings.touchpadAcceleration,
            valueRange = ACCELERATION_RANGE,
            onValueChange = { scope.launch { settingsRepository.setTouchpadAcceleration(it) } },
        )

        Text("Gyro Pointer", style = MaterialTheme.typography.titleLarge)

        EnumDropdownRow(
            label = "Mode",
            current = settings.gyroMode,
            options = GyroMode.entries,
            onSelect = { mode -> scope.launch { settingsRepository.setGyroMode(mode) } },
        )
        if (settings.gyroMode == GyroMode.EXPLICIT) {
            EnumDropdownRow(
                label = "Active source",
                current = settings.activePointerSource,
                options = ActivePointerSource.entries,
                onSelect = { source -> scope.launch { settingsRepository.setActivePointerSource(source) } },
            )
        }

        Text("Gyro sensitivity: %.1f".format(settings.gyroSensitivity))
        Slider(
            value = settings.gyroSensitivity,
            valueRange = GYRO_SENSITIVITY_RANGE,
            onValueChange = { scope.launch { settingsRepository.setGyroSensitivity(it) } },
        )

        Text("Gyro deadzone: %.1f deg/s".format(settings.gyroDeadzone))
        Slider(
            value = settings.gyroDeadzone,
            valueRange = GYRO_DEADZONE_RANGE,
            onValueChange = { scope.launch { settingsRepository.setGyroDeadzone(it) } },
        )

        Text("Orientation (Absolute Aim)", style = MaterialTheme.typography.titleLarge)
        Text("Select \"Active source\" = ABSOLUTE_ORIENTATION above (with Mode = EXPLICIT) to use it.")

        Text("Field of view: %.0f°".format(settings.orientationFovDegrees))
        Slider(
            value = settings.orientationFovDegrees,
            valueRange = FOV_RANGE,
            onValueChange = { scope.launch { settingsRepository.setOrientationFovDegrees(it) } },
        )

        MagnetometerCalibrationButton(
            controllerRepository = controllerRepository,
            onCalibrated = { bias -> scope.launch { settingsRepository.setMagHardIronBias(bias) } },
        )

        Text("Scrolling", style = MaterialTheme.typography.titleLarge)
        EnumDropdownRow(
            label = "Scroll trigger button",
            current = settings.scrollTrigger,
            options = ScrollTrigger.entries,
            onSelect = { trigger -> scope.launch { settingsRepository.setScrollTrigger(trigger) } },
        )

        Text("Buttons", style = MaterialTheme.typography.titleLarge)
        GearVrButton.entries.forEach { button ->
            EnumDropdownRow(
                label = button.name,
                current = settings.buttonMapping[button] ?: ButtonAction.NONE,
                options = ButtonAction.entries,
                onSelect = { action -> scope.launch { settingsRepository.setButtonAction(button, action) } },
            )
        }
    }
}

private sealed interface MagCalibrationState {
    data object Idle : MagCalibrationState
    data class Countdown(val secondsLeft: Int) : MagCalibrationState
    data class Recording(val secondsLeft: Int) : MagCalibrationState
    data object Done : MagCalibrationState
}

@Composable
private fun MagnetometerCalibrationButton(controllerRepository: ControllerRepository, onCalibrated: (Vector3) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MagCalibrationState>(MagCalibrationState.Idle) }

    Column {
        when (val s = state) {
            is MagCalibrationState.Idle -> {
                Button(onClick = {
                    scope.launch {
                        for (secondsLeft in MAG_CALIBRATION_COUNTDOWN_SECONDS downTo 1) {
                            state = MagCalibrationState.Countdown(secondsLeft)
                            delay(1000)
                        }

                        val calibrator = MagnetometerCalibrator()
                        calibrator.start()
                        val collectJob = launch {
                            controllerRepository.rawPackets.collect { bytes ->
                                if (bytes.size == GearVrPacketParser.PACKET_SIZE) {
                                    calibrator.sample(GearVrPacketParser.parse(bytes).magnetometerUt)
                                }
                            }
                        }
                        for (secondsLeft in MAG_CALIBRATION_RECORD_SECONDS downTo 1) {
                            state = MagCalibrationState.Recording(secondsLeft)
                            delay(1000)
                        }
                        collectJob.cancel()

                        onCalibrated(calibrator.finish())
                        state = MagCalibrationState.Done
                    }
                }) {
                    Text("Calibrate Magnetometer")
                }
            }
            is MagCalibrationState.Countdown -> {
                Text("Get ready: slowly rotate the controller through all orientations (figure-8 works well)")
                Text("Starting in ${s.secondsLeft}...", style = MaterialTheme.typography.titleMedium)
            }
            is MagCalibrationState.Recording -> {
                Text("Rotating now! ${s.secondsLeft}s left", style = MaterialTheme.typography.titleMedium)
            }
            is MagCalibrationState.Done -> {
                Text("Calibrated.")
                Button(onClick = { state = MagCalibrationState.Idle }) { Text("Recalibrate") }
            }
        }
    }
}

@Composable
private fun <T : Enum<T>> EnumDropdownRow(label: String, current: T, options: List<T>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.padding(top = 12.dp))
        Column {
            Button(onClick = { expanded = true }) { Text(current.name) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
