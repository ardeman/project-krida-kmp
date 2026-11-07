package com.ardeman.krida

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.ardeman.krida.ble.BleGuestTransport
import com.ardeman.krida.congklak.CongklakScreen
import com.ardeman.krida.ble.BleHostTransport
import com.ardeman.krida.core.games.CounterGame
import com.ardeman.krida.core.games.CounterMove
import com.ardeman.krida.core.games.CounterState
import com.ardeman.krida.core.protocol.Platform
import com.ardeman.krida.core.session.GuestSession
import com.ardeman.krida.core.session.HostSession
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.transport.Transport

/**
 * Connectivity test screen: host or join a BLE room and play the demo counter game
 * against another Android or iOS device. Replace with the real menu + 3D scenes later.
 */
class MainActivity : ComponentActivity() {

    private var transport: Transport? = null
    private var host: HostSession<CounterState, CounterMove>? = null
    private var guest: GuestSession<CounterState, CounterMove>? = null

    private var status by mutableStateOf("Siap main")
    private var roomCode by mutableStateOf("")
    private var players by mutableStateOf("")
    private var total by mutableStateOf<Int?>(null)
    private var myTurn by mutableStateOf(false)
    private var pendingAction: (() -> Unit)? = null
    private var playingCongklak by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) pendingAction?.invoke() else status = "Izin Bluetooth ditolak"
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { RoomCode.parse(it) }?.let { roomCode = it }
        setContent {
            MaterialTheme {
                if (playingCongklak) CongklakScreen(onExit = { playingCongklak = false }) else Screen()
            }
        }
    }

    override fun onDestroy() {
        transport?.close()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.krida_wordmark), contentDescription = stringResource(R.string.app_name))
            Text(status)
            if (players.isNotEmpty()) Text("Pemain: $players")

            if (host == null && guest == null) {
                Button(onClick = { playingCongklak = true }) { Text(stringResource(R.string.congklak_pass_and_play)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { withBlePermissions(::startHost) }) { Text("Buat room (BLE)") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it.uppercase().take(4) },
                        label = { Text("Kode room") },
                        singleLine = true,
                    )
                    Button(
                        onClick = { withBlePermissions(::startGuest) },
                        enabled = RoomCode.isValid(roomCode),
                    ) { Text("Gabung") }
                }
            }

            host?.let { h ->
                if (!h.started) {
                    Button(onClick = { h.start() }, enabled = h.canStart()) { Text("Mulai") }
                }
            }

            total?.let { t ->
                Text("Total: $t / 10", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    (1..3).forEach { n ->
                        Button(onClick = { play(n) }, enabled = myTurn) { Text("+$n") }
                    }
                }
            }
        }
    }

    private fun startHost() {
        val code = RoomCode.generate()
        val ble = BleHostTransport(this, code)
        val session = HostSession(CounterGame, ble, roomCode = code)
        session.addLocalPlayer(Build.MODEL ?: "Android", Platform.ANDROID)
        session.onPlayersChanged = { list -> players = list.joinToString { "${it.name}${if (it.connected) "" else " (putus)"}" } }
        session.onStateChanged = { s -> render(s, session.state?.let(CounterGame::currentTurn) == 0) }
        transport = ble
        host = session
        roomCode = code
        status = "Room $code — tunggu pemain lain…"
        ble.start()
    }

    private fun startGuest() {
        val code = roomCode
        val ble = BleGuestTransport(this, code)
        val session = GuestSession(CounterGame, ble, Build.MODEL ?: "Android", Platform.ANDROID)
        session.onLobby = { list -> players = list.joinToString { it.name }; status = "Masuk room $code" }
        session.onState = { s -> render(s, session.isMyTurn) }
        session.onRejected = { reason -> status = "Ditolak: $reason" }
        session.onInvalid = { reason -> status = "Langkah ditolak: $reason" }
        session.onDisconnected = { status = "Terputus dari host" }
        transport = ble
        guest = session
        status = "Mencari room $code…"
        ble.start()
    }

    private fun render(state: CounterState, isMyTurn: Boolean) {
        total = state.total
        myTurn = isMyTurn && !CounterGame.isFinished(state)
        status = when {
            CounterGame.isFinished(state) -> "Selesai! Pemain ${(state.turn + state.players - 1) % state.players + 1} menang"
            myTurn -> "Giliranmu"
            else -> "Giliran pemain ${state.turn + 1}"
        }
    }

    private fun play(n: Int) {
        host?.submitLocal(0, CounterMove(n))
        guest?.sendMove(CounterMove(n))
    }

    private fun withBlePermissions(action: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        pendingAction = action
        permissionLauncher.launch(needed)
    }
}
