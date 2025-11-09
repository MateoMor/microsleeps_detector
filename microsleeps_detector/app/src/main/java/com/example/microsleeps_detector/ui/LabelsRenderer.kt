package com.example.microsleeps_detector.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.example.microsleeps_detector.FaceAnalysis
import com.example.microsleeps_detector.databinding.FragmentCameraBinding
import com.example.microsleeps_detector.databinding.FragmentStreamBinding

/**
 * Interfaz común para bindings que tienen labels
 */
interface LabelsBinding {
    val labelPrimary: TextView
    val labelSecondary: TextView
    val labelBottom: TextView
}

/**
 * Extensiones para hacer que los bindings implementen la interfaz
 */
fun FragmentCameraBinding.asLabelsBinding() = object : LabelsBinding {
    override val labelPrimary = this@asLabelsBinding.labelPrimary
    override val labelSecondary = this@asLabelsBinding.labelSecondary
    override val labelBottom = this@asLabelsBinding.labelBottom
}

fun FragmentStreamBinding.asLabelsBinding() = object : LabelsBinding {
    override val labelPrimary = this@asLabelsBinding.labelPrimary
    override val labelSecondary = this@asLabelsBinding.labelSecondary
    override val labelBottom = this@asLabelsBinding.labelBottom
}

/**
 * Renderiza los labels del layout con los resultados de [FaceAnalysis].
 * Garantiza que las actualizaciones se hagan en el main thread.
 */
class LabelsRenderer private constructor(private val binding: LabelsBinding) {

    constructor(cameraBinding: FragmentCameraBinding) : this(cameraBinding.asLabelsBinding())
    constructor(streamBinding: FragmentStreamBinding) : this(streamBinding.asLabelsBinding())

    private val main = Handler(Looper.getMainLooper())
    private val defaultBottomTextColor = binding.labelBottom.currentTextColor

    /**
     * Actualiza los labels con el resultado del análisis facial
     */
    fun render(result: FaceAnalysis.Result) {
        onMain {
            // Label primario: EAR
            binding.labelPrimary.text = String.format("EAR: %.2f", result.earAverage)

            // Label secundario: contador de cabeceos
            binding.labelSecondary.text = buildString {
                append("Nods: ${result.totalNods}")
                if (result.isNodEvent) {
                    append(" · Cabeceo #${result.totalNods}")
                }
            }

            // Label inferior: estado de los ojos
            binding.labelBottom.text = if (result.eyesClosed) "Eyes Closed" else "Eyes Open"

            // Cambiar color del estado: rojo si ojos cerrados, color por defecto si abiertos
            binding.labelBottom.setTextColor(
                if (result.eyesClosed) Color.RED else defaultBottomTextColor
            )
        }
    }

    /**
     * Actualiza únicamente el label inferior con un texto de estado arbitrario.
     */
    fun setStatus(status: String) {
        onMain {
            binding.labelBottom.text = status
            binding.labelBottom.setTextColor(defaultBottomTextColor)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}