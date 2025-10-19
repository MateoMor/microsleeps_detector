package com.example.microsleeps_detector.ui

import android.os.Handler
import android.os.Looper
import com.example.microsleeps_detector.FaceAnalysis
import com.example.microsleeps_detector.databinding.FragmentCameraBinding

class LabelsRenderer(
    private val binding: FragmentCameraBinding
) {
    private val main = Handler(Looper.getMainLooper())

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
    }

    fun setStatus(text: String) = onMain {
        binding.labelBottom.text = text
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}