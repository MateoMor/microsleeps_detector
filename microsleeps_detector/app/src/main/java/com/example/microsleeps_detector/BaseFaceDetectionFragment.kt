package com.example.microsleeps_detector

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.example.microsleeps_detector.databinding.FragmentCameraBinding
import com.example.microsleeps_detector.databinding.FragmentStreamBinding
import com.example.microsleeps_detector.ui.LabelsRenderer
import com.example.microsleeps_detector.ui.OverlayView

/**
 * Base fragment for face detection functionality.
 * Provides common implementation for alarm management, rendering, and lifecycle callbacks.
 */
abstract class BaseFaceDetectionFragment<B : ViewBinding> : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    protected var _binding: B? = null
    protected abstract val binding: B

    protected var alarmPlayer: AlarmPlayer? = null
    protected var renderer: LabelsRenderer? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize alarm
        alarmPlayer = AlarmPlayer(requireContext())
        alarmPlayer?.initialize()

        // Initialize renderer based on binding type
        renderer = when (val b = binding) {
            is FragmentCameraBinding -> LabelsRenderer(b)
            is FragmentStreamBinding -> LabelsRenderer(b)
            else -> null
        }

        // Let subclasses do their specific initialization
        onViewCreatedImpl(view, savedInstanceState)
    }

    /**
     * Subclasses should implement their specific initialization here.
     */
    protected abstract fun onViewCreatedImpl(view: View, savedInstanceState: Bundle?)

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setLandmarkerListener(this)
    }

    override fun onPause() {
        super.onPause()
        (requireActivity() as MainActivity).setLandmarkerListener(null)
        onPauseImpl()
    }

    /**
     * Subclasses can add additional pause logic.
     */
    protected open fun onPauseImpl() {}

    override fun onDestroyView() {
        super.onDestroyView()

        // Release alarm
        alarmPlayer?.release()
        alarmPlayer = null

        // Release renderer
        renderer = null

        // Clear binding
        _binding = null

        // Let subclasses clean up
        onDestroyViewImpl()
    }

    /**
     * Subclasses should clean up their specific resources.
     */
    protected open fun onDestroyViewImpl() {}

    // ========== FaceLandmarkerHelper.LandmarkerListener Implementation ==========

    override fun onAnalysis(result: FaceAnalysis.Result) {
        renderer?.render(result)

        // Activar/desactivar alarma según ojos cerrados
        if (result.eyesClosed) {
            alarmPlayer?.play()
        } else {
            alarmPlayer?.stop()
        }
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            getOverlayView()?.setResults(resultBundle)
        }
    }

    override fun onEmpty() {
        if (!isAdded) return
        renderer?.setStatus("No face detected")
        requireActivity().runOnUiThread {
            getOverlayView()?.clear()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        if (!isAdded) return
        renderer?.setStatus("Error: $error")
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Subclasses must provide access to their overlay view.
     */
    protected abstract fun getOverlayView(): OverlayView?
}
