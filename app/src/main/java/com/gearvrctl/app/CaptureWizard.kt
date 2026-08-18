package com.gearvrctl.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gearvrctl.app.ble.ControllerRepository
import com.gearvrctl.app.protocol.RawPacketLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class CaptureStep(
    val id: String,
    val instruction: String,
    val countdownSeconds: Int = 3,
    val recordSeconds: Int = 3,
)

val CAPTURE_STEPS = listOf(
    CaptureStep("idle", "Leave the controller resting, untouched"),
    CaptureStep("trigger", "Press and HOLD the trigger, release partway through"),
    CaptureStep("home", "Press the Home button once"),
    CaptureStep("back", "Press the Back button once"),
    CaptureStep("touchpad_tap", "Tap the touchpad once, dead center"),
    CaptureStep("touchpad_top_left", "Touch and hold the touchpad's TOP-LEFT corner"),
    CaptureStep("touchpad_bottom_right", "Touch and hold the touchpad's BOTTOM-RIGHT corner"),
    CaptureStep("touchpad_drag", "Slowly swipe left-to-right across the touchpad", recordSeconds = 4),
    CaptureStep("volume_up", "Press Volume Up once"),
    CaptureStep("volume_down", "Press Volume Down once"),
)

private sealed interface WizardPhase {
    data object NotStarted : WizardPhase
    data class Countdown(val step: CaptureStep, val secondsLeft: Int) : WizardPhase
    data class Recording(val step: CaptureStep, val secondsLeft: Int) : WizardPhase
    data object Finished : WizardPhase
}

@Composable
fun CaptureWizardScreen(repository: ControllerRepository, activityContext: Context) {
    var phase by remember { mutableStateOf<WizardPhase>(WizardPhase.NotStarted) }
    var running by remember { mutableStateOf(false) }
    val combined = remember { StringBuilder() }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        combined.clear()
        var globalIndex = 0

        for (step in CAPTURE_STEPS) {
            for (secondsLeft in step.countdownSeconds downTo 1) {
                phase = WizardPhase.Countdown(step, secondsLeft)
                delay(1000)
            }

            val stepLines = mutableListOf<String>()
            val collectJob = launch {
                repository.rawPackets.collect { bytes ->
                    globalIndex += 1
                    stepLines.add(RawPacketLogger.summaryLine(globalIndex, bytes))
                }
            }
            for (secondsLeft in step.recordSeconds downTo 1) {
                phase = WizardPhase.Recording(step, secondsLeft)
                delay(1000)
            }
            collectJob.cancel()

            combined.append("===== STEP: ${step.id} — ${step.instruction} =====\n")
            if (stepLines.isEmpty()) {
                combined.append("(no packets received during this window)\n")
            } else {
                stepLines.forEach { combined.append(it).append('\n') }
            }
            combined.append('\n')
        }

        phase = WizardPhase.Finished
        DumpExporter.share(activityContext, combined.toString())
        running = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val p = phase) {
            is WizardPhase.NotStarted -> {
                Text("Capture Wizard", style = MaterialTheme.typography.titleLarge)
                Text("Walks through ${CAPTURE_STEPS.size} labeled actions, counts down, records a few " +
                    "seconds of raw packets for each, then shares one combined dump file.")
                Button(onClick = { running = true }) { Text("Start Wizard") }
            }
            is WizardPhase.Countdown -> {
                Text("Get ready:", style = MaterialTheme.typography.titleMedium)
                Text(p.step.instruction, style = MaterialTheme.typography.bodyLarge)
                Text("${p.secondsLeft}", style = MaterialTheme.typography.displayLarge)
            }
            is WizardPhase.Recording -> {
                Text("GO — do it now:", style = MaterialTheme.typography.titleMedium)
                Text(p.step.instruction, style = MaterialTheme.typography.bodyLarge)
                Text("Recording… ${p.secondsLeft}s", style = MaterialTheme.typography.displayMedium)
            }
            is WizardPhase.Finished -> {
                Text("Done — combined dump shared.", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { phase = WizardPhase.NotStarted }) { Text("Run Again") }
            }
        }
    }
}
