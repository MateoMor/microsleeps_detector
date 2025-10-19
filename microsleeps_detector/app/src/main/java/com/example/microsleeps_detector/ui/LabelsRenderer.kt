package com.example.microsleeps_detector.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.example.microsleeps_detector.FaceAnalysis
import com.example.microsleeps_detector.databinding.FragmentCameraBinding

/**
 * Pequeño renderizador de etiquetas responsable de actualizar los TextView del
 * layout de la cámara con los resultados de [FaceAnalysis]. Garantiza que las
 * actualizaciones se ejecuten en el hilo principal.
 *
 * Uso: crear con el binding del fragmento y llamar a [render] en cada
 * [FaceAnalysis.Result], o [setStatus] para mostrar un estado puntual.
 */
class LabelsRenderer(
    private val binding: FragmentCameraBinding
) {
    private val main = Handler(Looper.getMainLooper())
    private val defaultBottomTextColor = binding.labelBottom.currentTextColor

    /**
     * Pinta en los labels el EAR promedio, número total de cabeceos y un estado
     * textual basado en ojos abiertos/cerrados y si hubo cabeceo en este frame.
     * @param result salida de [FaceAnalysis.update]
     */
    fun render(result: FaceAnalysis.Result) = onMain {
        binding.labelPrimary.text = "EAR: %.2f".format(result.earAverage)
        binding.labelSecondary.text = "Nods: ${result.totalNods}"
        // Build a simple status from analysis fields
        val status = buildString {
            append(if (result.eyesClosed) "Ojos cerrados" else "Ojos abiertos")
            if (result.isNodEvent) {
                append(" · Cabeceo #${result.totalNods}")
            }
        }
        binding.labelBottom.text = status
        // Cambiar color del estado: rojo si ojos cerrados, color por defecto si abiertos
        binding.labelBottom.setTextColor(
            if (result.eyesClosed) Color.RED else defaultBottomTextColor
        )
    }

    /**
     * Actualiza únicamente el label inferior con un texto de estado arbitrario.
     * @param text texto a mostrar
     */
    fun setStatus(text: String) = onMain {
        binding.labelBottom.text = text
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}