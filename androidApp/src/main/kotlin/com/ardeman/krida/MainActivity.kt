package com.ardeman.krida

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import kotlin.math.absoluteValue
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
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
import com.ardeman.krida.ui.Segmented
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.ardeman.krida.congklak.VoxelPreview
import com.ardeman.krida.core.games.UlarTanggaBoard
import com.ardeman.krida.ulartangga.UlarTanggaRenderer
import com.ardeman.krida.ulartangga.UlarTanggaScreen
import com.ardeman.krida.ulartangga.UlarTanggaSettings
import com.ardeman.krida.core.games.LudoGame
import com.ardeman.krida.ludo.LudoHostTable
import com.ardeman.krida.ludo.LudoPreview
import com.ardeman.krida.ludo.LudoScreen
import com.ardeman.krida.ludo.LudoSettings
import com.ardeman.krida.ludo.LudoTable
import com.ardeman.krida.ulartangga.UlarTanggaHostTable
import com.ardeman.krida.ulartangga.UlarTanggaTable
import com.ardeman.krida.core.games.CongklakBot
import com.ardeman.krida.core.games.CongklakGame
import com.ardeman.krida.core.games.UlarTanggaGame
import com.ardeman.krida.core.games.CongklakStarter
import com.ardeman.krida.core.session.LocalSeat
import com.ardeman.krida.core.session.LocalSeats
import com.ardeman.krida.core.session.RoomCode
import com.ardeman.krida.core.session.SavedRoom
import com.ardeman.krida.nearby.dashedBorder
import com.ardeman.krida.ulartangga.LocalPlayer
import com.ardeman.krida.ulartangga.pawnColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.stateDescription
import com.ardeman.krida.nearby.ConnectingScreen
import com.ardeman.krida.nearby.JoinScreen
import com.ardeman.krida.nearby.LastRoom
import com.ardeman.krida.nearby.NearbyRoom
import com.ardeman.krida.nearby.RoomLobby
import com.ardeman.krida.nearby.gameTitle
import com.ardeman.krida.playonline.CreateOnlineRoomScreen
import com.ardeman.krida.playonline.JoinOnlineRoomScreen
import com.ardeman.krida.playonline.PlayOnlineScreen
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.ardeman.krida.ui.Language
import com.ardeman.krida.ui.LanguagePicker
import com.ardeman.krida.ui.LoadingScreen
import com.ardeman.krida.ui.rememberWordmark
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
    /** The join screen takes a 6-letter online code instead of a 4-letter nearby one. */
    private var joinOnline by mutableStateOf(false)
    private var creatingOnlineRoom by mutableStateOf(false)
    private var quickMatchTarget by mutableStateOf(4)
    private var playingQuickMatch by mutableStateOf(false)
    private var quickMatchGame by mutableStateOf(CongklakGame.id)
    private var room by mutableStateOf<NearbyRoom?>(null)
    private var passAndPlay by mutableStateOf<CongklakTable?>(null)
    private var ularTable by mutableStateOf<UlarTanggaTable?>(null)
    private var ludoTable by mutableStateOf<LudoTable?>(null)
    private var permissionDenied by mutableStateOf(false)
    /** A string resource under the join screen's code: the scanner failed or read another QR. */
    private var joinError by mutableStateOf<Int?>(null)
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
        // A system theme change recreates the activity, so reading it once here is enough.
        Theme.dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        Sounds.init(this)
        // A language change recreates the activity, so this follows the picker.
        Sounds.english = Language.current(this) == Language.Lang.EN
        CongklakSettings.init(this)
        UlarTanggaSettings.init(this)
        LudoSettings.init(this)
        LastRoom.init(this)
        // Language and theme changes recreate this activity with its original launch intent.
        // Apply a room link only on the initial launch so leaving it is not undone later.
        if (savedInstanceState == null) {
            intent?.data?.toString()?.let(::openRoomLink)
        }
        setContent {
            // The ad SDK and the banner's WebView take about a second to start on older phones:
            // start them once the loading screen is on screen, not before the first frame.
            LaunchedEffect(Unit) {
                withFrameNanos {}
                withFrameNanos {}
                Ads.init(this@MainActivity)
            }
            // Keeps the menu's saved state (chosen game, opponent, level) while a game or
            // lobby covers it, so leaving a game lands back on that game's card.
            val menuState = rememberSaveableStateHolder()
            LandscapeStage {
                val r = room
                val table = passAndPlay ?: r?.congklak
                val ular = ularTable ?: r?.ular
                val ludo = ludoTable ?: r?.ludo
                when {
                    ludo != null -> LudoScreen(ludo, onExit = { ludoTable = null; room = null; joining = false })
                    ular != null -> UlarTanggaScreen(ular, onExit = { ularTable = null; room = null; joining = false })
                    table != null -> CongklakScreen(table, onExit = { passAndPlay = null; room = null; joining = false })
                    r != null -> Lobby(r)
                    creatingOnlineRoom -> CreateOnlineRoomScreen(
                        game = quickMatchGame,
                        onBack = { creatingOnlineRoom = false },
                        onCreate = { public, seats ->
                            room = NearbyRoom.host(this@MainActivity, myName,
                                when (quickMatchGame) { LudoGame.id -> NearbyRoom.Game.LUDO; UlarTanggaGame.id -> NearbyRoom.Game.ULAR_TANGGA; else -> NearbyRoom.Game.CONGKLAK },
                                online = true, publicRoom = public, seats = seats)
                            creatingOnlineRoom = false
                        },
                        options = { GameRoomOptions(quickMatchGame) },
                    )
                    playingQuickMatch -> PlayOnlineScreen(
                        game = quickMatchGame,
                        target = quickMatchTarget,
                        onCancel = { playingQuickMatch = false },
                        createHost = { code, humans, bots, onReady ->
                            NearbyRoom.host(this@MainActivity, myName,
                                when (quickMatchGame) { LudoGame.id -> NearbyRoom.Game.LUDO; UlarTanggaGame.id -> NearbyRoom.Game.ULAR_TANGGA; else -> NearbyRoom.Game.CONGKLAK },
                                online = true, code = code, onOnlineReady = onReady, standardRules = true,
                            ).also { it.configureQuickMatch(humans, bots, getString(R.string.bot_name)) }
                        },
                        onHost = { matchedRoom -> room = matchedRoom; playingQuickMatch = false },
                        onJoin = { code, reservation ->
                            room = NearbyRoom.join(this@MainActivity, code, myName, online = true, reservation = reservation)
                            playingQuickMatch = false
                        },
                        onComputer = { count ->
                            val you = getString(R.string.bot_you)
                            val computer = getString(R.string.bot_name)
                            when (quickMatchGame) {
                                UlarTanggaGame.id -> ularTable = UlarTanggaHostTable.local(List(count) { seat ->
                                    LocalPlayer(if (seat == 0) you else computer, computer = seat != 0, color = seat)
                                })
                                LudoGame.id -> ludoTable = LudoHostTable.local(List(count) { seat ->
                                    LocalPlayer(if (seat == 0) you else computer, computer = seat != 0, color = seat)
                                })
                                else -> passAndPlay = HostTable.vsComputer(you, computer, CongklakBot.Level.NORMAL, getString(R.string.bot_normal))
                            }
                            playingQuickMatch = false
                        },
                    )
                    joining && joinOnline -> JoinOnlineRoomScreen(
                        game = quickMatchGame,
                        code = roomCode,
                        onCode = { roomCode = it; joinError = null },
                        onScan = ::scanRoomQr,
                        onJoin = ::joinRoom,
                        onSearch = { target -> quickMatchTarget = target; playingQuickMatch = true },
                        onBack = { joining = false; joinError = null },
                        error = joinError,
                    )
                    joining -> JoinScreen(
                        code = roomCode,
                        onCode = { roomCode = it; joinError = null },
                        onScan = ::scanRoomQr,
                        onJoin = ::joinRoom,
                        onBack = { joining = false; joinError = null },
                        error = joinError ?: R.string.nearby_permission_denied.takeIf { permissionDenied && !joinOnline },
                        online = joinOnline,
                    )
                    else -> menuState.SaveableStateProvider("menu") { Menu() }
                }
                StartupCover()
            }
        }
    }

    /** A room link (QR or share link) opened while Krida is already running (singleTask). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A running game or lobby stays; the link only applies from the menus.
        if (room == null && passAndPlay == null && ularTable == null) {
            intent.data?.toString()?.let(::openRoomLink)
        }
    }

    /** Opens the join screen for "krida://join?room=ABCD" or "https://krida.ardeman.com/r/ABC234". */
    private fun openRoomLink(link: String) {
        RoomCode.parse(link)?.let { roomCode = it; joinOnline = false; joining = true; creatingOnlineRoom = false }
            ?: RoomCode.parseOnline(link)?.let { roomCode = it; joinOnline = true; joining = true; creatingOnlineRoom = false }
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
        // Until the game list exists, the left card switches between the games. A room
        // link opens the app ready to join; the host's Welcome says which game it plays.
        var game by rememberSaveable { mutableStateOf(Game.CONGKLAK) }
        // Capped at the phone design's size (as on iOS), so a tall stage doesn't stretch the cards.
        Box(Modifier.fillMaxSize().background(Theme.backdropBrush).windowInsetsPadding(gameInsets), contentAlignment = Alignment.Center) { Row(
            Modifier.sizeIn(maxWidth = 874.dp, maxHeight = 460.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.width(236.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(
                    rememberWordmark(),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(40.dp),
                    alignment = Alignment.CenterStart,
                )
                GameCard(game, onGame = { game = it }, Modifier.weight(1f), onReady = { menuReady = true })
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
                        LastRoom.current?.let { RejoinCard(it) }
                        PlayCard(game)
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
     * player there is more below. While the column can still scroll: a backdrop fade over the
     * bottom edge and a chevron that scrolls to the end.
     */
    @Composable
    private fun MoreBelowHint(scroll: ScrollState, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        val more = stringResource(R.string.menu_more)
        AnimatedVisibility(scroll.canScrollForward, modifier, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .background(Brush.verticalGradient(listOf(Theme.backdrop.copy(alpha = 0f), Theme.backdrop))),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.size(32.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Theme.surface)
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
            Modifier.height(50.dp).clip(shape).background(Theme.surface).border(1.5.dp, Theme.accent, shape)
                .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); Ads.buyRemoveAds(this@MainActivity) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.shop_remove_ads), style = Theme.font(14, color = Theme.accent), maxLines = 1)
        }
    }

    /**
     * One card for every way to play: on this phone (seat tiles for people and computers) or
     * with friends nearby over Bluetooth; right below it what that means (or the computer's
     * level) with Mulai, and "Gabung pakai kode" for "Teman dekat"; then the game's rules.
     */
    /** A running game this device left as a guest: go back to the same seat, or forget it. */
    @Composable
    private fun RejoinCard(saved: SavedRoom) {
        val shape = RoundedCornerShape(20.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Theme.surface).border(1.5.dp, Theme.accent, shape)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rejoin_title, gameTitle(saved.game)), style = Theme.font(15, FontWeight.Bold, Theme.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Detail(stringResource(R.string.rejoin_detail, saved.code))
            }
            SecondaryButton(stringResource(R.string.rejoin_forget), onClick = { LastRoom.clear() }, tint = Theme.muted)
            PrimaryButton(
                stringResource(R.string.rejoin_button),
                onClick = {
                    // Backing out of a failed rejoin lands on the join screen, as after a typed code.
                    joining = true
                    joinOnline = saved.online
                    quickMatchGame = saved.game
                    roomCode = saved.code
                    joinRoom()
                },
            )
        }
    }

    @Composable
    private fun PlayCard(game: Game) {
        var place by rememberSaveable { mutableStateOf(Place.PHONE) }
        val ular = game == Game.ULAR_TANGGA
        val ludo = game == Game.LUDO
        val multi = ular || ludo
        // A room with other phones, over Bluetooth or the internet.
        val nearby = place != Place.PHONE
        val online = place == Place.ONLINE
        // Congklak's second seat: a person on this phone or the computer.
        var congklakSeats by rememberSaveable { mutableStateOf(LocalSeats.encode(listOf(LocalSeat.PERSON, LocalSeat.PERSON))) }
        val congklak = LocalSeats.decode(congklakSeats, 2, listOf(LocalSeat.PERSON, LocalSeat.PERSON))
        val seats = when {
            ular -> UlarTanggaSettings.seats
            ludo -> LudoSettings.seats
            else -> congklak
        }
        val computers = seats.count { it == LocalSeat.COMPUTER }
        val people = seats.count { it == LocalSeat.PERSON }
        // Vs computer on this phone: the lone person can switch colour with a computer or take a free one.
        var personColor by rememberSaveable { mutableStateOf(-1) }
        val seatColors = if (multi) LocalSeats.colors(seats, if (people == 1) personColor else -1) else emptyList()
        // Congklak has no seat colours; only index [seatColors] for ular/ludo.
        val myColor = seats.indices.firstOrNull { seats[it] == LocalSeat.PERSON }
            ?.takeIf { it < seatColors.size }
            ?.let { seatColors[it].takeIf { c -> c >= 0 } }
        val colorNames = listOf(R.string.ular_player_red, R.string.ular_player_blue, R.string.ular_player_green, R.string.ular_player_yellow)
            .map { stringResource(it) }
        val computerN = (1..3).map { stringResource(R.string.ular_computer_n, it) }
        var level by rememberSaveable { mutableStateOf(CongklakBot.Level.NORMAL) }
        val names = listOf(stringResource(R.string.congklak_player_1), stringResource(R.string.congklak_player_2))
        val you = stringResource(R.string.bot_you)
        val computer = stringResource(R.string.bot_name)
        val levelName = stringResource(levelLabel(level))
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Theme.surface).border(1.5.dp, Theme.line, shape)
                .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingRow(stringResource(R.string.play_where)) {
                Segmented(
                    options = Place.entries.map { it to stringResource(it.label) },
                    current = place,
                    label = stringResource(R.string.play_where),
                    onChange = { place = it },
                    icons = Place.entries.map { it.icon },
                    fill = true,
                )
            }
            // In a nearby room everyone who joins plays, up to 4.
            if (!nearby) SettingRow(stringResource(R.string.play_players)) {
                SeatTiles(
                    seats = seats,
                    colors = if (multi) {
                        seats.indices.map { i -> seatColors[i].takeIf { it >= 0 }?.let(::pawnColor) ?: Theme.border }
                    } else {
                        listOf(Theme.wood, Theme.seed)
                    },
                    // Congklak's first seat is always a person; the second is a person or the computer.
                    tappable = if (multi) seats.indices.toSet() else setOf(1),
                    onTap = { i ->
                        when {
                            ular -> UlarTanggaSettings.tapSeat(i)
                            ludo -> LudoSettings.tapSeat(i)
                            else -> congklakSeats = LocalSeats.encode(LocalSeats.next(congklak, i, allowEmpty = false))
                        }
                    },
                )
            }
            if (!nearby && multi && people == 1 && computers > 0) {
                LocalColorRow(
                    names = colorNames,
                    mine = myColor,
                    computerColors = seats.indices.mapNotNull { i ->
                        seatColors[i].takeIf { it >= 0 && seats[i] == LocalSeat.COMPUTER }
                    }.toSet(),
                    onPick = { personColor = it },
                )
            }
            // The action row: what this choice means (or the computer's level), then the
            // buttons, right under the choice that changes them. "Gabung pakai kode" (only for
            // "Teman dekat") turns the row into the code field.
            SettingRow(if (!multi && !nearby && computers > 0) stringResource(R.string.play_level) else "") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        when {
                            online -> Detail(stringResource(when { ular -> R.string.ular_detail_online; ludo -> R.string.ludo_detail_online; else -> R.string.online_detail }))
                            nearby -> Detail(stringResource(when { ular -> R.string.ular_detail_nearby; ludo -> R.string.ludo_detail_nearby; else -> R.string.nearby_detail }))
                            !multi && computers > 0 -> LevelPicker(level) { level = it }
                            !multi -> Detail(stringResource(R.string.mode_pass_detail))
                            computers == 0 -> Detail(stringResource(if (ludo) R.string.ludo_detail_phone else R.string.ular_detail_phone))
                            people == 1 -> Detail(stringResource(if (ludo) R.string.ludo_detail_computer else R.string.ular_detail_computer))
                            else -> Detail(stringResource(if (ludo) R.string.ludo_detail_mixed else R.string.ular_detail_mixed))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (nearby) {
                            SecondaryButton(
                                stringResource(if (online) R.string.room_join else R.string.nearby_join_with_code),
                                onClick = {
                                    if (joinOnline != online) roomCode = ""
                                    joinOnline = online
                                    quickMatchGame = when {
                                        ular -> UlarTanggaGame.id
                                        ludo -> LudoGame.id
                                        else -> CongklakGame.id
                                    }
                                    joining = true
                                },
                                modifier = Modifier.weight(1f),
                                tint = Theme.muted,
                            )
                        }
                        PrimaryButton(
                            stringResource(if (online) R.string.room_create else if (nearby) R.string.nearby_host else R.string.bot_start),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when {
                                    online -> {
                                        quickMatchGame = when {
                                            ular -> UlarTanggaGame.id
                                            ludo -> LudoGame.id
                                            else -> CongklakGame.id
                                        }
                                        creatingOnlineRoom = true
                                    }
                                    nearby -> withBlePermissions {
                                        room = NearbyRoom.host(
                                            this@MainActivity, myName,
                                            when {
                                                ular -> NearbyRoom.Game.ULAR_TANGGA
                                                ludo -> NearbyRoom.Game.LUDO
                                                else -> NearbyRoom.Game.CONGKLAK
                                            },
                                        )
                                    }
                                    multi -> {
                                        var nextComputer = 0
                                        val players = seats.mapIndexedNotNull { i, kind ->
                                            val color = seatColors[i].takeIf { it >= 0 } ?: return@mapIndexedNotNull null
                                            when (kind) {
                                                LocalSeat.EMPTY -> null
                                                LocalSeat.COMPUTER -> LocalPlayer(if (computers == 1) computer else computerN[nextComputer++], computer = true, color = color)
                                                LocalSeat.PERSON -> LocalPlayer(
                                                    if (people == 1) you else colorNames[color],
                                                    computer = false,
                                                    color = color,
                                                )
                                            }
                                        }
                                        if (ludo) ludoTable = LudoHostTable.local(players)
                                        else ularTable = UlarTanggaHostTable.local(players)
                                    }
                                    computers > 0 -> passAndPlay = HostTable.vsComputer(you, computer, level, levelName)
                                    else -> passAndPlay = HostTable.passAndPlay(names)
                                }
                            },
                        )
                    }
                }
            }
            if (!online) GameRoomOptions(game)
            if (permissionDenied && !online) {
                Text(stringResource(R.string.nearby_permission_denied), style = Theme.font(14, FontWeight.Bold, Theme.error))
            }
        }
    }

    /** Joins the room [roomCode] names: online, or nearby after asking for the Bluetooth permissions. */
    private fun joinRoom() {
        joinError = null
        if (joinOnline) {
            if (RoomCode.isValidOnline(roomCode)) room = NearbyRoom.join(this, roomCode, myName, online = true)
            return
        }
        if (!RoomCode.isValid(roomCode)) return
        withBlePermissions { room = NearbyRoom.join(this@MainActivity, roomCode, myName) }
    }

    /** Google's code scanner (no camera permission; Play services shows the camera). */
    private fun scanRoomQr() {
        joinError = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                // Either kind of room, whichever join screen is open.
                val raw = barcode.rawValue
                val nearbyCode = raw?.let(RoomCode::parse)
                val onlineCode = raw?.let(RoomCode::parseOnline)
                if (nearbyCode == null && onlineCode == null) {
                    joinError = R.string.nearby_scan_not_room
                } else {
                    joinOnline = nearbyCode == null
                    roomCode = nearbyCode ?: onlineCode!!
                    joinRoom()
                }
            }
            .addOnFailureListener { joinError = R.string.nearby_scan_failed }
    }

    @Composable
    private fun Lobby(r: NearbyRoom) {
        // Leaving a room the guest got into, or the host's own, goes back to the menu; backing
        // out of a search or a failure goes back to the join screen.
        fun leave() {
            r.leave()
            room = null
            if (r.isHost || r.stage == NearbyRoom.Stage.LOBBY) joining = false
        }
        if ((r.isHost && !r.failed) || r.stage == NearbyRoom.Stage.LOBBY) {
            RoomLobby(r, onLeave = ::leave)
        } else {
            ConnectingScreen(r, onCancel = ::leave, onRetry = { room = r.retry(this@MainActivity, myName) })
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

/** Where the game is played; the play card's first choice. */
private enum class Place(val label: Int, val icon: Int) {
    PHONE(R.string.opponent_phone, R.drawable.ic_people),
    NEARBY(R.string.opponent_nearby, R.drawable.ic_bluetooth),
    ONLINE(R.string.opponent_online, R.drawable.ic_globe),
}

/**
 * One tile per seat of a game on this phone: a person, a computer, or empty (dashed). A tap
 * on a seat in [tappable] moves it on (see [LocalSeats.next]).
 */
@Composable
private fun SeatTiles(seats: List<LocalSeat>, colors: List<Color>, tappable: Set<Int>, onTap: (Int) -> Unit) {
    val change = stringResource(R.string.seat_change)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        seats.forEachIndexed { i, kind ->
            val shape = RoundedCornerShape(12.dp)
            val empty = kind == LocalSeat.EMPTY
            val circle = colors[i]
            val kindLabel = stringResource(
                when (kind) {
                    LocalSeat.PERSON -> R.string.seat_person
                    LocalSeat.COMPUTER -> R.string.opponent_computer
                    LocalSeat.EMPTY -> R.string.seat_empty
                },
            )
            val seatLabel = stringResource(R.string.seat_n, i + 1)
            Column(
                Modifier.weight(1f).height(60.dp)
                    .then(if (empty) Modifier.dashedBorder(1.5.dp, Theme.line, 12.dp) else Modifier.clip(shape).background(Theme.surfaceSoft).border(1.dp, Theme.line, shape))
                    .clip(shape)
                    .then(
                        if (i in tappable) {
                            Modifier.clickable(role = Role.Button, onClickLabel = change) { Sounds.play(Sounds.Effect.Tap); onTap(i) }
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = seatLabel
                        stateDescription = kindLabel
                    }
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier.size(28.dp).then(
                        if (empty) Modifier.dashedBorder(1.5.dp, Theme.border, 14.dp) else Modifier.clip(CircleShape).background(circle),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = when (kind) {
                        LocalSeat.PERSON -> R.drawable.ic_people
                        LocalSeat.COMPUTER -> R.drawable.ic_computer
                        LocalSeat.EMPTY -> R.drawable.ic_add
                    }
                    val tint = if (empty) Theme.muted else if (circle.luminance() > 0.5f) Theme.inkOnLight else Color.White
                    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                }
                Text(
                    kindLabel,
                    style = Theme.font(12, FontWeight.Bold, if (empty) Theme.muted else Theme.ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Pawn colours for a lone person vs computers on this phone: free colours and computer colours
 * are tappable; the person's current colour shows a check.
 */
@Composable
private fun LocalColorRow(names: List<String>, mine: Int?, computerColors: Set<Int>, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Theme.surfaceSoft).border(1.dp, Theme.line, shape)
            .padding(start = 12.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.nearby_your_color), style = Theme.font(13, FontWeight.ExtraBold, Theme.muted), modifier = Modifier.weight(1f))
        for (c in 0 until 4) {
            val color = pawnColor(c)
            val name = names.getOrElse(c) { "${c + 1}" }
            val ink = if (color.luminance() > 0.5f) Theme.inkOnLight else Color.White
            val label = when {
                c == mine -> stringResource(R.string.nearby_color_chosen, name)
                c in computerColors -> stringResource(R.string.nearby_color_switch_computer, name)
                else -> name
            }
            Box(
                Modifier.size(44.dp)
                    .then(if (c == mine) Modifier.border(2.dp, Theme.ink, CircleShape).padding(4.dp) else Modifier)
                    .clip(CircleShape)
                    .background(color)
                    .clickable(enabled = c != mine, role = Role.Button, onClickLabel = label) {
                        Sounds.play(Sounds.Effect.Tap)
                        onPick(c)
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    c == mine -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
                    c in computerColors -> Icon(painterResource(R.drawable.ic_computer), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** The games the left card switches between, until the game list exists. */
private enum class Game(val title: Int, val players: Int, val tagline: Int, val face: Color, val edge: Color) {
    CONGKLAK(R.string.congklak_title, R.string.congklak_card_players, R.string.congklak_card_tagline, Theme.wood, Theme.woodShadow),
    ULAR_TANGGA(R.string.ular_title, R.string.ular_card_players, R.string.ular_card_tagline, Color(0xFF4F8A3B), Color(0xFF3B6A2B)),
    LUDO(R.string.ludo_title, R.string.ludo_card_players, R.string.ludo_card_tagline, Color(0xFF3D6EB5), Color(0xFF2A5085)),
}

@Composable
private fun GameRoomOptions(game: Any) {
    val ular = game == Game.ULAR_TANGGA || game == UlarTanggaGame.id
    val ludo = game == Game.LUDO || game == LudoGame.id
    when {
        ular -> SettingRow(stringResource(R.string.play_rules)) { UlarTanggaOptionChips() }
        ludo -> SettingRow(stringResource(R.string.play_rules)) { LudoOptionChips() }
        else -> {
            SettingRow(stringResource(R.string.play_capture)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Segmented(
                        options = listOf(true to stringResource(R.string.capture_after_store), false to stringResource(R.string.capture_anytime)),
                        current = CongklakSettings.tembakAfterStore,
                        label = stringResource(R.string.capture_title),
                        onChange = CongklakSettings::chooseTembakAfterStore,
                        fill = true,
                    )
                    Hint(stringResource(if (CongklakSettings.tembakAfterStore) R.string.capture_hint_after_store else R.string.capture_hint_anytime))
                }
            }
            SettingRow(stringResource(R.string.play_starter)) {
                Segmented(
                    options = listOf(
                        CongklakStarter.ME to stringResource(R.string.starter_me),
                        CongklakStarter.OPPONENT to stringResource(R.string.starter_opponent),
                        CongklakStarter.RANDOM to stringResource(R.string.starter_random),
                    ),
                    current = CongklakSettings.starter,
                    label = stringResource(R.string.starter_title),
                    onChange = CongklakSettings::chooseStarter,
                    fill = true,
                )
            }
        }
    }
}

/** Ludo's rule options as toggle chips, saved as they change. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LudoOptionChips() {
    val o = LudoSettings.options
    val chips = listOf(
        Triple(R.string.ludo_option_six_enter, o.sixToEnter) { v: Boolean -> o.copy(sixToEnter = v) },
        Triple(R.string.ludo_option_six_again, o.sixRollsAgain) { v: Boolean -> o.copy(sixRollsAgain = v) },
        Triple(R.string.ludo_option_capture_again, o.captureRollsAgain) { v: Boolean -> o.copy(captureRollsAgain = v) },
        Triple(R.string.ludo_option_first_ends, o.firstFinishEnds) { v: Boolean -> o.copy(firstFinishEnds = v) },
    )
    val hints = listOf(
        R.string.ludo_hint_six_enter_on to R.string.ludo_hint_six_enter_off,
        R.string.ludo_hint_six_again_on to R.string.ludo_hint_six_again_off,
        R.string.ludo_hint_capture_again_on to R.string.ludo_hint_capture_again_off,
        R.string.ludo_hint_first_ends_on to R.string.ludo_hint_first_ends_off,
    )
    var last by rememberSaveable { mutableStateOf(-1) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { (label, on, change) ->
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.height(34.dp).clip(shape)
                        .background(if (on) Theme.chip else Theme.surface)
                        .border(1.5.dp, if (on) Theme.accent else Theme.line, shape)
                        .toggleable(value = on, role = Role.Checkbox) {
                            last = chips.indexOfFirst { c -> c.first == label }
                            LudoSettings.chooseOptions(change(it))
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), style = Theme.font(13, color = if (on) Theme.accent else Theme.muted), maxLines = 1)
                }
            }
        }
        Hint(stringResource(hints.getOrNull(last)?.let { if (chips[last].second) it.first else it.second } ?: R.string.ludo_hint_tap))
    }
}

/** Ular tangga's rule options as toggle chips, saved as they change. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun UlarTanggaOptionChips() {
    val o = UlarTanggaSettings.options
    val chips = listOf(
        Triple(R.string.ular_option_six_again, o.sixRollsAgain) { v: Boolean -> o.copy(sixRollsAgain = v) },
        Triple(R.string.ular_option_six_to_enter, o.sixToEnter) { v: Boolean -> o.copy(sixToEnter = v) },
        Triple(R.string.ular_option_bounce, o.bounceBack) { v: Boolean -> o.copy(bounceBack = v) },
        Triple(R.string.ular_option_first_ends, o.firstFinishEnds) { v: Boolean -> o.copy(firstFinishEnds = v) },
    )
    // What each chip means when on and off, in chip order.
    val hints = listOf(
        R.string.ular_hint_six_again_on to R.string.ular_hint_six_again_off,
        R.string.ular_hint_six_to_enter_on to R.string.ular_hint_six_to_enter_off,
        R.string.ular_hint_bounce_on to R.string.ular_hint_bounce_off,
        R.string.ular_hint_first_ends_on to R.string.ular_hint_first_ends_off,
    )
    // The chip tapped last; its meaning shows under the chips.
    var last by rememberSaveable { mutableStateOf(-1) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { (label, on, change) ->
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.height(34.dp).clip(shape)
                        .background(if (on) Theme.chip else Theme.surface)
                        .border(1.5.dp, if (on) Theme.accent else Theme.line, shape)
                        .toggleable(value = on, role = Role.Checkbox) { last = chips.indexOfFirst { c -> c.first == label }; UlarTanggaSettings.chooseOptions(change(it)) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), style = Theme.font(13, color = if (on) Theme.accent else Theme.muted), maxLines = 1)
                }
            }
        }
        Hint(stringResource(hints.getOrNull(last)?.let { if (chips[last].second) it.first else it.second } ?: R.string.ular_hint_tap))
    }
}

/** A small label above its control; an empty label keeps the rows' spacing. */
@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Theme.font(13, FontWeight.Bold, Theme.muted), maxLines = 1)
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

/** A short explanation under a rule control. */
@Composable
private fun Hint(text: String) =
    Text(text, style = Theme.font(12, FontWeight.SemiBold, Theme.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)

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

/**
 * The game tile on the home screen, with a snapshot of the real board; the arrows or a swipe
 * slide to the next game (the play card on the right follows).
 */
@Composable
private fun GameCard(game: Game, onGame: (Game) -> Unit, modifier: Modifier, onReady: () -> Unit) {
    val games = Game.entries
    val n = games.size
    // A looping carousel: the pages repeat the games, starting far from both ends, so a swipe or
    // an arrow slides the next card in attached to the current one.
    val pager = rememberPagerState(initialPage = n * 500 + game.ordinal) { n * 1000 }
    val scope = rememberCoroutineScope()
    val pick by rememberUpdatedState(onGame)
    val slide = tween<Float>(420, easing = FastOutSlowInEasing)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { pick(games[it % n]) } }
    // Keeps the pager on [game] if it changes from outside (smooth, same as a swipe).
    LaunchedEffect(game) {
        val shown = games[pager.settledPage % n]
        if (shown != game) {
            pager.animateScrollToPage(pager.settledPage + game.ordinal - shown.ordinal, animationSpec = slide)
        }
    }
    fun step(d: Int) {
        scope.launch { pager.animateScrollToPage(pager.targetPage + d, animationSpec = slide) }
    }
    Box(modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 14.dp,
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = slide),
            key = { it },
        ) { page ->
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
            GameCardFace(games[page % n], onReady, Modifier.graphicsLayer {
                // Neighbours ease smaller and fade a little while the finger drags.
                val t = offset.coerceIn(0f, 1f)
                alpha = lerp(1f, 0.72f, t)
                val scale = lerp(1f, 0.94f, t)
                scaleX = scale
                scaleY = scale
            })
        }
        // The arrows stay put above the sliding cards, so they're always in the same spot.
        Row(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SwitchArrow(R.drawable.ic_chevron_left, stringResource(R.string.menu_prev_game)) { step(-1) }
            SwitchArrow(R.drawable.ic_chevron_right, stringResource(R.string.menu_next_game)) { step(1) }
        }
    }
}

@Composable
private fun GameCardFace(game: Game, onReady: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val cream = Color(0xFFF3DDBE)
    Box(modifier.fillMaxSize().background(game.edge, shape)) {
        Column(
            Modifier.fillMaxSize().padding(bottom = 6.dp).background(game.face, shape).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Room on the right for the arrows, which GameCard draws above the pages.
            Column(Modifier.padding(end = 80.dp).heightIn(min = 34.dp), verticalArrangement = Arrangement.Center) {
                Text(stringResource(game.title), style = Theme.font(20, FontWeight.Black, Color(0xFFFFF6E8)).copy(lineHeight = 22.sp), maxLines = 2)
                Text(stringResource(game.players), style = Theme.font(13, FontWeight.Bold, cream), maxLines = 1)
            }
            val preview = Modifier.fillMaxWidth().weight(1f).heightIn(min = 80.dp).padding(end = 6.dp).clip(RoundedCornerShape(14.dp))
            when (game) {
                // The real board, cropped to its left end; it takes whatever height the card has.
                Game.CONGKLAK -> BoardPreview(preview, onReady = onReady)
                Game.ULAR_TANGGA -> VoxelPreview("ular_tangga", null, preview, onReady) { view ->
                    // Zoomed in on the start: the pawns waiting in the tray, the first rows,
                    // ladder 4→25 and snake 27→5.
                    UlarTanggaRenderer(view).apply {
                        val pawns = (0 until UlarTanggaBoard.PIECES).map { UlarTanggaBoard.pieceSpot(0, it, true) }
                        pawns.forEachIndexed { seat, spot -> place(seat, spot) }
                        frame(pawns + listOf(1, 4, 5, 25, 27).map { UlarTanggaBoard.squareAnchor(it) })
                    }
                }
                Game.LUDO -> LudoPreview(preview, onReady = onReady)
            }
            Text(stringResource(game.tagline), style = Theme.font(13, FontWeight.SemiBold, cream), modifier = Modifier.padding(end = 6.dp))
        }
    }
}

@Composable
private fun SwitchArrow(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
            .clickable(role = Role.Button) { Sounds.play(Sounds.Effect.Tap); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color(0xFFFFF6E8), modifier = Modifier.size(20.dp))
    }
}

private const val STARTUP_MIN_MS = 700L
private const val STARTUP_MAX_MS = 3000L
