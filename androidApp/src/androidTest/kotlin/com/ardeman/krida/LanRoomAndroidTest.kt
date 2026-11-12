package com.ardeman.krida

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.ardeman.krida.lan.LanHostTransport
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Nearby Wi‑Fi room e2e on emulators (UiAutomator). NSD is unreliable there, so the guest
 * TCP-connects through `adb forward` (`scripts/test-android-lan.mjs` / `test-cross-lan.mjs`).
 * Args: `role`=host|guest; guest also needs `lanHost`, `lanPort`, `code`.
 */
@RunWith(AndroidJUnit4::class)
class LanRoomAndroidTest {

    private lateinit var device: UiDevice
    private val args get() = InstrumentationRegistry.getArguments()
    private val timeout = 15_000L

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun lanHost() {
        assumeTrue("host role", args.getString("role") == "host")
        File(e2ePath()).delete()
        File(OK_FILE).delete()
        launchApp()
        openNearbyWifi()
        clickText("Create room")
        waitText("ROOM CODE", "Room code", "Players · 1 of 2")
        waitFile(e2ePath(), 30_000)
        waitText("Players · 2 of 2", timeoutMs = 90_000)
        assert(device.findObject(By.text("Start")) != null)
        waitFile(OK_FILE, 30_000)
    }

    @Test
    fun lanGuest() {
        assumeTrue("guest role", args.getString("role") == "guest")
        val code = args.getString("code") ?: error("missing code")
        val host = args.getString("lanHost") ?: error("missing lanHost")
        val port = args.getString("lanPort")?.toIntOrNull() ?: error("missing lanPort")
        launchApp(host, port)
        openNearbyWifi()
        clickText("Join")
        waitText("Type the code", "Room code from your friend")
        val field = device.wait(Until.findObject(By.desc("Room code from your friend")), timeout)
            ?: error("code field")
        field.click()
        field.text = code
        // Primary Join on the type-code card (after the play-card Join).
        val joins = device.findObjects(By.text("Join"))
        joins.lastOrNull()?.click() ?: clickText("Join")
        waitText("You're in", "Waiting for the host to start…", "Players · 2 of 2", timeoutMs = 60_000)
        File(OK_FILE).writeText("ok\n")
    }

    private fun launchApp(lanHost: String? = null, lanPort: Int = 0) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("adsRemoved", true)
            if (lanHost != null) {
                putExtra(EXTRA_E2E_LAN_HOST, lanHost)
                putExtra(EXTRA_E2E_LAN_PORT, lanPort)
            }
        }
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.text("Nearby")), 60_000)
            || error("menu did not appear")
        Thread.sleep(800)
    }

    private fun openNearbyWifi() {
        clickText("Nearby")
        waitText("Wi‑Fi", timeoutMs = 8_000)
        clickText("Wi‑Fi")
        waitText("Wi‑Fi or hotspot, no internet", timeoutMs = 8_000)
    }

    private fun clickText(text: String) {
        val node = device.wait(Until.findObject(By.text(text)), timeout) ?: error("missing $text")
        node.click()
        Thread.sleep(300)
    }

    private fun waitText(vararg texts: String, timeoutMs: Long = timeout) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (texts.any { device.findObject(By.text(it)) != null || device.findObject(By.textContains(it)) != null }) {
                return
            }
            Thread.sleep(250)
        }
        error("missing any of ${texts.toList()}")
    }

    private fun waitFile(path: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (File(path).isFile) return
            Thread.sleep(250)
        }
        error("missing file $path")
    }

    private fun e2ePath() = "${LanHostTransport.E2E_DIR}/${LanHostTransport.E2E_NAME}"

    companion object {
        private const val OK_FILE = "/sdcard/Android/data/com.ardeman.krida/files/krida-lan-e2e-ok"
    }
}
