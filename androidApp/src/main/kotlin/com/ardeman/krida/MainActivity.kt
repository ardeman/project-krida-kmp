package com.ardeman.krida

import android.Manifest
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ardeman.krida.congklak.CongklakScreen
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.games.CongklakBot
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.nearby.NearbyRoom
import com.ardeman.krida.ui.IconTile
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.Theme

/**
 * Menu: congklak pass-and-play or vs computer on this device, or a nearby room over BLE (host or join),
 * iOS and Android alike. Then the lobby, then the board.
 */
class MainActivity : ComponentActivity() {

    private var roomCode by mutableStateOf("")
    private var joining by mutableStateOf(false)
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
        intent?.data?.toString()?.let { RoomCode.parse(it) }?.let { roomCode = it; joining = true }
        setContent {
            val r = room
            val table = passAndPlay ?: r?.table
            when {
                table != null -> CongklakScreen(table, onExit = { passAndPlay = null; room = null })
                r != null -> Lobby(r)
                else -> Menu()
            }
        }
    }

    override fun onDestroy() {
        room?.leave()
        super.onDestroy()
    }

    @Composable
    private fun Menu() {
        Row(
            Modifier.fillMaxSize().background(Theme.cream).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.width(236.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(
                    rememberLightWordmark(),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(40.dp),
                    alignment = Alignment.CenterStart,
                )
                CongklakCard(Modifier.weight(1f))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PassAndPlayCard()
                VsComputerCard()
                NearbyCard()
            }
        }
    }

    @Composable
    private fun PassAndPlayCard() {
        val names = listOf(stringResource(R.string.congklak_player_1), stringResource(R.string.congklak_player_2))
        ModeCard(R.drawable.ic_people, onClick = { passAndPlay = HostTable.passAndPlay(names) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.mode_pass_title), style = Theme.font(18, color = Theme.ink))
                Text(stringResource(R.string.mode_pass_detail), style = Theme.font(14, FontWeight.SemiBold, Theme.muted))
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Theme.muted)
        }
    }

    @Composable
    private fun VsComputerCard() {
        var level by rememberSaveable { mutableStateOf(CongklakBot.Level.NORMAL) }
        val you = stringResource(R.string.bot_you)
        val computer = stringResource(R.string.bot_name)
        val levelName = stringResource(levelLabel(level))
        ModeCard(R.drawable.ic_computer) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bot_title), style = Theme.font(18, color = Theme.ink), maxLines = 1)
                LevelPicker(level) { level = it }
            }
            PrimaryButton(
                stringResource(R.string.bot_start),
                onClick = { passAndPlay = HostTable.vsComputer(you, computer, level, levelName) },
                height = 48.dp,
            )
        }
    }

    @Composable
    private fun NearbyCard() {
        ModeCard(R.drawable.ic_bluetooth) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nearby_title), style = Theme.font(18, color = Theme.ink), maxLines = 1)
                    Text(
                        stringResource(R.string.nearby_detail),
                        style = Theme.font(14, FontWeight.SemiBold, Theme.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (joining) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CodeField(Modifier.weight(1f))
                        val cancel = stringResource(R.string.nearby_cancel)
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Theme.track)
                                .clickable(role = Role.Button, onClickLabel = cancel) { joining = false }
                                .semantics { contentDescription = cancel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = Theme.muted, modifier = Modifier.size(18.dp))
                        }
                        PrimaryButton(
                            stringResource(R.string.nearby_join),
                            onClick = { withBlePermissions { room = NearbyRoom.join(this@MainActivity, roomCode, myName) } },
                            enabled = RoomCode.isValid(roomCode),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            stringResource(R.string.nearby_host),
                            onClick = { withBlePermissions { room = NearbyRoom.host(this@MainActivity, myName) } },
                        )
                        SecondaryButton(stringResource(R.string.nearby_join_with_code), onClick = { joining = true })
                    }
                }
                if (permissionDenied) {
                    Text(stringResource(R.string.nearby_permission_denied), style = Theme.font(14, FontWeight.Bold, Theme.error))
                }
            }
        }
    }

    @Composable
    private fun CodeField(modifier: Modifier) {
        val hint = stringResource(R.string.nearby_code_hint)
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(
            value = roomCode,
            onValueChange = { roomCode = it.uppercase().filter(Char::isLetterOrDigit).take(4) },
            singleLine = true,
            textStyle = Theme.font(17, color = Theme.ink).copy(letterSpacing = 2.sp),
            cursorBrush = SolidColor(Theme.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = modifier.height(44.dp).semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxSize().background(Color(0xFFFFFDF9), shape).border(1.5.dp, Theme.border, shape).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (roomCode.isEmpty()) Text(stringResource(R.string.nearby_code), style = Theme.font(17, FontWeight.SemiBold, Theme.muted))
                    inner()
                }
            },
        )
    }

    @Composable
    private fun Lobby(r: NearbyRoom) {
        fun leave() { r.leave(); room = null }
        BackHandler(onBack = ::leave)
        Column(
            Modifier.fillMaxSize().background(Theme.cream).safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.congklak_title), style = Theme.font(24, FontWeight.Black, Theme.ink))
            Text(stringResource(R.string.nearby_room_code), style = Theme.font(15, FontWeight.SemiBold, Theme.muted))
            Text(r.code, style = Theme.font(44, FontWeight.Bold, Theme.ink).copy(fontFamily = FontFamily.Monospace))
            Text(stringResource(r.status), style = Theme.font(16, FontWeight.SemiBold, Theme.ink))
            r.players.forEach { p ->
                Text("${p.seat + 1}. ${p.name}", style = Theme.font(16, FontWeight.SemiBold, Theme.ink))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.isHost) PrimaryButton(stringResource(R.string.nearby_start), onClick = r::start, enabled = r.canStart)
                SecondaryButton(stringResource(R.string.congklak_back), onClick = ::leave)
            }
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

/** The wordmark in its light-mode colors: the menu stays cream even when the system is dark. */
@Composable
private fun rememberLightWordmark(): ImageVector {
    val context = LocalContext.current
    return remember(context) {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        ImageVector.vectorResource(null, context.createConfigurationContext(config).resources, R.drawable.krida_wordmark)
    }
}

private fun levelLabel(level: CongklakBot.Level) = when (level) {
    CongklakBot.Level.EASY -> R.string.bot_easy
    CongklakBot.Level.HARD -> R.string.bot_hard
    else -> R.string.bot_normal
}

/** White rounded card with an icon tile, used for each way to play. */
@Composable
private fun ModeCard(icon: Int, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(shape).background(Color.White).border(1.5.dp, Theme.line, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        content()
    }
}

/** Mudah / Sedang / Sulit segmented picker. */
@Composable
private fun LevelPicker(level: CongklakBot.Level, onChange: (CongklakBot.Level) -> Unit) {
    val label = stringResource(R.string.bot_level)
    Row(
        Modifier.background(Theme.track, RoundedCornerShape(12.dp)).padding(3.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(CongklakBot.Level.EASY, CongklakBot.Level.NORMAL, CongklakBot.Level.HARD).forEach { value ->
            val on = value == level
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.height(38.dp)
                    .then(if (on) Modifier.shadow(1.5.dp, shape) else Modifier)
                    .clip(shape)
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(value) }
                    .semantics { selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(levelLabel(value)), style = Theme.font(14, color = if (on) Theme.ink else Theme.muted))
            }
        }
    }
}

/** The congklak tile on the home screen: a flat drawing of the board. */
@Composable
private fun CongklakCard(modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().background(Theme.woodShadow, shape)) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 6.dp).background(Theme.wood, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.congklak_title), style = Theme.font(22, FontWeight.Black, Color(0xFFFFF6E8)))
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.congklak_card_players), style = Theme.font(13, FontWeight.Bold, Color(0xFFF3DDBE)))
            }
            MiniBoard()
            Text(stringResource(R.string.congklak_card_tagline), style = Theme.font(13, FontWeight.SemiBold, Color(0xFFF3DDBE)))
        }
    }
}

/** Two rows of seven holes between two stores, drawn flat. */
@Composable
private fun ColumnScope.MiniBoard() {
    Canvas(
        Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(204f / 80f).clearAndSetSemantics {},
    ) {
        val u = size.width / 204f
        drawRoundRect(Theme.woodDark, cornerRadius = CornerRadius(size.height / 2))
        val storeW = 18 * u
        val storeH = 56 * u
        val padX = 10 * u
        val top = (size.height - storeH) / 2
        drawRoundRect(Theme.pit, Offset(padX, top), Size(storeW, storeH), CornerRadius(12 * u))
        drawRoundRect(Theme.pit, Offset(size.width - padX - storeW, top), Size(storeW, storeH), CornerRadius(12 * u))
        val innerL = padX + storeW + 8 * u
        val innerR = size.width - padX - storeW - 8 * u
        val gap = 5 * u
        val d = (innerR - innerL - 6 * gap) / 7
        val rowGap = 6 * u
        val y0 = (size.height - (2 * d + rowGap)) / 2
        val seed = 5 * u
        for (row in 0..1) for (i in 0 until 7) {
            val c = Offset(innerL + i * (d + gap) + d / 2, y0 + row * (d + rowGap) + d / 2)
            drawCircle(Theme.pit, d / 2, c)
            drawRoundRect(Theme.seed, Offset(c.x - seed / 2, c.y - seed / 2), Size(seed, seed), CornerRadius(1.5f * u))
        }
    }
}
