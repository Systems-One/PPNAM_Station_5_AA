package com.mitas.ppnam.station5aa

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mitas.ppnam.station5aa.databinding.ActivitySettingsBinding

class SettingsActivity : SessionActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val connectionStatusListener: (ConnectionStatus) -> Unit = { status ->
        runOnUiThread {
            binding.connectionPill.setStatus(status)
            updateDiagnostics(status)
        }
    }

    private lateinit var pinGateStore: PinGateStore
    private lateinit var pinGate: PinGate
    private val tickHandler = Handler(Looper.getMainLooper())
    private val lockoutTicker = object : Runnable {
        override fun run() = renderLockout()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        forceLightStatusBarIcons()

        setupToolbar()
        MqttManager.getInstance(this).addConnectionStatusListener(connectionStatusListener)

        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvDeviceId.text = DeviceIdentity.deviceId(this)
        setupSessionSection()
        pinGateStore = PinGateStore(this)
        pinGate = pinGateStore.load()

        val settingsRepository = SettingsRepository(this)
        val current = settingsRepository.brokerSettings()

        binding.etBrokerHost.setText(current.host)
        binding.etBrokerPort.setText(current.port.toString())
        binding.swBrokerWebSocket.isChecked = current.useWebSocket
        binding.swBrokerTls.isChecked = current.useTls
        binding.etBrokerUsername.setText(current.username)
        binding.etAutoLogout.setText(settingsRepository.autoLogoutMinutes().toString())
        // The password field stays empty: the stored credential is never echoed back into the UI.
        // A blank field on save means "keep the provisioned password" (see save below).

        binding.btnUnlock.setOnClickListener { submitPin() }
        binding.etPin.setOnSubmit { submitPin() }
        // Done on the last broker field is the same gesture as tapping the primary button.
        binding.etAutoLogout.setOnSubmit { binding.btnSaveSettings.performClick() }

        binding.btnSaveSettings.setOnClickListener {
            val host = binding.etBrokerHost.text.toString().trim()
            val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
            if (host.isBlank()) {
                binding.etBrokerHost.error = "Host required"
                return@setOnClickListener
            }
            if (port == null) {
                binding.etBrokerPort.error = "Invalid port (1–65535)"
                return@setOnClickListener
            }

            val autoLogoutMinutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
            if (autoLogoutMinutes == null) {
                binding.tilAutoLogout.error = getString(R.string.error_auto_logout_minutes)
                return@setOnClickListener
            }
            binding.tilAutoLogout.error = null
            settingsRepository.saveAutoLogoutMinutes(autoLogoutMinutes)
            SessionGuard.applyTimeout()

            val typedPassword = binding.etBrokerPassword.text.toString()
            val newSettings = BrokerSettings(
                host = host,
                port = port,
                useWebSocket = binding.swBrokerWebSocket.isChecked,
                useTls = binding.swBrokerTls.isChecked,
                username = binding.etBrokerUsername.text.toString().trim(),
                // Blank field keeps the already-provisioned password: the repository only
                // writes a non-blank password to the Keystore.
                password = typedPassword.ifBlank { settingsRepository.brokerSettings().password },
            )

            // 1. Properly disconnect from the OLD broker first
            MqttManager.getInstance(this).disconnect {
                runOnUiThread {
                    // 2. Save the new settings after the old presence is offline
                    if (!settingsRepository.save(newSettings)) {
                        binding.etBrokerPassword.error = "Could not store the password securely"
                        MqttManager.getInstance(this).connect()
                        return@runOnUiThread
                    }

                    // 3. Reconnect against the new broker
                    MqttManager.getInstance(this).connect()

                    // Restart app to apply changes
                    val intent = Intent(this, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
        }

        binding.btnUnlock.applyPressScaleFeedback()
        binding.btnSaveSettings.applyPressScaleFeedback()
        binding.btnLogOut.applyPressScaleFeedback()

        // A lockout that was running when the screen was last left is still running now.
        renderLockout()
        onBackPressedDispatcher.addCallback(this) { finishBackward() }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    /**
     * The Diagnostics card, mirroring Station 2's SettingsScreen: broker link and station
     * presence are separate failures with separate remedies, and the composite pill can only
     * name one of them at a time — so both get their own row here. The broker row uses the
     * toolbar pill's own words (Offline / Reconnecting / Connected) so one state never has two
     * names on the same screen (UI audit S5-08).
     */
    private fun updateDiagnostics(status: ConnectionStatus) {
        val green = getColor(R.color.success)
        val brand = getColor(R.color.primary_action)
        val red = getColor(R.color.danger)
        val muted = getColor(R.color.text_muted)

        when (status) {
            ConnectionStatus.CONNECTED, ConnectionStatus.STATION_OFFLINE ->
                binding.pillBroker.setAppearance(green, getString(R.string.diag_broker_connected))
            ConnectionStatus.RECONNECTING ->
                binding.pillBroker.setAppearance(brand, getString(R.string.diag_broker_reconnecting))
            ConnectionStatus.OFFLINE ->
                binding.pillBroker.setAppearance(red, getString(R.string.diag_broker_offline))
        }

        // With the broker down, the retained presence value is stale rather than false — saying
        // "offline" there would blame the station for the broker's fault.
        when (status) {
            ConnectionStatus.CONNECTED ->
                binding.pillStation.setAppearance(green, getString(R.string.diag_station_online))
            ConnectionStatus.STATION_OFFLINE ->
                binding.pillStation.setAppearance(brand, getString(R.string.diag_station_offline))
            else -> binding.pillStation.setAppearance(muted, getString(R.string.diag_station_unknown))
        }
    }

    /**
     * The Session card, mirroring Station 2's: the home screen's operator label is one route to
     * switching users, and Settings is the obvious second home for it.
     */
    private fun setupSessionSection() {
        val session = OperatorSessionHolder.session
        if (session == null) {
            binding.groupSession.visibility = View.GONE
            return
        }
        binding.groupSession.visibility = View.VISIBLE
        binding.tvSignedInAs.text =
            if (session.role.isNotBlank()) "${session.operatorName} · ${session.role}"
            else session.operatorName
        binding.btnLogOut.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.logout_dialog_title))
                .setMessage(getString(R.string.logout_dialog_message))
                .setPositiveButton(getString(R.string.btn_log_out)) { _, _ ->
                    AuthClient(this).logout {
                        startActivity(Intent(this, LoginActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        })
                        finish()
                    }
                }
                .setNegativeButton(getString(R.string.btn_cancel), null)
                .show()
        }
    }

    private fun submitPin() {
        val now = System.currentTimeMillis()
        when (val outcome = pinGate.submit(binding.etPin.text.toString(), now)) {
            PinGate.Outcome.Blank -> return // nothing typed: not an attempt (UI audit group (c))
            PinGate.Outcome.Unlocked -> {
                hidePinMessages()
                hideKeyboard()
                binding.cardPinLock.visibility = View.GONE
                binding.groupSettingsFields.visibility = View.VISIBLE
            }
            is PinGate.Outcome.Wrong -> {
                binding.etPin.setText("")
                showErrorMessage(
                    resources.getQuantityString(
                        R.plurals.pin_attempts_left, outcome.attemptsLeft, outcome.attemptsLeft
                    )
                )
            }
            is PinGate.Outcome.LockedOut -> {
                binding.etPin.setText("")
                renderLockout()
            }
        }
        pinGateStore.save(pinGate)
    }

    /** Shows the live countdown while locked (1 s ticker) and disables the gate's inputs. */
    private fun renderLockout() {
        val remainingMs = pinGate.remainingMs(System.currentTimeMillis())
        tickHandler.removeCallbacks(lockoutTicker)
        if (remainingMs <= 0L) {
            if (binding.tvPinLockout.visibility == View.VISIBLE) hidePinMessages()
            setPinInputEnabled(true)
            return
        }
        setPinInputEnabled(false)
        val seconds = ((remainingMs + 999) / 1_000).toInt()
        showLockoutMessage(getString(R.string.pin_locked_out, seconds))
        tickHandler.postDelayed(lockoutTicker, 1_000)
    }

    private fun setPinInputEnabled(enabled: Boolean) {
        binding.etPin.isEnabled = enabled
        binding.btnUnlock.isEnabled = enabled
    }

    /** Hides the form behind the PIN gate again (used after a successful Test & Apply). */
    private fun relockPinGate() {
        binding.etPin.setText("")
        hidePinMessages()
        binding.groupSettingsFields.visibility = View.GONE
        binding.cardPinLock.visibility = View.VISIBLE
    }

    private fun showErrorMessage(message: String) {
        binding.tvPinError.text = message
        binding.tvPinError.visibility = View.VISIBLE
        binding.tvPinLockout.visibility = View.GONE
    }

    private fun showLockoutMessage(message: String) {
        binding.tvPinLockout.text = message
        binding.tvPinLockout.visibility = View.VISIBLE
        binding.tvPinError.visibility = View.GONE
    }

    private fun hidePinMessages() {
        binding.tvPinError.visibility = View.GONE
        binding.tvPinLockout.visibility = View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishBackward()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(lockoutTicker)
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
