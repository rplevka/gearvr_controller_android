package com.gearvrctl.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.gearvrctl.app.GearVrApplication
import com.gearvrctl.app.ble.BlePermissions
import com.gearvrctl.app.config.AppSettings
import com.gearvrctl.app.config.GyroMode
import com.gearvrctl.app.config.ScrollTrigger
import com.gearvrctl.app.config.SettingsRepository
import com.gearvrctl.app.config.toGearVrButtonOrNull
import com.gearvrctl.app.input.GyroPointerMotionSource
import com.gearvrctl.app.input.PointerModeManager
import com.gearvrctl.app.input.TouchpadPointerMotionSource
import com.gearvrctl.app.protocol.ButtonState
import com.gearvrctl.app.protocol.GearVrPacketParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "GearVrAccessibility"

/**
 * Touchpad-drag (or gyro tilt, see [GyroMode]) moves an overlay cursor, touchpad-click taps at
 * the cursor's position, holding the configured [ScrollTrigger] button while dragging the
 * touchpad scrolls real content instead of just moving the cursor, and the other physical
 * buttons fire the user-configured action via [ButtonActionMapper]. Everything tunable is
 * live-loaded from [SettingsRepository] and applied without restarting the service.
 */
class GearVrAccessibilityService : AccessibilityService() {

    private val app get() = application as GearVrApplication
    private val repository get() = app.controllerRepository
    private val settingsRepository get() = app.settingsRepository
    private val overlay by lazy { CursorOverlayController(this) }
    private val touchpadSource = TouchpadPointerMotionSource()
    private val gyroSource = GyroPointerMotionSource()
    private val scrollController by lazy { ScrollGestureController(this) }
    private val buttonActionMapper by lazy {
        ButtonActionMapper(this, onTapRequested = { GestureDispatcher.tap(this, cursorX, cursorY) })
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentSettings = AppSettings(
        touchpadSensitivity = SettingsRepository.DEFAULT_SENSITIVITY,
        touchpadAcceleration = SettingsRepository.DEFAULT_ACCELERATION,
        buttonMapping = SettingsRepository.DEFAULT_BUTTON_MAPPING,
        gyroSensitivity = SettingsRepository.DEFAULT_GYRO_SENSITIVITY,
        gyroDeadzone = SettingsRepository.DEFAULT_GYRO_DEADZONE,
        scrollTrigger = SettingsRepository.DEFAULT_SCROLL_TRIGGER,
        gyroMode = SettingsRepository.DEFAULT_GYRO_MODE,
        activePointerSource = SettingsRepository.DEFAULT_ACTIVE_POINTER_SOURCE,
    )

    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0
    private var touchpadClickHeld = false
    private var scrolling = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")

        val (width, height) = overlay.screenSize()
        screenWidth = width
        screenHeight = height
        cursorX = width / 2f
        cursorY = height / 2f
        overlay.show()
        overlay.moveTo(cursorX, cursorY)

        if (BlePermissions.hasAll(this)) {
            repository.connect()
        } else {
            Log.w(TAG, "Missing Bluetooth permissions — open the app once to grant them")
        }

        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                currentSettings = settings
                touchpadSource.sensitivity = settings.touchpadSensitivity
                touchpadSource.acceleration = settings.touchpadAcceleration
                gyroSource.sensitivity = settings.gyroSensitivity
                gyroSource.deadzoneDegPerSec = settings.gyroDeadzone
                buttonActionMapper.mapping = settings.buttonMapping
            }
        }

        serviceScope.launch {
            repository.rawPackets.collect { bytes -> onPacket(bytes) }
        }
    }

    private fun onPacket(bytes: ByteArray) {
        val sample = GearVrPacketParser.parse(bytes)

        // Feed both sources every sample regardless of which drives the cursor right now, so
        // touchpad's last-touch position and gyro's calibration/bias stay warm for an instant
        // switch (see PointerModeManager).
        val touchpadDelta = touchpadSource.onSample(sample)
        val gyroDelta = gyroSource.onSample(sample)

        val scrollTriggerHeld = isHeld(sample.buttons, currentSettings.scrollTrigger)

        if (scrollTriggerHeld && sample.touching) {
            if (!scrolling) {
                scrolling = true
                scrollController.start(cursorX, cursorY)
            }
            touchpadDelta?.let { scrollController.extend(it.dx, it.dy) }
        } else {
            if (scrolling) {
                scrolling = false
                scrollController.end()
            }
            val delta = PointerModeManager.select(
                touching = sample.touching,
                touchpadDelta = touchpadDelta,
                gyroDelta = gyroDelta,
                gyroMode = currentSettings.gyroMode,
                activeSource = currentSettings.activePointerSource,
            )
            delta?.let {
                cursorX = (cursorX + it.dx).coerceIn(0f, screenWidth.toFloat())
                cursorY = (cursorY + it.dy).coerceIn(0f, screenHeight.toFloat())
                overlay.moveTo(cursorX, cursorY)
            }
        }

        val clicked = sample.buttons.touchpadClick
        if (touchpadClickHeld && !clicked) {
            GestureDispatcher.tap(this, cursorX, cursorY)
        }
        touchpadClickHeld = clicked

        buttonActionMapper.onButtons(sample.buttons, excluded = currentSettings.scrollTrigger.toGearVrButtonOrNull())
    }

    private fun isHeld(buttons: ButtonState, trigger: ScrollTrigger): Boolean = when (trigger) {
        ScrollTrigger.NONE -> false
        ScrollTrigger.TRIGGER -> buttons.trigger
        ScrollTrigger.HOME -> buttons.home
        ScrollTrigger.BACK -> buttons.back
        ScrollTrigger.VOLUME_UP -> buttons.volumeUp
        ScrollTrigger.VOLUME_DOWN -> buttons.volumeDown
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        overlay.hide()
    }
}
