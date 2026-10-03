package com.mitas.ppnam.station5aa

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Process-wide owner of the inactivity sign-out (copied from Station 1's SessionGuard; the
 * station-offline sign-out is deliberately not ported - Station 5 keeps its overlay).
 *
 * Ends in [signOut]: best-effort `reader_logout_requested`, session cleared, and
 * LoginActivity brought up with the reason so the operator knows what happened. Installed
 * once from ScannerApp; activities only ever call [touch] (any interaction or scan).
 */
object SessionGuard {

    private const val TAG = "SessionGuard"

    private lateinit var app: Application
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitor: InactivityMonitor? = null

    fun install(app: Application) {
        this.app = app
        monitor = InactivityMonitor(
            now = { SystemClock.elapsedRealtime() },
            schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
            cancel = { r -> mainHandler.removeCallbacks(r) },
            onExpired = {
                val minutes = SettingsRepository(app).autoLogoutMinutes()
                signOut(app.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes))
            },
        )

        // Start/stop the inactivity timer with the session itself.
        OperatorSessionHolder.addListener { session ->
            mainHandler.post { if (session == null) monitor?.stop() else applyTimeout() }
        }

        // A deadline that passed while the app was backgrounded is caught on the next resume.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { checkNow() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor?.touch() }
    }

    fun checkNow() {
        monitor?.checkNow()
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        if (OperatorSessionHolder.session == null) return
        val minutes = SettingsRepository(app).autoLogoutMinutes()
        monitor?.start(AutoLogout.timeoutMs(minutes))
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    fun signOut(reason: String) {
        if (OperatorSessionHolder.session == null) return
        Log.i(TAG, "Signing out: $reason")
        PendingSignedOutReason.set(reason)
        AuthClient(app).logout {
            app.startActivity(Intent(app, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, reason)
            })
        }
    }
}
