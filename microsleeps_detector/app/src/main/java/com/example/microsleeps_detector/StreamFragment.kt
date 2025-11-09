package com.example.microsleeps_detector

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.microsleeps_detector.databinding.FragmentStreamBinding
import com.example.microsleeps_detector.ui.LabelsRenderer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class StreamFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentStreamBinding? = null
    private val binding get() = _binding!!

    private val streamUrl = "http://192.168.4.1/stream" // Reemplaza con tu URL
    private val client = OkHttpClient()
    private var streamJob: Job? = null
    private var renderer: LabelsRenderer? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderer = LabelsRenderer(binding)
        startStream()
    }

    private fun startStream() {
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(streamUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Error: ${response.code}")
                    }
                    return@launch
                }

                val inputStream = response.body?.byteStream()
                val buffer = ByteArray(1024 * 8)

                withContext(Dispatchers.Main) {
                    renderer?.setStatus("Connected to stream")
                }

                while (isActive && inputStream != null) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val bitmap = BitmapFactory.decodeByteArray(buffer, 0, bytesRead)
                        bitmap?.let {
                            withContext(Dispatchers.Main) {
                                binding.streamImageView.setImageBitmap(it)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    renderer?.setStatus("Error: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setLandmarkerListener(this)
    }

    override fun onPause() {
        super.onPause()
        (requireActivity() as MainActivity).setLandmarkerListener(null)
        streamJob?.cancel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderer = null
        _binding = null
        streamJob?.cancel()
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