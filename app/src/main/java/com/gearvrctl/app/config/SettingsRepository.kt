package com.gearvrctl.app.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gearvrctl.app.protocol.Vector3
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val touchpadSensitivity: Float,
    val touchpadAcceleration: Float,
    val buttonMapping: Map<GearVrButton, ButtonAction>,
    val gyroSensitivity: Float,
    val gyroDeadzone: Float,
    val scrollTrigger: ScrollTrigger,
    val gyroMode: GyroMode,
    val activePointerSource: ActivePointerSource,
    val motionSmoothingEnabled: Boolean,
    val magHardIronBias: Vector3,
    val orientationFovDegrees: Float,
)

/** Live-persisted app settings via Jetpack DataStore — replaces the Phase 4/5 hardcoded values. */
class SettingsRepository(private val context: Context) {

    private val sensitivityKey = floatPreferencesKey("touchpad_sensitivity")
    private val accelerationKey = floatPreferencesKey("touchpad_acceleration")
    private val gyroSensitivityKey = floatPreferencesKey("gyro_sensitivity")
    private val gyroDeadzoneKey = floatPreferencesKey("gyro_deadzone")
    private val scrollTriggerKey = stringPreferencesKey("scroll_trigger")
    private val gyroModeKey = stringPreferencesKey("gyro_mode")
    private val activePointerSourceKey = stringPreferencesKey("active_pointer_source")
    private val motionSmoothingEnabledKey = booleanPreferencesKey("motion_smoothing_enabled")
    private val magBiasXKey = floatPreferencesKey("mag_hard_iron_bias_x")
    private val magBiasYKey = floatPreferencesKey("mag_hard_iron_bias_y")
    private val magBiasZKey = floatPreferencesKey("mag_hard_iron_bias_z")
    private val orientationFovKey = floatPreferencesKey("orientation_fov_degrees")
    private fun buttonKey(button: GearVrButton) = stringPreferencesKey("button_${button.name}")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            touchpadSensitivity = prefs[sensitivityKey] ?: DEFAULT_SENSITIVITY,
            touchpadAcceleration = prefs[accelerationKey] ?: DEFAULT_ACCELERATION,
            buttonMapping = GearVrButton.entries.associateWith { button ->
                prefs[buttonKey(button)]
                    ?.let { stored -> runCatching { ButtonAction.valueOf(stored) }.getOrNull() }
                    ?: DEFAULT_BUTTON_MAPPING.getValue(button)
            },
            gyroSensitivity = prefs[gyroSensitivityKey] ?: DEFAULT_GYRO_SENSITIVITY,
            gyroDeadzone = prefs[gyroDeadzoneKey] ?: DEFAULT_GYRO_DEADZONE,
            scrollTrigger = prefs[scrollTriggerKey]?.let { runCatching { ScrollTrigger.valueOf(it) }.getOrNull() }
                ?: DEFAULT_SCROLL_TRIGGER,
            gyroMode = prefs[gyroModeKey]?.let { runCatching { GyroMode.valueOf(it) }.getOrNull() }
                ?: DEFAULT_GYRO_MODE,
            activePointerSource = prefs[activePointerSourceKey]
                ?.let { runCatching { ActivePointerSource.valueOf(it) }.getOrNull() }
                ?: DEFAULT_ACTIVE_POINTER_SOURCE,
            motionSmoothingEnabled = prefs[motionSmoothingEnabledKey] ?: DEFAULT_MOTION_SMOOTHING_ENABLED,
            magHardIronBias = Vector3(
                (prefs[magBiasXKey] ?: 0f).toDouble(),
                (prefs[magBiasYKey] ?: 0f).toDouble(),
                (prefs[magBiasZKey] ?: 0f).toDouble(),
            ),
            orientationFovDegrees = prefs[orientationFovKey] ?: DEFAULT_ORIENTATION_FOV_DEGREES,
        )
    }

    suspend fun setTouchpadSensitivity(value: Float) {
        context.dataStore.edit { it[sensitivityKey] = value }
    }

    suspend fun setTouchpadAcceleration(value: Float) {
        context.dataStore.edit { it[accelerationKey] = value }
    }

    suspend fun setButtonAction(button: GearVrButton, action: ButtonAction) {
        context.dataStore.edit { it[buttonKey(button)] = action.name }
    }

    suspend fun setGyroSensitivity(value: Float) {
        context.dataStore.edit { it[gyroSensitivityKey] = value }
    }

    suspend fun setGyroDeadzone(value: Float) {
        context.dataStore.edit { it[gyroDeadzoneKey] = value }
    }

    suspend fun setScrollTrigger(trigger: ScrollTrigger) {
        context.dataStore.edit { it[scrollTriggerKey] = trigger.name }
    }

    suspend fun setGyroMode(mode: GyroMode) {
        context.dataStore.edit { it[gyroModeKey] = mode.name }
    }

    suspend fun setActivePointerSource(source: ActivePointerSource) {
        context.dataStore.edit { it[activePointerSourceKey] = source.name }
    }

    suspend fun setMotionSmoothingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[motionSmoothingEnabledKey] = enabled }
    }

    suspend fun setMagHardIronBias(bias: Vector3) {
        context.dataStore.edit {
            it[magBiasXKey] = bias.x.toFloat()
            it[magBiasYKey] = bias.y.toFloat()
            it[magBiasZKey] = bias.z.toFloat()
        }
    }

    suspend fun setOrientationFovDegrees(value: Float) {
        context.dataStore.edit { it[orientationFovKey] = value }
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 6.0f
        const val DEFAULT_ACCELERATION = 0.0f
        const val DEFAULT_GYRO_SENSITIVITY = 15.0f
        const val DEFAULT_GYRO_DEADZONE = 1.5f
        val DEFAULT_SCROLL_TRIGGER = ScrollTrigger.TRIGGER
        val DEFAULT_GYRO_MODE = GyroMode.FALLBACK
        val DEFAULT_ACTIVE_POINTER_SOURCE = ActivePointerSource.TOUCHPAD
        const val DEFAULT_MOTION_SMOOTHING_ENABLED = false
        const val DEFAULT_ORIENTATION_FOV_DEGREES = 90f

        // TRIGGER defaults to NONE here — it's the default scroll-trigger modifier (see
        // DEFAULT_SCROLL_TRIGGER), so holding it to scroll shouldn't also fire Recents.
        val DEFAULT_BUTTON_MAPPING = mapOf(
            GearVrButton.TRIGGER to ButtonAction.NONE,
            GearVrButton.HOME to ButtonAction.HOME,
            GearVrButton.BACK to ButtonAction.BACK,
            GearVrButton.VOLUME_UP to ButtonAction.VOLUME_UP,
            GearVrButton.VOLUME_DOWN to ButtonAction.VOLUME_DOWN,
        )
    }
}
