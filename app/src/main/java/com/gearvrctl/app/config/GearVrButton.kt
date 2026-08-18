package com.gearvrctl.app.config

/** Remappable physical buttons. Touchpad-click is deliberately excluded — it's hardcoded as the
 * pointer's click trigger, the only click source until an alternate one is introduced. */
enum class GearVrButton { TRIGGER, HOME, BACK, VOLUME_UP, VOLUME_DOWN }

enum class ButtonAction { NONE, BACK, HOME, RECENTS, VOLUME_UP, VOLUME_DOWN, TAP }
