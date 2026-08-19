# Backlog

## Absolute-orientation mode: continuous drift/spin bug
Full-minute capture (no isolated hold-still period) showed yaw/pitch cycling through a full
rotation sweep repeatedly, every ~8s, with no settled/static period — even when the controller
should have been at rest. Points to the Madgwick filter not correctly anchoring orientation via
accel+mag (uncorrected gyro bias, bad mag calibration, or a sign error in the accel correction
term causing sustained spin instead of convergence). Needs a static "leave it on the table, don't
touch" capture to confirm, then a fix, before axis-mapping tuning can even be evaluated.

## Samsung DeX mouse control
Cursor overlay only ever renders on the phone's own display; clicks (`dispatchGesture`) never
reach the DeX desktop display. Confirmed both empirically (cursor/click test with phone docked
into DeX) and via the installed Android SDK stubs (checked up to API 35: `dispatchGesture` has no
display-targeting overload anywhere in AOSP, and no `FLAG_ENABLE_ACCESSIBILITY_SUPPORT_FOR_ALL_DISPLAYS`
flag exists). This is a hard platform limitation for unprivileged apps, not a bug in this project.

Revisit for rooted phones: with root, inject directly via `/dev/uinput` (or `evdev`) targeting the
DeX virtual display's input device instead of going through `AccessibilityService.dispatchGesture`
— bypasses the display-routing limitation entirely since uinput events go straight to the input
subsystem, not through the accessibility gesture pipeline.
