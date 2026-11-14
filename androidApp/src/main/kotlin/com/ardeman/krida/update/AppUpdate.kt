package com.ardeman.krida.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ardeman.krida.core.protocol.Krida
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Asks Play whether a newer AAB is on this install's track. Runs once at launch ([check]
 * with `silent = true`) and from the menu button. Immediate in-app update when Play allows
 * it; otherwise opens the Play listing. Sideloaded / debug builds stay [Status.UpToDate].
 */
object AppUpdate {
    enum class Status { Idle, Checking, UpToDate, Available, Failed }

    var status by mutableStateOf(Status.Idle)
        private set

    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var launchedCheck = false

    fun bind(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        this.launcher = launcher
    }

    /**
     * @param silent launch-time check: no button feedback unless an update starts; failures
     * stay quiet. Menu taps use `silent = false`.
     */
    fun check(activity: Activity, silent: Boolean = false) {
        if (status == Status.Checking) return
        if (!silent) status = Status.Checking
        val manager = AppUpdateManagerFactory.create(activity)
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                when {
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> {
                        status = Status.Available
                        startImmediate(activity, manager, info)
                    }
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE -> {
                        status = Status.Available
                        openStore(activity)
                    }
                    silent -> status = Status.Idle
                    else -> status = Status.UpToDate
                }
            }
            .addOnFailureListener {
                status = if (silent) Status.Idle else Status.Failed
            }
    }

    /** Once per process after the activity is ready (menu / Play installs). */
    fun checkOnLaunch(activity: Activity) {
        if (launchedCheck) return
        launchedCheck = true
        check(activity, silent = true)
    }

    /** Resume an in-progress immediate update after the activity was recreated. */
    fun resumeIfNeeded(activity: Activity) {
        val manager = AppUpdateManagerFactory.create(activity)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() ==
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            ) {
                status = Status.Available
                startImmediate(activity, manager, info)
            }
        }
    }

    private fun startImmediate(
        activity: Activity,
        manager: com.google.android.play.core.appupdate.AppUpdateManager,
        info: com.google.android.play.core.appupdate.AppUpdateInfo,
    ) {
        val launch = launcher
        if (launch == null) {
            openStore(activity)
            return
        }
        manager.startUpdateFlowForResult(
            info,
            launch,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
        )
    }

    fun openStore(activity: Activity) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(Krida.PLAY_STORE_URL))
        try {
            activity.startActivity(market)
        } catch (_: Exception) {
            activity.startActivity(web)
        }
    }

    fun onUpdateFlowResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK && status == Status.Available) {
            // User backed out of the Play sheet; keep Available so they can tap again.
        }
    }

    /** Clears a transient status so the menu button returns to "Check for update". */
    fun clearStatus() {
        if (status == Status.UpToDate || status == Status.Failed) status = Status.Idle
    }
}
