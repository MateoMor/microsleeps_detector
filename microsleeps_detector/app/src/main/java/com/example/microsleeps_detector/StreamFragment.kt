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
import android.util.Log
import java.util.concurrent.TimeUnit

private val TAG = "StreamFragment"

class StreamFragment : Fragment(), FaceLandmarkerHelper.LandmarkerListener {

    private var _binding: FragmentStreamBinding? = null
    private val binding get() = _binding!!

    private val streamUrl = "http://192.168.43.74/stream"
    //private val streamUrl = "http://10.253.50.3/stream"
    //private val streamUrl = "http://192.168.4.1/stream"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
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
        Log.e(TAG, "Starting StreamFragment")
        startStream()
    }

    private fun startStream() {
        Log.e(TAG, "Starting stream")
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.e(TAG, "Trying to connect to stream: $streamUrl")
                val request = Request.Builder().url(streamUrl).build()
                val response = client.newCall(request).execute()

                Log.d(TAG, "Response code=${response.code}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "Response not successful: ${response.code}")
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Error: ${response.code}")
                    }
                    return@launch
                }

                val inputStream = response.body?.byteStream()
                if (inputStream != null) {
                    Log.d(TAG, "Connected, starting MJPEG parser")
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Connected to stream")
                    }
                    parseMjpegStream(inputStream)
                } else {
                    Log.e(TAG, "Response body is null")
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Empty response body")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in startStream: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    renderer?.setStatus("Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun parseMjpegStream(inputStream: java.io.InputStream) {
        try {
            val buffer = ByteArray(1024 * 64)
            var bytesRead: Int
            val frameBuffer = mutableListOf<Byte>()
            var contentLength = 0
            var isReadingHeader = true
            var isReadingJpeg = false

            while (streamJob?.isActive == true) {
                bytesRead = withContext(Dispatchers.IO) {
                    inputStream.read(buffer)
                }

                if (bytesRead <= 0) {
                    Log.d(TAG, "Stream ended")
                    break
                }

                for (i in 0 until bytesRead) {
                    val byte = buffer[i]
                    frameBuffer.add(byte)

                    if (isReadingHeader) {
                        // Convert buffer to string to search for Content-Length
                        if (frameBuffer.size > 50) {
                            val frameStr = try {
                                String(frameBuffer.toByteArray(), Charsets.UTF_8)
                            } catch (e: Exception) {
                                ""
                            }

                            val contentLengthMatch = Regex("Content-Length: (\\d+)").find(frameStr)
                            if (contentLengthMatch != null) {
                                contentLength = contentLengthMatch.groupValues[1].toInt()
                                Log.d(TAG, "Found Content-Length: $contentLength")
                            }
                        }

                        // Detect end of headers (double CRLF: \r\n\r\n = 0x0D 0x0A 0x0D 0x0A)
                        if (frameBuffer.size >= 4 &&
                            frameBuffer[frameBuffer.size - 4].toInt() and 0xFF == 0x0D &&
                            frameBuffer[frameBuffer.size - 3].toInt() and 0xFF == 0x0A &&
                            frameBuffer[frameBuffer.size - 2].toInt() and 0xFF == 0x0D &&
                            frameBuffer[frameBuffer.size - 1].toInt() and 0xFF == 0x0A
                        ) {
                            isReadingHeader = false
                            isReadingJpeg = true
                            frameBuffer.clear()
                            Log.d(TAG, "Headers done, expecting $contentLength bytes of JPEG")
                        }
                    } else if (isReadingJpeg && contentLength > 0) {
                        // We have enough bytes for a complete JPEG
                        if (frameBuffer.size >= contentLength) {
                            Log.d(TAG, "Got complete JPEG frame ($contentLength bytes)")
                            val jpegData = frameBuffer.take(contentLength).toByteArray()
                            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)

                            if (bitmap != null) {
                                Log.d(TAG, "Frame decoded (${bitmap.width}x${bitmap.height})")
                                withContext(Dispatchers.Main) {
                                    if (isAdded) {
                                        binding.streamImageView.setImageBitmap(bitmap)
                                    }
                                }
                            } else {
                                Log.w(TAG, "Failed to decode bitmap")
                            }

                            frameBuffer.clear()
                            isReadingHeader = true
                            isReadingJpeg = false
                            contentLength = 0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream parser error: ${e.message}", e)
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