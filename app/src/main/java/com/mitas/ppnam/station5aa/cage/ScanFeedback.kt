package com.mitas.ppnam.station5aa.cage

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Haptic / audible confirmation of a scan's outcome. */
object ScanFeedback {

    private const val TAG = "ScanFeedback"
    private val main = Handler(Looper.getMainLooper())

    fun success(ctx: Context) {
        vibrator(ctx)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun error(ctx: Context) {
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            main.postDelayed({ tone.release() }, 500)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Error tone unavailable", e) // the vibration still signals the refusal
        }
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
