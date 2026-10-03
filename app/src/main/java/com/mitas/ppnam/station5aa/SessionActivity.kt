package com.mitas.ppnam.station5aa

import androidx.appcompat.app.AppCompatActivity

/**
 * Base for every screen that requires a signed-in operator: each touch or key press
 * counts as activity for the inactivity auto-logout. Scanner broadcasts don't pass
 * through onUserInteraction, so receivers call SessionGuard.touch() themselves.
 */
abstract class SessionActivity : AppCompatActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionGuard.touch()
    }
}
