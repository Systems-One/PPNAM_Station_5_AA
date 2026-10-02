package com.mitas.ppnam.station5aa

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Forces light (white) status bar icons, matching this app's always-dark background.
 * enableEdgeToEdge()'s own light/dark heuristic doesn't resolve consistently across every
 * screen, leaving status bar icons unreadable on some activities - this makes it explicit.
 */
fun Activity.forceLightStatusBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
}

/**
 * Dismisses the soft keyboard. Called on submit so the result (error line, status row) is
 * never drawn underneath the IME (UI audit S5-01).
 */
fun Activity.hideKeyboard() {
    WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
}
