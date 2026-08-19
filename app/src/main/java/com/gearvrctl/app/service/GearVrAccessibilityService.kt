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
import com.gearvrctl.app.input.AbsoluteOrientationSource
import com.gearvrctl.app.input.DeltaSmoother
import com.gearvrctl.app.input.GyroPointerMotionSource
import com.gearvrctl.app.input.PointerModeManager
import com.gearvrctl.app.input.TouchpadPointerMotionSource
import com.gearvrctl.app.protocol.ButtonState
import com.gearvrctl.app.protocol.GearVrPacketParser
import com.gearvrctl.app.protocol.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "GearVrAccessibility"
private const val TICK_INTERVAL_MS = 16L

/**
 * Touchpad-drag (or gyro tilt, see [GyroMode]) moves an overlay cursor, touchpad-click taps at
 * the cursor's position, holding the configured [ScrollTrigger] button while dragging the
 * touchpad scrolls real content instead of just moving the cursor, and the other physical
 * buttons fire the user-configured action via [ButtonActionMapper]. Everything tunable is
 * live-loaded from [SettingsRepository] and applied without restarting the service.
 *
 * Confirmed on real hardware that the controller delivers touchpad/gyro data in ~12Hz bursts (a
 * firmware/BLE-scheduling limit, not fixable here) — [cursorSmoother]/[scrollSmoother] spread
 * each burst's delta across a steady ~60fps [tickLoop] instead of applying it all at once, which
 * otherwise looks like a jump followed by a freeze.
 */
class GearVrAccessibilityService : AccessibilityService() {

    private val app get() = application as GearVrApplication
    private val repository get() = app.controllerRepository
    private val settingsRepository get() = app.settingsRepository
    private val overlay by lazy { CursorOverlayController(this) }
    private val touchpadSource = TouchpadPointerMotionSource()
    private val gyroSource = GyroPointerMotionSource()
    private val absoluteOrientationSource = AbsoluteOrientationSource()
    private val scrollController by lazy { ScrollGestureController(this, screenWidth, screenHeight) }
    private val buttonActionMapper by lazy {
        ButtonActionMapper(this, onTapRequested = { GestureDispatcher.tap(this, cursorX, cursorY) })
    }
    private val cursorSmoother = DeltaSmoother()
    private val scrollSmoother = DeltaSmoother()
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
        motionSmoothingEnabled = SettingsRepository.DEFAULT_MOTION_SMOOTHING_ENABLED,
        magHardIronBias = Vector3(0.0, 0.0, 0.0),
        orientationFovDegrees = SettingsRepository.DEFAULT_ORIENTATION_FOV_DEGREES,
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
                absoluteOrientationSource.magHardIronBias = settings.magHardIronBias
                absoluteOrientationSource.fovDegrees = settings.orientationFovDegrees
                buttonActionMapper.mapping = settings.buttonMapping
            }
        }

        serviceScope.launch {
            repository.rawPackets.collect { bytes -> onPacket(bytes) }
        }

        serviceScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun onPacket(bytes: ByteArray) {
        // The controller occasionally sends non-sensor-report notifications on this same
        // characteristic (confirmed on real hardware: a 2-byte packet while testing VR mode) —
        // skip anything that isn't a full 60-byte report rather than crashing the whole service.
        if (bytes.size != GearVrPacketParser.PACKET_SIZE) return
        val sample = GearVrPacketParser.parse(bytes)

        // Feed both sources every sample regardless of which drives the cursor right now, so
        // touchpad's last-touch position and gyro's calibration/bias stay warm for an instant
        // switch (see PointerModeManager).
        val touchpadDelta = touchpadSource.onSample(sample)
        val gyroDelta = gyroSource.onSample(sample)
        absoluteOrientationSource.onSample(sample)

        val scrollTriggerHeld = isHeld(sample.buttons, currentSettings.scrollTrigger)

        val smoothingEnabled = currentSettings.motionSmoothingEnabled

        if (scrollTriggerHeld && sample.touching) {
            if (!scrolling) {
                scrolling = true
                cursorSmoother.reset()
                scrollController.start(cursorX, cursorY)
            }
            touchpadDelta?.let {
                if (smoothingEnabled) scrollSmoother.addDelta(it.dx, it.dy) else scrollController.extend(it.dx, it.dy)
            }
        } else {
            if (scrolling) {
                scrolling = false
                scrollSmoother.reset()
                scrollController.end()
            }
            val delta = PointerModeManager.select(
                touching = sample.touching,
                touchpadDelta = touchpadDelta,
                gyroDelta = gyroDelta,
                absoluteTarget = absoluteOrientationSource.currentTarget(screenWidth, screenHeight),
                cursorX = cursorX,
                cursorY = cursorY,
                gyroMode = currentSettings.gyroMode,
                activeSource = currentSettings.activePointerSource,
            )
            delta?.let {
                if (smoothingEnabled) {
                    cursorSmoother.addDelta(it.dx, it.dy)
                } else {
                    moveCursor(it.dx, it.dy)
                }
            }
        }

        val clicked = sample.buttons.touchpadClick
        if (touchpadClickHeld && !clicked) {
            GestureDispatcher.tap(this, cursorX, cursorY)
        }
        touchpadClickHeld = clicked

        buttonActionMapper.onButtons(sample.buttons, excluded = currentSettings.scrollTrigger.toGearVrButtonOrNull())
    }

    /** Drains the smoothers at a steady rate, independent of the BLE data's bursty arrival. */
    private fun tick() {
        cursorSmoother.tick()?.let { moveCursor(it.dx, it.dy) }
        if (scrolling) {
            scrollSmoother.tick()?.let { scrollController.extend(it.dx, it.dy) }
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
        overlay.moveTo(cursorX, cursorY)
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
