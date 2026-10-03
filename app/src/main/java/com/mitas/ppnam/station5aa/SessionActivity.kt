package com.mitas.ppnam.station5aa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that requires a signed-in operator: each touch or key press
 * counts as activity for the inactivity auto-logout. Scanner broadcasts don't pass
 * through onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {

    /** Whether a session existed when this screen was created (Settings is reachable without one). */
    protected var signedInAtCreate = false
        private set

    /** Screens that need an operator return true; Settings only when it was opened signed in. */
    protected open fun requiresSession(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signedInAtCreate = OperatorSessionHolder.session != null
    }

    /**
     * A sign-out that happened while the app was backgrounded cleared the session, but
     * SessionGuard's Login launch from the Application context is blocked on Android 10+, so
     * this screen is still on top. Catch that on return and go to Login with the reason.
     */
    override fun onResume() {
        super.onResume()
        if (requiresSession() && OperatorSessionHolder.session == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                PendingSignedOutReason.take()?.let { putExtra(LoginActivity.EXTRA_SIGNED_OUT_REASON, it) }
            })
            finish()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
