package com.mitas.ppnam.station5aa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
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

    /** Text of the notice last rendered, so only a new notice scrolls the screen to the top. */
    private var renderedNoticeText: String? = null

    private val cageListener: (CageUiState) -> Unit = { render(it) }

    /** True between onStart and onStop: the countdown ticker runs only then. */
    private var ticking = false

    /** The arm expiry already reported to the workflow, so each arm is expired once. */
    private var armExpiryReported: Long? = null

    /**
     * Ticks about once a second between onStart and onStop and drives both countdowns: the
     * Pack arm on Loading and the completion summary on Preview. Expiry times are device-clock
     * instants anchored at receipt and measured on the station's clock by the workflow.
     */
    private val ticker = object : Runnable {
        override fun run() {
            val delay = tick()
            // tick() may re-render (expiry), which re-posts this: keep exactly one pending tick.
            main.removeCallbacks(this)
            if (ticking) main.postDelayed(this, delay)
        }
    }

    /** One countdown step; returns the delay to the next one (lands on the next whole second left). */
    private fun tick(): Long {
        val s = CageController.state
        val now = System.currentTimeMillis()
        when (val screen = s.screen) {
            is CageScreen.Preview -> {
                val left = screen.expiresAtMs - now
                if (left <= 0) {
                    // Ignored by the workflow while the confirmation is in flight; checked again next tick.
                    CageController.previewExpired()
                    return TICK_MS
                }
                previewSheetBinding?.tvSheetCountdown?.text = summaryCountdown(left)
                return untilNextSecond(left)
            }
            is CageScreen.Loading -> {
                val until = screen.armExpiresAtMs ?: return TICK_MS
                val left = until - now
                if (left <= 0) {
                    if (armExpiryReported != until) {
                        armExpiryReported = until
                        CageController.armExpired()
                    }
                    return TICK_MS
                }
                if (!s.sending) binding.cage.tvStepDetail.text = armCountdown(left)
                return untilNextSecond(left)
            }
            else -> return TICK_MS
        }
    }

    private fun untilNextSecond(leftMs: Long): Long = ((leftMs - 1) % TICK_MS) + 1

    private fun secondsLeft(leftMs: Long): Int = ((leftMs.coerceAtLeast(0) + 999) / 1000).toInt()

    private fun armCountdown(leftMs: Long): String =
        secondsLeft(leftMs).let { getString(R.string.cage_arm_countdown, it / 60, it % 60) }

    private fun summaryCountdown(leftMs: Long): String =
        secondsLeft(leftMs).let { getString(R.string.cage_expires_in, it / 60, it % 60) }

    /** Re-runs the ticker now, so a new countdown starts on its own second boundaries. */
    private fun restartTicker() {
        main.removeCallbacks(ticker)
        if (ticking) main.post(ticker)
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

    /**
     * Station offline (no retained `online`, or the broker link is down): the overlay covers the
     * screen and the workflow blocks scans. ScannerApp brings this screen to the front when the
     * station goes offline while another screen is showing.
     */
    private val stationStatusListener: (Boolean) -> Unit = { online ->
        runOnUiThread {
            binding.layoutStationOffline.isVisible = !online
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

        // The layout's own padding is the screen gutter: add the insets to it, never replace it,
        // or every card runs edge to edge (visual critique 2026-11-06, P1-1).
        val base = binding.main.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            // systemBars() alone would defeat the manifest's adjustResize if a field is ever
            // added here (UI audit group (a) sub-cause 2) — pad for the keyboard too.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
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
        // The row draws 24dp tall; grow its touch area to at least 48dp without changing the
        // header card's layout (suite C2) (skill-run critique 2026-11-06, P2-9).
        val operatorRow = binding.layoutOperator
        (operatorRow.parent as View).post {
            val r = Rect()
            operatorRow.getHitRect(r)
            val grow = maxOf(0, ((48 * resources.displayMetrics.density).toInt() - r.height() + 1) / 2)
            r.inset(0, -grow)
            (operatorRow.parent as View).touchDelegate = TouchDelegate(r, operatorRow)
        }

        // allowedTabs gating is fail-closed and display-only (the station re-checks server-side).
        val cageAllowed = OperatorSessionHolder.session?.canShow(StationTab.CAGE_LOADING) == true
        binding.cage.root.isVisible = cageAllowed
        binding.cardNoWorkflows.isVisible = !cageAllowed

        binding.cage.btnRefresh.setOnClickListener { CageController.refreshCompleted() }
        binding.cage.btnCompleteCage.setOnClickListener { CageController.completeCage() }
        binding.btnRefusalGotIt.setOnClickListener { CageController.dismissRefusal() }
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        ticking = true
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
        ticking = false
        CageController.removeListener(cageListener)
        main.removeCallbacks(ticker)
        dismissPreviewSheet()
    }

    // ---- Cage Loading rendering -----------------------------------------------------------

    private fun render(s: CageUiState) {
        val v = binding.cage
        val screen = s.screen

        renderNotice(s.notice)
        // While a request is in flight scans are ignored: say so where the next step would be.
        v.rowSending.isVisible = s.sending

        // Loading and Preview both show the load (Preview dims it under the bottom sheet).
        val shown: Triple<CageLoad, List<LoadedPack>, LoadedPack?>? = when (screen) {
            is CageScreen.Loading -> Triple(screen.load, screen.packs, screen.lastPack)
            is CageScreen.Preview -> Triple(screen.load, screen.packs, null)
            else -> null
        }
        v.tvIdlePrompt.isVisible = screen is CageScreen.Idle
        listOf(v.cardBanner, v.rowTiles).forEach { it.isVisible = shown != null }
        val loading = screen as? CageScreen.Loading
        v.layoutScanPrompt.isVisible = loading != null && !s.sending
        if (loading != null && !s.sending) renderStepPrompt(loading.armExpiresAtMs)
        v.btnCompleteCage.isVisible =
            loading != null && loading.load.isOpen && loading.load.packCount >= 1 && !s.sending
        v.cardLastPack.isVisible = shown?.third != null
        if (shown != null) {
            val (load, packs, lastPack) = shown
            v.tvBannerCage.text = load.cageId
            val jobPack = lastPack ?: packs.firstOrNull()
            v.tvBannerJob.isVisible = jobPack != null
            if (jobPack != null) v.tvBannerJob.text = jobLine(jobPack)
            v.tvPackCount.text = load.packCount.toString()
            v.tvPackCountLabel.text = resources.getQuantityString(R.plurals.cage_tile_packs, load.packCount)
            v.tvTotalKg.text = kg(load.totalKg)
            if (lastPack != null) {
                v.tvLastPack.text = getString(
                    R.string.cage_last_pack, lastPack.bagId, kg(lastPack.weightKg), disposition(lastPack.disposition),
                )
            }
        }
        renderPacks(shown?.second.orEmpty(), shown?.first?.packCount ?: 0)

        val completed = (screen as? CageScreen.Completed)?.load
        v.cardCompleted.isVisible = completed != null
        v.tvNextCagePrompt.isVisible = completed != null
        if (completed != null) {
            val time = StatusText.completedTime(completed.completedAtUtc)
            v.tvCompletedTitle.text = if (time != null) {
                getString(R.string.cage_completed_title_time, completed.cageId, packCount(completed.packCount), kg(completed.totalKg), time)
            } else {
                getString(R.string.cage_completed_title, completed.cageId, packCount(completed.packCount), kg(completed.totalKg))
            }
            v.tvGrv.text = getString(R.string.cage_sap_receipt, StatusText.grv(completed.grvState))
            v.tvPrint.text = getString(R.string.cage_manifest_print, StatusText.print(completed.printState))
            // Problem states in warning amber (6.1:1 on light_green), not the card's success white.
            fun stateColor(state: String) = ContextCompat.getColor(
                this, if (StatusText.needsAttention(state)) R.color.warning else R.color.text_primary,
            )
            v.tvGrv.setTextColor(stateColor(completed.grvState))
            v.tvPrint.setTextColor(stateColor(completed.printState))
            v.btnRefresh.isEnabled = !s.sending
        }

        val refusal = s.refusal
        binding.layoutRefusal.isVisible = refusal != null
        if (refusal != null) {
            binding.tvRefusalHeadline.text = refusal.headline
            binding.tvRefusalDetail.text = refusal.detail
            binding.tvRefusalCode.text = getString(R.string.cage_refusal_code, refusal.code)
        }

        // The sheet is its own window and would sit above the refusal and station-offline overlays,
        // so it closes for either; the workflow stays on Preview and the sheet returns once clear.
        if (screen is CageScreen.Preview && refusal == null && s.stationOnline) {
            showPreviewSheet(screen, s.sending)
        } else {
            dismissPreviewSheet()
        }
        restartTicker()
    }

    /**
     * The one next physical action (spec §4.2). Armed: "Scan ONE Pack QR now" on amber with the
     * arm's countdown; not armed: "Scan the cage tag" before each Pack.
     */
    private fun renderStepPrompt(armExpiresAtMs: Long?) {
        val v = binding.cage
        if (armExpiresAtMs != null) {
            v.layoutScanPrompt.setBackgroundResource(R.drawable.bg_cage_armed)
            v.tvStepPrompt.text = getString(R.string.cage_step_scan_pack)
            v.tvStepDetail.text = armCountdown(armExpiresAtMs - System.currentTimeMillis())
            v.tvStepDetail.textSize = 28f
            v.tvStepDetail.setTypeface(null, Typeface.BOLD)
            v.tvStepDetail.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            v.layoutScanPrompt.setBackgroundResource(R.drawable.bg_cage_dashed)
            v.tvStepPrompt.text = getString(R.string.cage_step_scan_tag)
            v.tvStepDetail.text = getString(R.string.cage_step_scan_tag_detail)
            v.tvStepDetail.textSize = 15f
            v.tvStepDetail.setTypeface(null, Typeface.NORMAL)
            v.tvStepDetail.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun renderNotice(notice: Notice?) {
        val tv = binding.cage.tvNotice
        tv.isVisible = notice != null
        val previousText = renderedNoticeText
        renderedNoticeText = notice?.text
        if (notice == null) return
        tv.text = notice.text
        // The notice sits at the top of the scroll view: bring a new one into sight.
        if (notice.text != previousText) binding.cage.root.post { binding.cage.root.smoothScrollTo(0, 0) }
        val (bg, fg) = when (notice.tone) {
            Notice.Tone.INFO -> R.color.light_blue to R.color.text_primary
            Notice.Tone.SUCCESS -> R.color.light_green to R.color.success
            Notice.Tone.WARNING -> R.color.light_amber to R.color.warning
        }
        // Rounded like the cards around it; a square full-width strip read as a stray band
        // (skill-run critique 2026-11-06, P3-12).
        tv.background = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(ContextCompat.getColor(this@MainActivity, bg))
        }
        tv.setTextColor(ContextCompat.getColor(this, fg))
    }

    /**
     * Packs in the order the workflow holds them (an allocation is prepended: newest first). A
     * cage-tag reply carries no packs, so a load with Packs may show an empty list until the
     * refresh after the next accepted Pack.
     */
    private fun renderPacks(packs: List<LoadedPack>, packCount: Int) {
        val v = binding.cage
        v.tvPacksHeader.isVisible = packs.isNotEmpty() || packCount > 0
        v.tvPacksHeader.setText(if (packs.isNotEmpty()) R.string.cage_packs_header else R.string.cage_packs_pending)
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
        b.tvSheetSummary.text = getString(R.string.cage_packs_kg, packCount(preview.load.packCount), kg(preview.load.totalKg))
        val jobPack = preview.packs.firstOrNull()
        b.tvSheetJob.isVisible = jobPack != null
        if (jobPack != null) b.tvSheetJob.text = jobLine(jobPack)
        b.tvSheetCountdown.isVisible = true
        b.tvSheetCountdown.text = summaryCountdown(preview.expiresAtMs - System.currentTimeMillis())
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

    private fun jobLine(pack: LoadedPack): String =
        getString(R.string.cage_job_line, pack.jobCard ?: "—", pack.outerCode)

    /** "1 Pack" / "2 Packs". */
    private fun packCount(count: Int): String = resources.getQuantityString(R.plurals.cage_pack_count, count, count)

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

    private companion object {
        const val TICK_MS = 1000L
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::binding.isInitialized) return // finished in onCreate for want of a session
        MqttManager.getInstance(this).removeConnectionStatusListener(connectionStatusListener)
        MqttManager.getInstance(this).removeStationStatusListener(stationStatusListener)
    }
}
