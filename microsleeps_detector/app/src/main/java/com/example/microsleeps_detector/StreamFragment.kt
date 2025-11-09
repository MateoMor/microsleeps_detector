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
import java.io.InputStream
import android.util.Log
import kotlin.code


private val TAG = "StreamFragment"

class StreamFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentStreamBinding? = null
    private val binding get() = _binding!!

    private val streamUrl = "http://192.168.43.74/stream" // Reemplaza con tu URL
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
        Log.e(TAG, "Hello")
        startStream()
    }

    private fun startStream() {
        Log.e(TAG, "Starting stream")
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.e(TAG, "Trying to connect to stream")

                val request = Request.Builder().url(streamUrl).build()

                Log.e(TAG, "Request built with request: $request")

                val response = client.newCall(request).execute()

                Log.d(TAG, "Response=${response}, message=${response.message}")


                if (!response.isSuccessful) {
                    Log.e(TAG, "Response not successful")

                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Error: ${response.code}")
                    }
                    return@launch
                }

                val inputStream = response.body?.byteStream()
                if (inputStream != null) {
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Connected to stream")
                    }
                    parseMjpegStream(inputStream, this)
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    renderer?.setStatus("Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun parseMjpegStream(inputStream: InputStream, scope: CoroutineScope) {
        try {
            val buffer = ByteArray(1024 * 64)
            var bytesRead: Int
            val frameBuffer = mutableListOf<Byte>()
            val boundary = "--frame"
            var contentLength = 0
            var currentHeaderLength = 0
            var isReadingHeader = true
            var isReadingJpeg = false

            while (true) {
                scope.ensureActive()
                bytesRead = withContext(Dispatchers.IO) {
                    inputStream.read(buffer)
                }
                if (bytesRead <= 0) break

                for (i in 0 until bytesRead) {
                    val byte = buffer[i]
                    frameBuffer.add(byte)

                    // Si estamos leyendo headers, busca Content-Length
                    if (isReadingHeader) {
                        currentHeaderLength++
                        val frameStr = String(frameBuffer.toByteArray(), Charsets.UTF_8)

                        // Busca el patrón Content-Length: [número]
                        val contentLengthMatch = Regex("Content-Length: (\\d+)").find(frameStr)
                        if (contentLengthMatch != null) {
                            contentLength = contentLengthMatch.groupValues[1].toInt()
                        }

                        // Detecta fin de headers (doble CRLF: \r\n\r\n)
                        if (frameBuffer.size >= 4 &&
                            frameBuffer[frameBuffer.size - 4].toInt() and 0xFF == 0x0D &&
                            frameBuffer[frameBuffer.size - 3].toInt() and 0xFF == 0x0A &&
                            frameBuffer[frameBuffer.size - 2].toInt() and 0xFF == 0x0D &&
                            frameBuffer[frameBuffer.size - 1].toInt() and 0xFF == 0x0A
                        ) {
                            isReadingHeader = false
                            isReadingJpeg = true
                            frameBuffer.clear()
                        }
                    } else if (isReadingJpeg) {
                        // Leyendo el JPEG
                        if (frameBuffer.size >= contentLength) {
                            // Ya tenemos el JPEG completo
                            val jpegData = frameBuffer.take(contentLength).toByteArray()
                            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)

                            if (bitmap != null) {
                                withContext(Dispatchers.Main) {
                                    if (isAdded) {
                                        binding.streamImageView.setImageBitmap(bitmap)
                                    }
                                }
                            }

                            // Limpiar buffer y prepararse para el siguiente frame
                            frameBuffer.clear()
                            isReadingHeader = true
                            isReadingJpeg = false
                            contentLength = 0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    renderer?.setStatus("Stream error: ${e.message}")
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