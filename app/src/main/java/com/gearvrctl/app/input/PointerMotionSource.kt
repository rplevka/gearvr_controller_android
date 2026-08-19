package com.gearvrctl.app.input

import com.gearvrctl.app.protocol.GearVrSample

/** A source of relative pointer motion — touchpad drag or gyro tilt. */
interface PointerMotionSource {
    /** Returns a delta to apply to the cursor this sample, or null if nothing should move it. */
    fun onSample(sample: GearVrSample): PointerDelta?
}
