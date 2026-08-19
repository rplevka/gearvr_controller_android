package com.gearvrctl.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import com.gearvrctl.app.config.ButtonAction
import com.gearvrctl.app.config.GearVrButton
import com.gearvrctl.app.config.SettingsRepository
import com.gearvrctl.app.protocol.ButtonState

/**
 * Fires the user-configured [ButtonAction] for each physical button's press edge (false→true
 * transition). Mapping is live-updatable (see [mapping]) so Settings changes apply immediately.
 */
class ButtonActionMapper(
    private val service: AccessibilityService,
    private val onTapRequested: () -> Unit,
) {

    var mapping: Map<GearVrButton, ButtonAction> = SettingsRepository.DEFAULT_BUTTON_MAPPING

    private val audioManager by lazy {
        service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var previous: ButtonState? = null

    /**
     * [excluded] is the button currently acting as the scroll-trigger modifier, if any — it's
     * skipped here so holding it to scroll doesn't also fire its mapped action. This lives here
     * rather than in the mapping itself since it's about interaction between two features.
     */
    fun onButtons(buttons: ButtonState, excluded: GearVrButton? = null) {
        val prev = previous
        previous = buttons
        if (prev == null) return

        if (excluded != GearVrButton.TRIGGER && !prev.trigger && buttons.trigger) fire(GearVrButton.TRIGGER)
        if (excluded != GearVrButton.HOME && !prev.home && buttons.home) fire(GearVrButton.HOME)
        if (excluded != GearVrButton.BACK && !prev.back && buttons.back) fire(GearVrButton.BACK)
        if (excluded != GearVrButton.VOLUME_UP && !prev.volumeUp && buttons.volumeUp) fire(GearVrButton.VOLUME_UP)
        if (excluded != GearVrButton.VOLUME_DOWN && !prev.volumeDown && buttons.volumeDown) fire(GearVrButton.VOLUME_DOWN)
    }

    private fun fire(button: GearVrButton) {
        when (mapping[button]) {
            ButtonAction.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ButtonAction.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ButtonAction.RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ButtonAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
            ButtonAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
            ButtonAction.TAP -> onTapRequested()
            ButtonAction.NONE, null -> Unit
        }
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }
}
