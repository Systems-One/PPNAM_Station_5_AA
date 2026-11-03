package com.mitas.ppnam.station5aa.cage

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.mitas.ppnam.station5aa.DeviceIdentity
import com.mitas.ppnam.station5aa.MqttManager
import com.mitas.ppnam.station5aa.OperatorSession
import com.mitas.ppnam.station5aa.OperatorSessionHolder
import com.mitas.ppnam.station5aa.ScannerApp
import com.mitas.ppnam.station5aa.SessionGuard
import com.mitas.ppnam.station5aa.StationTab
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide host of the [CageWorkflow]: feeds it the operator session, station status, scans
 * and responses, and carries out its effects. Every workflow call and effect runs on the main
 * thread; MQTT callbacks are posted there first.
 */
object CageController {

    private const val TAG = "CageController"
    private const val RETRY_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(CageUiState) -> Unit>()

    private lateinit var app: Application
    private lateinit var mqtt: MqttManager
    private var workflow: CageWorkflow? = null

    /** The session the workflow was started with, or null while stopped. */
    private var startedSessionId: String? = null
    private var retryScheduled = false

    private val retryTick = object : Runnable {
        override fun run() {
            retryScheduled = false
            if (workflow?.pending() == null) return
            retryNow()
        }
    }

    /** From ScannerApp.onCreate, after SessionGuard.install. */
    fun install(app: Application) {
        if (workflow != null) return
        this.app = app
        mqtt = MqttManager.getInstance(app)
        val wf = CageWorkflow(
            CageRequests(DeviceIdentity.deviceId(app), Instant::now) { UUID.randomUUID().toString() },
            SharedPrefsOutboxStore(app),
            System::currentTimeMillis,
        )
        workflow = wf

        CageTransport(mqtt, DeviceIdentity.deviceId(app), main, { wf.pending() }) { json ->
            dispatch { wf.onResponse(json) }
        }.start()

        onMain { dispatch { wf.onStationOnline(mqtt.isStationOnline) } }
        mqtt.addStationStatusListener { online -> onMain { dispatch { wf.onStationOnline(online) } } }
        mqtt.addConnectionListener { connected -> if (connected) onMain { retryNow() } }
        OperatorSessionHolder.addListener { session -> onMain { onSession(session) } }
    }

    val state: CageUiState get() = workflow?.state ?: CageUiState()

    fun addListener(l: (CageUiState) -> Unit) { listeners.add(l) }
    fun removeListener(l: (CageUiState) -> Unit) { listeners.remove(l) }

    fun onRfid(tag: String) {
        if (tag == ScannerApp.SETTINGS_RFID) return // the Settings shortcut never selects a cage
        call { it.onRfid(tag) }
    }

    fun onQr(data: String) = call { it.onQr(data) }
    fun dismissRefusal() = call { it.onDismissRefusal() }
    fun keepLoading() = call { it.onKeepLoading() }
    fun confirmComplete() = call { it.onConfirmComplete() }
    fun previewExpired() = call { it.onPreviewExpired() }
    fun refreshCompleted() = call { it.onRefreshCompleted() }
    fun completeCage() = call { it.onCompleteCage() }
    fun armExpired() = call { it.onArmExpired() }

    fun hasPending(): Boolean = workflow?.pending() != null

    // ---- internals ------------------------------------------------------------------------

    private fun onSession(session: OperatorSession?) {
        val wf = workflow ?: return
        // Fail closed: an operator without the cage_loading tab gets no workflow at all.
        val id = session?.takeIf { it.canShow(StationTab.CAGE_LOADING) }?.operatorSessionId
        if (id == startedSessionId) return
        startedSessionId = id
        if (id == null) {
            wf.stop()
            publishState()
            return
        }
        dispatch { wf.onStationOnline(mqtt.isStationOnline) }
        dispatch { wf.start(id) }
    }

    private fun retryNow() {
        val wf = workflow ?: return
        dispatch { wf.onRetryTick(OperatorSessionHolder.session?.operatorSessionId) }
    }

    private fun call(block: (CageWorkflow) -> List<Effect>) {
        val wf = workflow ?: return
        onMain { dispatch { block(wf) } }
    }

    /** Runs a workflow call, then its effects, then tells the screen and (re)arms the retry tick. */
    private fun dispatch(block: () -> List<Effect>) {
        val effects = block()
        effects.forEach(::execute)
        publishState()
    }

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.Publish -> {
                val r = effect.request
                mqtt.publish(r.topic, r.body, retain = false, qos = MqttQos.AT_LEAST_ONCE) { error ->
                    // Nothing to undo: the request stays in the outbox and the next tick resends it.
                    if (error != null) Log.w(TAG, "Publish failed for ${r.requestType} ${r.messageId}; will retry", error)
                }
            }
            is Effect.SignOut -> SessionGuard.signOut(effect.reason)
            Effect.SuccessFeedback -> ScanFeedback.success(app)
            Effect.ErrorFeedback -> ScanFeedback.error(app)
        }
    }

    private fun publishState() {
        if (workflow?.pending() != null && !retryScheduled) {
            retryScheduled = true
            main.postDelayed(retryTick, RETRY_MS)
        }
        val s = state
        listeners.forEach { it(s) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
