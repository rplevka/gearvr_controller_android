package com.gearvrctl.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gearvrctl.app.ble.BlePermissions
import com.gearvrctl.app.ble.ConnectionState
import com.gearvrctl.app.ble.ScanSighting
import com.gearvrctl.app.protocol.RawPacketLogger
import com.gearvrctl.app.ui.SettingsScreen

private const val MAX_LOGGED_PACKETS = 200

private enum class Screen { LOGGER, WIZARD, SETTINGS }

class MainActivity : ComponentActivity() {

    private val repository get() = (application as GearVrApplication).controllerRepository
    private val settingsRepository get() = (application as GearVrApplication).settingsRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) repository.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.LOGGER) }
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.padding(8.dp)) {
                            Button(onClick = { screen = Screen.LOGGER }) { Text("Dev Tools") }
                            Button(onClick = { screen = Screen.WIZARD }) { Text("Wizard") }
                            Button(onClick = { screen = Screen.SETTINGS }) { Text("Settings") }
                        }
                        when (screen) {
                            Screen.WIZARD -> CaptureWizardScreen(repository = repository, activityContext = this@MainActivity)
                            Screen.SETTINGS -> SettingsScreen(settingsRepository = settingsRepository)
                            Screen.LOGGER -> PacketLoggerScreen(
                                onConnectClick = {
                                    if (BlePermissions.hasAll(this@MainActivity)) {
                                        repository.connect()
                                    } else {
                                        permissionLauncher.launch(BlePermissions.required())
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PacketLoggerScreen(onConnectClick: () -> Unit) {
    val activityContext = LocalContext.current
    val app = activityContext.applicationContext as GearVrApplication
    val repository = app.controllerRepository

    val connectionState by repository.connectionState.collectAsState()
    val packets = remember { mutableStateListOf<String>() }
    var packetCount by remember { mutableStateOf(0) }
    var debugCaptureEnabled by remember { mutableStateOf(repository.debugCaptureEnabled) }
    val seenDevices = remember { mutableStateMapOf<String, ScanSighting>() }

    LaunchedEffect(debugCaptureEnabled) {
        if (!debugCaptureEnabled) return@LaunchedEffect
        repository.rawPackets.collect { bytes ->
            packetCount += 1
            packets.add(0, RawPacketLogger.summaryLine(packetCount, bytes))
            if (packets.size > MAX_LOGGED_PACKETS) {
                packets.removeAt(packets.lastIndex)
            }
        }
    }

    LaunchedEffect(Unit) {
        repository.scanLog.collect { sighting -> seenDevices[sighting.address] = sighting }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Status: ${connectionStateLabel(connectionState)}")
        Button(onClick = onConnectClick) {
            Text("Scan & Connect")
        }
        Button(onClick = {
            activityContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("Open Accessibility Settings")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = debugCaptureEnabled,
                onCheckedChange = {
                    debugCaptureEnabled = it
                    repository.debugCaptureEnabled = it
                },
            )
            Text("Debug packet capture (uses memory — leave off during normal use)")
        }
        Text(text = "Nearby BLE devices (${seenDevices.size}):")
        LazyColumn(
            modifier = Modifier.heightIn(max = 150.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(seenDevices.values.toList()) { sighting ->
                Text(
                    text = "${sighting.name ?: "(no name)"}  ${sighting.address}  rssi=${sighting.rssi}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(text = "Packets received: $packetCount")
        Button(onClick = { DumpExporter.share(activityContext, repository.captureText()) }) {
            Text("Save & Share Dump ($packetCount packets)")
        }
        Button(onClick = {
            repository.clearCapture()
            packets.clear()
            packetCount = 0
        }) {
            Text("Clear")
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(packets) { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun connectionStateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Idle -> "Idle"
    is ConnectionState.Scanning -> "Scanning…"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected — streaming"
    is ConnectionState.Disconnected -> "Disconnected (${state.reason})"
    is ConnectionState.Error -> "Error: ${state.message}"
}
