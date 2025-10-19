package com.example.microsleeps_detector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.microsleeps_detector.databinding.FragmentCameraBinding
import com.example.microsleeps_detector.ui.LabelsRenderer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var cameraExecutor: ExecutorService? = null
    private var isFrontCamera = true
    private var renderer: LabelsRenderer? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(requireContext(), "Permiso de cámara denegado", Toast.LENGTH_LONG).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        renderer = LabelsRenderer(binding)
        ensurePermissionAndStart()
    }

    private fun ensurePermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> startCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                val helper = (requireActivity() as MainActivity).faceHelperOrNull()
                if (helper == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                helper.detectLiveStream(imageProxy, isFrontCamera)
            }

            val selector = CameraSelector.Builder()
                .requireLensFacing(if (isFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "No se pudo iniciar la cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setLandmarkerListener(this)
    }

    override fun onPause() {
        super.onPause()
        (requireActivity() as MainActivity).setLandmarkerListener(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderer = null
        _binding = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
    }

    // FaceLandmarkerHelper.LandmarkerListener

    override fun onAnalysis(result: FaceAnalysis.Result) {
        // Forward analysis to the labels renderer (handles main thread internally)
        renderer?.render(result)
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            binding.overlay.setResults(resultBundle)
        }
    }

    override fun onEmpty() {
        if (!isAdded) return
        renderer?.setStatus("No face detected")
        requireActivity().runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        if (!isAdded) return
        renderer?.setStatus("Error: $error")
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}