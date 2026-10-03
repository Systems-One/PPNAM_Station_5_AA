package com.mitas.ppnam.station5aa

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.core.widget.doAfterTextChanged
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
    private lateinit var settingsRepository: SettingsRepository
    private val applyHandler = Handler(Looper.getMainLooper())
    private var pendingConnectionListener: ((Boolean) -> Unit)? = null
    private var applyTimeout: Runnable? = null
    /** Set synchronously on entry: the listener guard below only exists after the async disconnect. */
    private var applying = false

    /** Settings is also reachable from Login; it only needs a session if it was opened with one. */
    override fun requiresSession(): Boolean = signedInAtCreate

    private enum class ApplyStatus { HIDDEN, TESTING, SUCCESS, FAILED }

    private companion object {
        /** Single attempt, same budget as the login round trip. */
        const val APPLY_TIMEOUT_MS = 10_000L
        /** Re-lock the gate shortly after a successful apply. */
        const val RELOCK_DELAY_MS = 2_000L
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

        settingsRepository = SettingsRepository(this)
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

        binding.btnSaveSettings.setOnClickListener { testAndApply() }
        // Typing again clears a field's inline error.
        listOf(binding.tilBrokerHost, binding.tilBrokerPort, binding.tilBrokerUsername,
            binding.tilBrokerPassword, binding.tilAutoLogout).forEach { til ->
            til.editText?.doAfterTextChanged { til.error = null }
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
        val brand = getColor(R.color.brand_tint) // label text on the dark card: tint, not the fill amber
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

    /**
     * Validation (host, port, credential, minutes), each error shown inline under its field.
     * Returns null when something is wrong (focus moves to it).
     */
    private fun validatedInput(): Pair<BrokerSettings, Int>? {
        val host = binding.etBrokerHost.text.toString().trim()
        if (host.isBlank()) {
            binding.tilBrokerHost.error = getString(R.string.error_host_required)
            binding.etBrokerHost.requestFocus()
            return null
        }
        val port = BrokerSettings.parsePort(binding.etBrokerPort.text.toString())
        if (port == null) {
            binding.tilBrokerPort.error = getString(R.string.error_port_invalid)
            binding.etBrokerPort.requestFocus()
            return null
        }
        val username = binding.etBrokerUsername.text.toString().trim()
        if (username.isBlank()) {
            binding.tilBrokerUsername.error = getString(R.string.error_broker_username_required)
            binding.etBrokerUsername.requestFocus()
            return null
        }
        // Blank field keeps the already-provisioned password: the repository only writes a
        // non-blank password to the Keystore. With nothing stored either, there is nothing to test.
        val password = binding.etBrokerPassword.text.toString()
            .ifBlank { settingsRepository.brokerSettings().password }
        if (password.isBlank()) {
            binding.tilBrokerPassword.error = getString(R.string.error_broker_password_required)
            binding.etBrokerPassword.requestFocus()
            return null
        }
        val minutes = AutoLogout.parseMinutes(binding.etAutoLogout.text.toString())
        if (minutes == null) {
            binding.tilAutoLogout.error = getString(R.string.error_auto_logout_minutes)
            binding.etAutoLogout.requestFocus()
            return null
        }
        val settings = BrokerSettings(
            host = host,
            port = port,
            useWebSocket = binding.swBrokerWebSocket.isChecked,
            useTls = binding.swBrokerTls.isChecked,
            username = username,
            password = password,
        )
        return settings to minutes
    }

    /**
     * Test & Apply (UI audit section 5): save, reconnect against the new broker and report the
     * outcome in place. The operator stays on this screen and keeps their session.
     *
     * Settings are persisted BEFORE the old link is dropped, and the reconnect always happens
     * even if this screen is gone by then - otherwise the handheld would be left Offline with
     * wantsConnection=false. The 10 s verdict budget starts at the button press.
     */
    private fun testAndApply() {
        if (applying) return
        val (newSettings, minutes) = validatedInput() ?: return
        hideKeyboard()

        val mqtt = MqttManager.getInstance(this)
        // Keystore first: if the credential cannot be stored nothing else changes and the
        // current connection is left alone.
        if (!settingsRepository.save(newSettings)) {
            binding.tilBrokerPassword.error = getString(R.string.error_password_store)
            return
        }
        settingsRepository.saveAutoLogoutMinutes(minutes)
        SessionGuard.applyTimeout()

        applying = true
        setApplyInFlight(true)
        showApplyStatus(ApplyStatus.TESTING)
        cancelPendingApply(mqtt)

        var registering = false
        lateinit var listener: (Boolean) -> Unit
        listener = { connected ->
            // addConnectionListener replays the current state once; that is not a verdict.
            if (connected && !registering) runOnUiThread {
                if (pendingConnectionListener === listener) {
                    cancelPendingApply(mqtt)
                    onApplyResult(connected = true, settings = newSettings)
                }
            }
        }
        val timeout = Runnable {
            if (pendingConnectionListener === listener) {
                cancelPendingApply(mqtt)
                onApplyResult(connected = false, settings = newSettings)
                // A hung disconnect must never strand the handheld offline.
                if (!mqtt.isConnected() && !mqtt.isConnectAttemptInFlight()) mqtt.connect()
            }
        }
        pendingConnectionListener = listener
        applyTimeout = timeout
        applyHandler.postDelayed(timeout, APPLY_TIMEOUT_MS)

        // Properly disconnect from the OLD broker first (publishes presence offline).
        mqtt.disconnect {
            runOnUiThread {
                if (isFinishing || isDestroyed || pendingConnectionListener !== listener) {
                    // Screen gone, or the apply already timed out (a late disconnect callback must
                    // not tear down whatever the timeout's fallback connected): no views to touch,
                    // but the handheld must not stay offline.
                    mqtt.connect()
                    return@runOnUiThread
                }
                registering = true
                mqtt.addConnectionListener(listener)
                registering = false
                // force: an attempt that was in flight against the old settings is abandoned,
                // otherwise connect()'s isConnecting guard would swallow this one.
                mqtt.connect(force = true)
            }
        }
    }

    private fun cancelPendingApply(mqtt: MqttManager) {
        pendingConnectionListener?.let { mqtt.removeConnectionListener(it) }
        pendingConnectionListener = null
        applyTimeout?.let { applyHandler.removeCallbacks(it) }
        applyTimeout = null
    }

    private fun onApplyResult(connected: Boolean, settings: BrokerSettings) {
        if (isFinishing || isDestroyed) return
        setApplyInFlight(false)
        if (connected) {
            showApplyStatus(ApplyStatus.SUCCESS)
            applyHandler.postDelayed({ if (!isFinishing && !isDestroyed) relockPinGate() }, RELOCK_DELAY_MS)
        } else {
            showApplyStatus(
                ApplyStatus.FAILED,
                getString(R.string.settings_apply_failed, settings.host, settings.port)
            )
        }
    }

    private fun setApplyInFlight(inFlight: Boolean) {
        applying = inFlight
        binding.btnSaveSettings.isEnabled = !inFlight
    }

    private fun showApplyStatus(status: ApplyStatus, message: String? = null) {
        binding.layoutApplyStatus.visibility =
            if (status == ApplyStatus.HIDDEN) View.GONE else View.VISIBLE
        binding.progressApply.visibility =
            if (status == ApplyStatus.TESTING) View.VISIBLE else View.GONE
        val (text, colorRes) = when (status) {
            ApplyStatus.HIDDEN -> "" to R.color.text_muted
            ApplyStatus.TESTING -> getString(R.string.settings_testing) to R.color.text_muted
            ApplyStatus.SUCCESS -> getString(R.string.settings_apply_success) to R.color.success
            ApplyStatus.FAILED -> (message ?: "") to R.color.danger
        }
        binding.tvApplyStatus.text = text
        binding.tvApplyStatus.setTextColor(getColor(colorRes))
        if (status != ApplyStatus.HIDDEN) binding.btnSaveSettings.post { binding.btnSaveSettings.scrollIntoView() }
    }

    private fun submitPin() {
        val now = System.currentTimeMillis()
        when (val outcome = pinGate.submit(binding.etPin.text.toString(), now)) {
            PinGate.Outcome.Blank -> return // nothing typed: not an attempt (UI audit group (c))
            PinGate.Outcome.Unlocked -> {
                hidePinMessages()
                hideKeyboard()
                showApplyStatus(ApplyStatus.HIDDEN)
                binding.cardPinLock.visibility = View.GONE
                binding.groupSettingsFields.visibility = View.VISIBLE
                binding.groupApplyButton.visibility = View.VISIBLE
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
        binding.groupApplyButton.visibility = View.GONE
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
        cancelPendingApply(MqttManager.getInstance(this))
        applyHandler.removeCallbacksAndMessages(null)
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
    }
}
