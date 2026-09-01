package com.mitas.ppnam.station5aa

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mitas.ppnam.station5aa.databinding.ActivityMainBinding

/**
 * Post-login home, in Station 1's MainActivity style but without workflow tiles: Station 5
 * defines no workflow tabs yet, so allowedTabs gating (fail-closed — see OperatorSession.canShow)
 * leaves an operator with none, and the dashboard says so instead of showing an empty grid.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
        }
    }

    private val stationStatusListener: (Boolean) -> Unit = { online ->
        runOnUiThread {
            if (online) {
                binding.layoutStationOffline.visibility = android.view.View.GONE
            } else {
                binding.layoutStationOffline.visibility = android.view.View.VISIBLE
                // Bring MainActivity to front and clear others
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No session (fresh process, or logged out) — the dashboard requires an operator.
        if (OperatorSessionHolder.session == null) {
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        setContentView(binding.root)
        forceLightStatusBarIcons()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupDashboard()

        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).addStationStatusListener(stationStatusListener)
    }

    private fun setupDashboard() {
        binding.btnSettings.setOnClickListener {
            startActivityForward(Intent(this, SettingsActivity::class.java))
        }

        // Operator control, mirroring Station 2's top bar: shows "name · role", tapping it asks
        // to log out.
        OperatorSessionHolder.session?.let { session ->
            binding.tvOperator.text =
                if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
                else session.operatorName
        }
        binding.layoutOperator.setOnClickListener { showLogoutDialog() }

        // The login response decides which sub-apps this operator gets (allowedTabs, fail-closed
        // on a missing/empty list — display gating only, the station re-checks server-side).
        // Station 5 defines no tabs (StationTab is empty), so nothing can pass canShow and the
        // dashboard shows the no-workflows message unconditionally.
    }

    private fun showLogoutDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
            .setTitle(getString(R.string.logout_dialog_title))
            .setMessage(getString(R.string.logout_dialog_message))
            .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                AuthClient(this).logout {
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
    }
}
