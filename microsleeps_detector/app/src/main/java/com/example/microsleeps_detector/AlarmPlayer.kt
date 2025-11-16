package com.example.microsleeps_detector

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Manages alarm sound playback for drowsiness detection.
 * Plays a looping alarm sound when eyes are closed.
 */
class AlarmPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var isPlaying = false

    companion object {
        private const val TAG = "AlarmPlayer"
        // Patrón: espera, vibra, pausa, vibra (en milisegundos)
        private val VIBRATION_PATTERN = longArrayOf(0, 500, 200, 500)
        // Amplitudes: 0=sin vibrar, 255=intensidad máxima
        private val VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255)
    }

    /**
     * Initializes the MediaPlayer with the alarm sound.
     */
    fun initialize() {
        try {
            // Usar archivo de sonido personalizado desde res/raw/alarm_sound.wav
            mediaPlayer = MediaPlayer.create(context, R.raw.alarm_sound)
            mediaPlayer?.isLooping = true // Loop alarm continuously

            // Inicializar vibrador
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            Log.d(TAG, "Alarm initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize alarm: ${e.message}", e)
        }
    }

    /**
     * Starts playing the alarm if not already playing.
     */
    fun play() {
        if (mediaPlayer == null) {
            Log.w(TAG, "MediaPlayer not initialized")
            return
        }

        if (!isPlaying) {
            try {
                // Iniciar sonido
                mediaPlayer?.start()

                // Iniciar vibración con intensidad controlada
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0+: Usar amplitudes para controlar intensidad
                    val vibrationEffect = VibrationEffect.createWaveform(
                        VIBRATION_PATTERN,
                        VIBRATION_AMPLITUDES,
                        0 // Repetir desde el índice 0
                    )
                    vibrator?.vibrate(vibrationEffect)
                } else {
                    // Android 7.1 o menor: Solo patrón temporal (sin control de intensidad)
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(VIBRATION_PATTERN, 0)
                }

                isPlaying = true
                Log.d(TAG, "Alarm and vibration started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start alarm: ${e.message}", e)
            }
        }
    }

    /**
     * Stops the alarm and resets playback position.
     */
    fun stop() {
        if (isPlaying) {
            try {
                // Detener sonido
                mediaPlayer?.pause()
                mediaPlayer?.seekTo(0)

                // Detener vibración
                vibrator?.cancel()

                isPlaying = false
                Log.d(TAG, "Alarm and vibration stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop alarm: ${e.message}", e)
            }
        }
    }

    /**
     * Releases MediaPlayer resources.
     * Call this in onDestroy or onDestroyView.
     */
    fun release() {
        stop()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator = null
            Log.d(TAG, "Alarm resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release alarm: ${e.message}", e)
        }
    }

    /**
     * Checks if alarm is currently playing.
     */
    fun isPlaying(): Boolean = isPlaying
}