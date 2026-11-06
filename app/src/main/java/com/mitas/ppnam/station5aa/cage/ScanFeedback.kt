package com.mitas.ppnam.station5aa.cage

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.mitas.ppnam.station5aa.R

/**
 * Suite rule S2 (UI_Design/sounds/README.md): the audible and haptic answer to a scan.
 *
 * One lazily created [SoundPool] on the media stream (USAGE_MEDIA / CONTENT_TYPE_SONIFICATION,
 * so it follows the media volume) holds both suite sounds, loaded once. Each sound is paired
 * with a vibration. When audio is unavailable it logs and stays silent — the vibration and the
 * on-screen status still carry the outcome. *When* to sound is decided by [CageWorkflow]
 * ([Effect.SuccessFeedback] / [Effect.ErrorFeedback]); [CageController] plays it.
 */
object ScanFeedback {

    private const val TAG = "ScanFeedback"

    private var pool: SoundPool? = null
    private var successId = 0
    private var failId = 0
    private val loaded = HashSet<Int>()
    private val pendingPlay = HashSet<Int>()
    private var audioUnavailable = false

    /** Loads the sounds ahead of the first scan so it is not delayed by decoding. */
    fun warmUp(context: Context) {
        synchronized(this) { ensurePool(context.applicationContext) }
    }

    fun success(context: Context) {
        vibrate(context) { VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE) }
        playSound(context) { successId }
    }

    fun fail(context: Context) {
        vibrate(context) { VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1) }
        playSound(context) { failId }
    }

    private fun playSound(context: Context, id: () -> Int) {
        synchronized(this) {
            val soundPool = ensurePool(context.applicationContext) ?: return
            val soundId = id()
            if (soundId == 0) return
            if (soundId in loaded) {
                soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
            } else {
                // Still decoding (first scan right after start): play as soon as it is ready.
                pendingPlay += soundId
            }
        }
    }

    private fun ensurePool(appContext: Context): SoundPool? {
        pool?.let { return it }
        if (audioUnavailable) return null
        return try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build()
                .also { soundPool ->
                    soundPool.setOnLoadCompleteListener { sp, sampleId, status ->
                        synchronized(this) {
                            if (status != 0) {
                                Log.w(TAG, "Scan sound $sampleId failed to load ($status)")
                                pendingPlay -= sampleId
                                return@synchronized
                            }
                            loaded += sampleId
                            if (pendingPlay.remove(sampleId)) sp.play(sampleId, 1f, 1f, 1, 0, 1f)
                        }
                    }
                    successId = soundPool.load(appContext, R.raw.scan_success, 1)
                    failId = soundPool.load(appContext, R.raw.scan_fail, 1)
                    pool = soundPool
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Scan sounds unavailable", e)
            audioUnavailable = true
            null
        }
    }

    private fun vibrate(context: Context, effect: () -> VibrationEffect) {
        try {
            vibrator(context)?.takeIf { it.hasVibrator() }?.vibrate(effect())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vibration unavailable", e)
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
