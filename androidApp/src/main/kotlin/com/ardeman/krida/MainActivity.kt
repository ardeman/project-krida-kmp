package com.ardeman.krida

import android.Manifest
import android.os.Build
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import com.ardeman.krida.ui.LandscapeStage
import com.ardeman.krida.ui.gameInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ardeman.krida.ads.Ads
import com.ardeman.krida.congklak.CongklakScreen
import com.ardeman.krida.congklak.CongklakSettings
import com.ardeman.krida.congklak.BoardPreview
import com.ardeman.krida.congklak.CongklakTable
import com.ardeman.krida.congklak.HostTable
import com.ardeman.krida.core.games.CongklakBot
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.nearby.NearbyRoom
import com.ardeman.krida.ui.Language
import com.ardeman.krida.ui.LanguagePicker
import com.ardeman.krida.ui.LoadingScreen
import com.ardeman.krida.ui.rememberLightWordmark
import com.ardeman.krida.ui.PrimaryButton
import com.ardeman.krida.ui.SecondaryButton
import com.ardeman.krida.ui.SoundToggles
import com.ardeman.krida.ui.Sounds
import com.ardeman.krida.ui.Theme
import kotlinx.coroutines.delay

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

    /** The menu card's board snapshot is on screen; ends the startup loading screen. */
    private var menuReady by mutableStateOf(false)

    private val myName: String get() = Build.MODEL ?: "Android"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionDenied = !result.values.all { it }
            if (!permissionDenied) pendingAction?.invoke()
            pendingAction = null
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Language.wrap(newBase))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and full-screen ads can bring the bars back; hide them again on return.
        if (hasFocus) hideSystemBars()
    }

    /** Full screen like a game: no status bar or navigation bar until the player swipes from an edge. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Sounds.init(this)
        // A language change recreates the activity, so this follows the picker.
        Sounds.english = Language.current(this) == Language.Lang.EN
        CongklakSettings.init(this)
        intent?.data?.toString()?.let { RoomCode.parse(it) }?.let { roomCode = it; joining = true }
        setContent {
            // The ad SDK and the banner's WebView take about a second to start on older phones:
            // start them once the loading screen is on screen, not before the first frame.
            LaunchedEffect(Unit) {
                withFrameNanos {}
                withFrameNanos {}
                Ads.init(this@MainActivity)
            }
            LandscapeStage {
                val r = room
                val table = passAndPlay ?: r?.table
                when {
                    table != null -> CongklakScreen(table, onExit = { passAndPlay = null; room = null })
                    r != null -> Lobby(r)
                    else -> Menu()
                }
                StartupCover()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Sounds.setActive(true)
        Ads.refresh()
    }

    override fun onStop() {
        Sounds.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        room?.leave()
        super.onDestroy()
    }

    /**
     * The loading screen at app start, over the menu while its board snapshot renders
     * underneath: up for at least [STARTUP_MIN_MS] so it doesn't flash, at most [STARTUP_MAX_MS]
     * in case the snapshot never comes (a deep link straight into a game, a slow GPU).
     */
    @Composable
    private fun StartupCover() {
        var minDone by remember { mutableStateOf(false) }
        var maxDone by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(STARTUP_MIN_MS)
            minDone = true
            delay(STARTUP_MAX_MS - STARTUP_MIN_MS)
            maxDone = true
        }
        AnimatedVisibility(
            visible = !(minDone && (menuReady || maxDone)),
            enter = EnterTransition.None,
            exit = fadeOut(tween(300)),
        ) { LoadingScreen() }
    }

    @Composable
    private fun Menu() {
        // Capped at the phone design's size (as on iOS), so a tall stage doesn't stretch the cards.
        Box(Modifier.fillMaxSize().background(Theme.cream).windowInsetsPadding(gameInsets), contentAlignment = Alignment.Center) { Row(
            Modifier.sizeIn(maxWidth = 874.dp, maxHeight = 460.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.width(236.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(
                    rememberLightWordmark(),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(40.dp),
                    alignment = Alignment.CenterStart,
                )
                CongklakCard(Modifier.weight(1f), onReady = { menuReady = true })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoundToggles()
                    Spacer(Modifier.weight(1f))
                    LanguagePicker()
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PlayCard()
                        AdButtons()
                    }
                    MoreBelowHint(scroll, Modifier.align(Alignment.BottomCenter))
                }
                Ads.Banner(trailing = { RemoveAdsButton() })
            }
        } }
    }

    /**
     * The cards can end exactly at the column's bottom edge, so nothing tells a first-time
     * player there is more below. While the column can still scroll: a cream fade over the
     * bottom edge and a chevron that scrolls to the end.
     */
    @Composable
    private fun MoreBelowHint(scroll: ScrollState, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val more = stringResource(R.string.menu_more)
        AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .background(Brush.verticalGradient(listOf(Theme.cream.copy(alpha = 0f), Theme.cream))),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.size(32.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White)
                        .clickable(role = Role.Button) { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
                        .semantics { contentDescription = more },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = Theme.accent, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    /** "Privasi iklan" where the consent rules require a way back into the form. */
    @Composable
    private fun AdButtons() {
        if (!Ads.privacyOptionsRequired) return
        SecondaryButton(stringResource(R.string.ads_privacy), onClick = { Ads.showPrivacyOptions(this@MainActivity) }, tint = Theme.muted)
    }

    /**
     * "Hapus iklan" beside the banner where the ad is: the cards above keep the full height.
     * The price shows in Play's purchase sheet, not on the button. Only while ads are on and
     * Play knows the product.
     */
    @Composable
    private fun RemoveAdsButton() {
        if (Ads.removeAdsPrice == null) return
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier.height(50.dp).clip(shape).background(Color.White).border(1.5.dp, Theme.accent, shape)
                .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); Ads.buyRemoveAds(this@MainActivity) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.shop_remove_ads), style = Theme.font(14, color = Theme.accent), maxLines = 1)
        }
    }

    /**
     * One card for every way to play congklak: pick the opponent (a friend on this phone, the
     * computer, or a friend nearby over Bluetooth); right below it what that means (or the
     * computer's level) with Mulai, and "Gabung pakai kode" for "Teman dekat"; then the tembak rule.
     */
    @Composable
    private fun PlayCard() {
        // A room link opens the app ready to join: start on "Teman dekat".
        var opponent by rememberSaveable { mutableStateOf(if (joining) Opponent.NEARBY else Opponent.PHONE) }
        var level by rememberSaveable { mutableStateOf(CongklakBot.Level.NORMAL) }
        val names = listOf(stringResource(R.string.congklak_player_1), stringResource(R.string.congklak_player_2))
        val you = stringResource(R.string.bot_you)
        val computer = stringResource(R.string.bot_name)
        val levelName = stringResource(levelLabel(level))
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Color.White).border(1.5.dp, Theme.line, shape)
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingRow(stringResource(R.string.play_opponent)) {
                Segmented(
                    options = Opponent.entries.map { it to stringResource(it.label) },
                    current = opponent,
                    label = stringResource(R.string.play_opponent),
                    onChange = { opponent = it; if (it != Opponent.NEARBY) joining = false },
                    icons = Opponent.entries.map { it.icon },
                    fill = true,
                )
            }
            // The action row: what this opponent means (or the computer's level), then the
            // buttons, right under the choice that changes them. "Gabung pakai kode" (only for
            // "Teman dekat") turns the row into the code field.
            SettingRow(if (opponent == Opponent.COMPUTER) stringResource(R.string.play_level) else "") {
                if (joining) JoinRow() else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        when (opponent) {
                            Opponent.COMPUTER -> LevelPicker(level) { level = it }
                            Opponent.PHONE -> Detail(stringResource(R.string.mode_pass_detail))
                            Opponent.NEARBY -> Detail(stringResource(R.string.nearby_detail))
                        }
                    }
                    if (opponent == Opponent.NEARBY) {
                        SecondaryButton(stringResource(R.string.nearby_join_with_code), onClick = { joining = true }, tint = Theme.muted)
                    }
                    PrimaryButton(
                        stringResource(if (opponent == Opponent.NEARBY) R.string.nearby_host else R.string.bot_start),
                        onClick = {
                            when (opponent) {
                                Opponent.PHONE -> passAndPlay = HostTable.passAndPlay(names)
                                Opponent.COMPUTER -> passAndPlay = HostTable.vsComputer(you, computer, level, levelName)
                                Opponent.NEARBY -> withBlePermissions { room = NearbyRoom.host(this@MainActivity, myName) }
                            }
                        },
                    )
                }
            }
            SettingRow(stringResource(R.string.play_tembak)) {
                Segmented(
                    options = listOf(true to stringResource(R.string.tembak_after_store), false to stringResource(R.string.tembak_anytime)),
                    current = CongklakSettings.tembakAfterStore,
                    label = stringResource(R.string.tembak_title),
                    onChange = CongklakSettings::chooseTembakAfterStore,
                    fill = true,
                )
            }
            if (permissionDenied) {
                Text(stringResource(R.string.nearby_permission_denied), style = Theme.font(14, FontWeight.Bold, Theme.error))
            }
        }
    }

    /** The room code field, cancel and Gabung, in place of the "Teman dekat" description. */
    @Composable
    private fun JoinRow() {
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
                height = 44.dp,
            )
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
            Modifier.fillMaxSize().background(Theme.cream).windowInsetsPadding(gameInsets).padding(24.dp),
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
            Spacer(Modifier.weight(1f))
            Ads.Banner()
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

private fun levelLabel(level: CongklakBot.Level) = when (level) {
    CongklakBot.Level.EASY -> R.string.bot_easy
    CongklakBot.Level.HARD -> R.string.bot_hard
    else -> R.string.bot_normal
}

/** Who you play congklak against; the merged play card's first choice. */
private enum class Opponent(val label: Int, val icon: Int) {
    PHONE(R.string.opponent_phone, R.drawable.ic_people),
    COMPUTER(R.string.opponent_computer, R.drawable.ic_computer),
    NEARBY(R.string.opponent_nearby, R.drawable.ic_bluetooth),
}

/** A small label above its control; an empty label keeps the rows' spacing. */
@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Theme.font(13, FontWeight.Bold, Theme.muted), maxLines = 1)
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

@Composable
private fun Detail(text: String) =
    Text(text, style = Theme.font(14, FontWeight.SemiBold, Theme.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)

/** Mudah / Sedang / Sulit segmented picker. */
@Composable
private fun LevelPicker(level: CongklakBot.Level, onChange: (CongklakBot.Level) -> Unit) = Segmented(
    fill = true,
    options = listOf(CongklakBot.Level.EASY, CongklakBot.Level.NORMAL, CongklakBot.Level.HARD).map { it to stringResource(levelLabel(it)) },
    current = level,
    label = stringResource(R.string.bot_level),
    onChange = onChange,
)

/** Segmented picker on a track: the chosen option is a raised white chip. */
@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    current: T,
    label: String,
    onChange: (T) -> Unit,
    icons: List<Int>? = null,
    fill: Boolean = false,
) {
    Row(
        Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).background(Theme.track, RoundedCornerShape(12.dp)).padding(3.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, (value, text) ->
            val on = value == current
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.then(if (fill) Modifier.weight(1f) else Modifier).height(38.dp)
                    .then(if (on) Modifier.shadow(1.5.dp, shape) else Modifier)
                    .clip(shape)
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(value) }
                    .semantics { selected = on }
                    .padding(horizontal = if (fill) 6.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    icons?.getOrNull(i)?.let {
                        Icon(painterResource(it), contentDescription = null, tint = if (on) Theme.accent else Theme.muted, modifier = Modifier.size(16.dp))
                    }
                    Text(text, style = Theme.font(14, color = if (on) Theme.ink else Theme.muted), maxLines = 1)
                }
            }
        }
    }
}

/** The congklak tile on the home screen, with a snapshot of the real board. */
@Composable
private fun CongklakCard(modifier: Modifier, onReady: () -> Unit) {
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
            // The real board, cropped to its left end; it takes whatever height the card has.
            BoardPreview(
                Modifier.fillMaxWidth().weight(1f).heightIn(min = 80.dp).clip(RoundedCornerShape(14.dp)),
                onReady = onReady,
            )
            Text(stringResource(R.string.congklak_card_tagline), style = Theme.font(13, FontWeight.SemiBold, Color(0xFFF3DDBE)))
        }
    }
}

private const val STARTUP_MIN_MS = 700L
private const val STARTUP_MAX_MS = 3000L
