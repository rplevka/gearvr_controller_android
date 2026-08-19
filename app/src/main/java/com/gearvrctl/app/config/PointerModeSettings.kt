package com.gearvrctl.app.config

/** Which button (if any), when held, turns a touchpad drag into a live scroll gesture instead
 * of moving the cursor. Mirrors [GearVrButton] plus a NONE option to disable scrolling. */
enum class ScrollTrigger { NONE, TRIGGER, HOME, BACK, VOLUME_UP, VOLUME_DOWN }

fun ScrollTrigger.toGearVrButtonOrNull(): GearVrButton? = when (this) {
    ScrollTrigger.NONE -> null
    ScrollTrigger.TRIGGER -> GearVrButton.TRIGGER
    ScrollTrigger.HOME -> GearVrButton.HOME
    ScrollTrigger.BACK -> GearVrButton.BACK
    ScrollTrigger.VOLUME_UP -> GearVrButton.VOLUME_UP
    ScrollTrigger.VOLUME_DOWN -> GearVrButton.VOLUME_DOWN
}

/** How gyro-aim coexists with the touchpad. */
enum class GyroMode {
    /** Gyro aims the cursor whenever the touchpad isn't being touched; touch takes over instantly. */
    FALLBACK,

    /** Only one of [ActivePointerSource] drives the cursor at a time, chosen explicitly. */
    EXPLICIT,
}

/**
 * ABSOLUTE_ORIENTATION is a "laser pointer" mode — a Madgwick-fused (gyro+accel+mag) absolute
 * orientation mapped straight to screen position — only meaningful in [GyroMode.EXPLICIT]:
 * mixing an absolute aim with the FALLBACK touchpad-blend model doesn't make UX sense.
 */
enum class ActivePointerSource { TOUCHPAD, GYRO, ABSOLUTE_ORIENTATION }
