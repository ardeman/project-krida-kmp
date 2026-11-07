package com.ardeman.krida

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ardeman.krida.congklak.CongklakScreen
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.nearby.NearbyRoom

/**
 * Menu: congklak pass-and-play on this device, or a nearby room over BLE (host or join),
 * iOS and Android alike. Then the lobby, then the board.
 */
class MainActivity : ComponentActivity() {

    private var roomCode by mutableStateOf("")
    private var room by mutableStateOf<NearbyRoom?>(null)
    private var passAndPlay by mutableStateOf<CongklakTable?>(null)
    private var permissionDenied by mutableStateOf(false)
    private var pendingAction: (() -> Unit)? = null

    private val myName: String get() = Build.MODEL ?: "Android"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionDenied = !result.values.all { it }
            if (!permissionDenied) pendingAction?.invoke()
            pendingAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { RoomCode.parse(it) }?.let { roomCode = it }
        setContent {
            MaterialTheme {
                val r = room
                val table = passAndPlay ?: r?.table
                when {
                    table != null -> CongklakScreen(table, onExit = { passAndPlay = null; room = null })
                    r != null -> Lobby(r)
                    else -> Menu()
                }
            }
        }
    }

    override fun onDestroy() {
        room?.leave()
        super.onDestroy()
    }

    @Composable
    private fun Menu() {
        val names = listOf(stringResource(R.string.congklak_player_1), stringResource(R.string.congklak_player_2))
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.krida_wordmark), contentDescription = stringResource(R.string.app_name))
            Button(onClick = { passAndPlay = HostTable.passAndPlay(names) }) {
                Text(stringResource(R.string.congklak_pass_and_play))
            }
            Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { withBlePermissions { room = NearbyRoom.host(this@MainActivity, myName) } }) {
                Text(stringResource(R.string.nearby_host))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = roomCode,
                    onValueChange = { roomCode = it.uppercase().take(4) },
                    label = { Text(stringResource(R.string.nearby_code)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { withBlePermissions { room = NearbyRoom.join(this@MainActivity, roomCode, myName) } },
                    enabled = RoomCode.isValid(roomCode),
                ) { Text(stringResource(R.string.nearby_join)) }
            }
            if (permissionDenied) Text(stringResource(R.string.nearby_permission_denied), color = MaterialTheme.colorScheme.error)
        }
    }

    @Composable
    private fun Lobby(r: NearbyRoom) {
        fun leave() { r.leave(); room = null }
        BackHandler(onBack = ::leave)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.congklak_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.nearby_room_code), style = MaterialTheme.typography.labelLarge)
            Text(r.code, style = MaterialTheme.typography.displayMedium)
            Text(stringResource(r.status))
            r.players.forEach { p ->
                Text("${p.seat + 1}. ${p.name}", style = MaterialTheme.typography.bodyLarge)
            }
            if (r.isHost) {
                Button(onClick = r::start, enabled = r.canStart) { Text(stringResource(R.string.nearby_start)) }
            }
            TextButton(onClick = ::leave) { Text(stringResource(R.string.congklak_back)) }
        }
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
