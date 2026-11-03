package com.mitas.ppnam.station5aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mitas.ppnam.station5aa.cage.CageController
import com.mitas.ppnam.station5aa.cage.CageLoad
import com.mitas.ppnam.station5aa.cage.CageScreen
import com.mitas.ppnam.station5aa.cage.CageUiState
import com.mitas.ppnam.station5aa.cage.LoadedPack
import com.mitas.ppnam.station5aa.cage.Notice
import com.mitas.ppnam.station5aa.cage.StatusText
import com.mitas.ppnam.station5aa.databinding.ActivityMainBinding
import com.mitas.ppnam.station5aa.databinding.ItemCagePackBinding
import com.mitas.ppnam.station5aa.databinding.SheetCageCompleteBinding
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Post-login home: the Cage Loading screen, Station 5's single workflow (spec §4). The workflow
 * itself lives in [CageController]; this activity forwards scans while resumed and renders
 * [CageUiState]. An operator whose allowedTabs lack cage_loading (fail-closed — see
 * OperatorSession.canShow) sees the no-workflows card instead.
 */
class MainActivity : SessionActivity() {

    private lateinit var binding: ActivityMainBinding
    private val main = Handler(Looper.getMainLooper())

    private var previewSheet: BottomSheetDialog? = null
    private var previewSheetBinding: SheetCageCompleteBinding? = null
    private var previewSheetToken: String? = null
    private var renderedPacks: List<LoadedPack>? = null

    private val cageListener: (CageUiState) -> Unit = { render(it) }

    /** Ticks once a second while a completion summary is shown; expires it at zero. */
    private val countdown = object : Runnable {
        override fun run() {
            val preview = CageController.state.screen as? CageScreen.Preview ?: return
            val left = remainingMs(preview.expiresAtUtc) ?: return // unparseable: the station still enforces it
            if (left <= 0) {
                // Ignored by the workflow while the confirmation is in flight; keep checking until it settles.
                CageController.previewExpired() // re-renders synchronously, which re-posts this
                main.removeCallbacks(this)
                if (CageController.state.screen is CageScreen.Preview) main.postDelayed(this, 1000)
                return
            }
            val secs = ((left + 999) / 1000).toInt()
            previewSheetBinding?.tvSheetCountdown?.text = getString(R.string.cage_expires_in, secs / 60, secs % 60)
            main.postDelayed(this, 1000)
        }
    }

    private val rfidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent?.getStringExtra("data")?.trim().orEmpty()
            if (data.isEmpty()) return
            SessionGuard.touch()
            if (data == ScannerApp.SETTINGS_RFID) return // ScannerApp opens Settings for this tag
            CageController.onRfid(data)
        }
    }

    private val barcodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent?.getStringExtra("data").orEmpty()
            if (data.isBlank()) return
            SessionGuard.touch()
            CageController.onQr(data)
        }
    }

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
        // Explicit dark styles: the default auto() style enforces a light contrast scrim over
        // the three-button nav bar, which is what made this screen's bottom strip light grey
        // while every other screen's was the window colour (UI audit group (h)).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContentView(binding.root)
        forceLightStatusBarIcons()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            // systemBars() alone would defeat the manifest's adjustResize if a field is ever
            // added here (UI audit group (a) sub-cause 2) — pad for the keyboard too.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupDashboard()

        // System Back used to finish the app silently (UI audit S5-05 / group (d)); every
        // station confirms with the same "Close the app?" dialog as the Login screen.
        onBackPressedDispatcher.addCallback(this) { showExitDialog() }

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

        // allowedTabs gating is fail-closed and display-only (the station re-checks server-side).
        val cageAllowed = OperatorSessionHolder.session?.canShow(StationTab.CAGE_LOADING) == true
        binding.cage.root.isVisible = cageAllowed
        binding.cardNoWorkflows.isVisible = !cageAllowed

        binding.cage.btnRefresh.setOnClickListener { CageController.refreshCompleted() }
        binding.btnRefusalGotIt.setOnClickListener { CageController.dismissRefusal() }
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        CageController.addListener(cageListener)
        render(CageController.state)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // Scans count only while this screen is in front (the scanner broadcasts reach every app).
        val rfid = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        val barcode = IntentFilter("com.scanner.broadcast")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidReceiver, rfid, Context.RECEIVER_EXPORTED)
            registerReceiver(barcodeReceiver, barcode, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidReceiver, rfid)
            registerReceiver(barcodeReceiver, barcode)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::binding.isInitialized) return
        unregisterReceiver(rfidReceiver)
        unregisterReceiver(barcodeReceiver)
    }

    override fun onStop() {
        super.onStop()
        if (!::binding.isInitialized) return
        CageController.removeListener(cageListener)
        main.removeCallbacks(countdown)
        dismissPreviewSheet()
    }

    // ---- Cage Loading rendering -----------------------------------------------------------

    private fun render(s: CageUiState) {
        val v = binding.cage
        val screen = s.screen

        renderNotice(s.notice)
        v.rowSending.isVisible = s.sending

        // Loading and Preview both show the load (Preview dims it under the bottom sheet).
        val shown: Triple<CageLoad, List<LoadedPack>, LoadedPack?>? = when (screen) {
            is CageScreen.Loading -> Triple(screen.load, screen.packs, screen.lastPack)
            is CageScreen.Preview -> Triple(screen.load, screen.packs, null)
            else -> null
        }
        v.tvIdlePrompt.isVisible = screen is CageScreen.Idle
        listOf(v.cardBanner, v.rowTiles, v.layoutScanPrompt).forEach { it.isVisible = shown != null }
        v.cardLastPack.isVisible = shown?.third != null
        if (shown != null) {
            val (load, packs, lastPack) = shown
            v.tvBannerCage.text = load.cageId
            val jobPack = lastPack ?: packs.firstOrNull()
            v.tvBannerJob.isVisible = jobPack != null
            if (jobPack != null) v.tvBannerJob.text = jobLine(jobPack)
            v.tvPackCount.text = load.packCount.toString()
            v.tvTotalKg.text = kg(load.totalKg)
            if (lastPack != null) {
                v.tvLastPack.text = getString(
                    R.string.cage_last_pack, lastPack.bagId, kg(lastPack.weightKg), disposition(lastPack.disposition),
                )
            }
        }
        renderPacks(shown?.second.orEmpty())

        val completed = (screen as? CageScreen.Completed)?.load
        v.cardCompleted.isVisible = completed != null
        v.tvNextCagePrompt.isVisible = completed != null
        if (completed != null) {
            v.tvCompletedTitle.text =
                getString(R.string.cage_completed_title, completed.cageId, completed.packCount, kg(completed.totalKg))
            v.tvGrv.text = getString(R.string.cage_sap_receipt, StatusText.grv(completed.grvState))
            v.tvPrint.text = getString(R.string.cage_manifest_print, StatusText.print(completed.printState))
            v.btnRefresh.isEnabled = !s.sending
        }

        val refusal = s.refusal
        binding.layoutRefusal.isVisible = refusal != null
        if (refusal != null) {
            binding.tvRefusalHeadline.text = refusal.headline
            binding.tvRefusalDetail.text = refusal.detail
            binding.tvRefusalCode.text = getString(R.string.cage_refusal_code, refusal.code)
        }

        // The sheet is its own window and would hide the refusal overlay, so it closes for one.
        if (screen is CageScreen.Preview && refusal == null) showPreviewSheet(screen, s.sending) else dismissPreviewSheet()
        main.removeCallbacks(countdown)
        if (screen is CageScreen.Preview) main.post(countdown)
    }

    private fun renderNotice(notice: Notice?) {
        val tv = binding.cage.tvNotice
        tv.isVisible = notice != null
        if (notice == null) return
        tv.text = notice.text
        val (bg, fg) = when (notice.tone) {
            Notice.Tone.INFO -> R.color.light_blue to R.color.text_primary
            Notice.Tone.SUCCESS -> R.color.light_green to R.color.success
            Notice.Tone.WARNING -> R.color.light_amber to R.color.warning
        }
        tv.setBackgroundColor(ContextCompat.getColor(this, bg))
        tv.setTextColor(ContextCompat.getColor(this, fg))
    }

    /** Packs in the order the workflow holds them (an allocation is prepended: newest first). */
    private fun renderPacks(packs: List<LoadedPack>) {
        val v = binding.cage
        v.tvPacksHeader.isVisible = packs.isNotEmpty()
        if (packs == renderedPacks) return
        renderedPacks = packs
        v.packList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        packs.forEach { pack ->
            val row = ItemCagePackBinding.inflate(inflater, v.packList, false)
            row.tvBagId.text = pack.bagId
            row.tvPackDetail.text = jobLine(pack)
            row.tvPackKg.text = getString(R.string.cage_kg_value, kg(pack.weightKg))
            v.packList.addView(row.root)
        }
    }

    private fun showPreviewSheet(preview: CageScreen.Preview, sending: Boolean) {
        if (previewSheet == null || previewSheetToken != preview.token) {
            dismissPreviewSheet()
            val sheetBinding = SheetCageCompleteBinding.inflate(layoutInflater)
            val dialog = BottomSheetDialog(this).apply {
                setContentView(sheetBinding.root)
                // Only the two buttons close it; never auto-confirmed (spec §4.4).
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                behavior.isDraggable = false
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            sheetBinding.btnKeepLoading.setOnClickListener { CageController.keepLoading() }
            sheetBinding.btnCompleteCage.setOnClickListener { CageController.confirmComplete() }
            previewSheet = dialog
            previewSheetBinding = sheetBinding
            previewSheetToken = preview.token
            dialog.show()
        }
        val b = previewSheetBinding ?: return
        b.tvSheetTitle.text = getString(R.string.cage_complete_title, preview.load.cageId)
        b.tvSheetSummary.text = getString(R.string.cage_packs_kg, preview.load.packCount, kg(preview.load.totalKg))
        val jobPack = preview.packs.firstOrNull()
        b.tvSheetJob.isVisible = jobPack != null
        if (jobPack != null) b.tvSheetJob.text = jobLine(jobPack)
        b.tvSheetCountdown.isVisible = remainingMs(preview.expiresAtUtc) != null
        b.rowSheetSending.isVisible = sending
        b.btnKeepLoading.isEnabled = !sending
        b.btnCompleteCage.isEnabled = !sending
    }

    private fun dismissPreviewSheet() {
        previewSheet?.dismiss()
        previewSheet = null
        previewSheetBinding = null
        previewSheetToken = null
    }

    /** Milliseconds until [expiresAtUtc] by the device clock, or null when it cannot be parsed. */
    private fun remainingMs(expiresAtUtc: String): Long? = try {
        Instant.parse(expiresAtUtc).toEpochMilli() - System.currentTimeMillis()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun jobLine(pack: LoadedPack): String =
        getString(R.string.cage_job_line, pack.jobCard ?: "—", pack.outerCode)

    private fun kg(value: BigDecimal): String = String.format(Locale.US, "%.2f", value)

    private fun disposition(value: String): String = when (value) {
        "Pass" -> getString(R.string.cage_disposition_pass)
        "ManagerAccepted" -> getString(R.string.cage_disposition_manager_accepted)
        else -> value
    }

    // ---- dialogs --------------------------------------------------------------------------

    private fun showLogoutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.logout_dialog_title))
            .setMessage(
                getString(
                    if (CageController.hasPending()) R.string.logout_pending_message
                    else R.string.logout_dialog_message
                )
            )
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

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.exit_dialog_title))
            .setMessage(getString(R.string.exit_dialog_message))
            .setPositiveButton(getString(R.string.exit_dialog_close)) { _, _ -> finishAffinity() }
            .setNegativeButton(getString(R.string.exit_dialog_stay), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::binding.isInitialized) return // finished in onCreate for want of a session
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
    }
}
