package com.example.microsleeps_detector

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.microsleeps_detector.databinding.FragmentStreamBinding
import com.example.microsleeps_detector.ui.OverlayView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import java.util.concurrent.TimeUnit

private val TAG = "StreamFragment"

class StreamFragment : BaseFaceDetectionFragment<FragmentStreamBinding>() {

    override val binding get() = _binding!!

    // For visualization adjustments
    private val imageRotation = 90f

    private val streamUrl = "http://192.168.43.74/stream"
    //private val streamUrl = "http://10.253.50.3/stream"
    //private val streamUrl = "http://192.168.4.1/stream"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var streamJob: Job? = null
    private var isConnected = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreatedImpl(view: View, savedInstanceState: Bundle?) {
        // Mostrar estado inicial explícito
        renderer?.setStatus("No conectado")
        Log.e(TAG, "Starting StreamFragment")
        startStream()
    }

    override fun getOverlayView(): OverlayView? = binding.overlay

    override fun onPauseImpl() {
        streamJob?.cancel()
        isConnected = false
        renderer?.setStatus("No conectado")
    }

    override fun onDestroyViewImpl() {
        streamJob?.cancel()
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
                    isConnected = false
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("No conectado")
                    }
                    return@launch
                }

                val inputStream = response.body?.byteStream()
                if (inputStream != null) {
                    // Conectado
                    isConnected = true
                    Log.d(TAG, "Connected, starting MJPEG parser")
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("Conectado")
                    }
                    parseMjpegStream(inputStream)
                } else {
                    // No conectado (sin datos)
                    isConnected = false
                    Log.e(TAG, "Response body is null")
                    withContext(Dispatchers.Main) {
                        renderer?.setStatus("No conectado")
                    }
                }
            } catch (e: Exception) {
                // Error durante conexión inicial
                isConnected = false
                Log.e(TAG, "Exception in startStream: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    renderer?.setStatus("No conectado")
                }
            }
        }
    }

    private suspend fun processFrameForFaceDetection(bitmap: android.graphics.Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val helper = (requireActivity() as MainActivity).faceHelperOrNull()
                if (helper == null) {
                    Log.w(TAG, "FaceLandmarkerHelper not available")
                    return@withContext
                }

                // Convertir Bitmap a MPImage (sin rotación)
                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()

                // Detectar con timestamp actual
                val timestamp = android.os.SystemClock.uptimeMillis()
                helper.detectAsync(mpImage, timestamp)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame for face detection: ${e.message}", e)
            }
        }
    }

    /**
     * Rota un Bitmap por los grados especificados solo para visualización.
     */
    private fun rotateBitmap(source: android.graphics.Bitmap, degrees: Float): android.graphics.Bitmap {
        if (degrees == 0f) return source
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return android.graphics.Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
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
                bytesRead = withContext(Dispatchers.IO) { inputStream.read(buffer) }
                if (bytesRead <= 0) {
                    Log.d(TAG, "Stream ended")
                    // Se desconectó
                    isConnected = false
                    withContext(Dispatchers.Main) { renderer?.setStatus("Se desconectó") }
                    break
                }

                for (i in 0 until bytesRead) {
                    val byte = buffer[i]
                    frameBuffer.add(byte)

                    if (isReadingHeader) {
                        if (frameBuffer.size > 50) {
                            val frameStr = try {
                                String(frameBuffer.toByteArray(), Charsets.UTF_8)
                            } catch (_: Exception) { "" }

                            val contentLengthMatch = Regex("Content-Length: (\\d+)").find(frameStr)
                            if (contentLengthMatch != null) {
                                contentLength = contentLengthMatch.groupValues[1].toInt()
                            }
                        }

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
                    } else if (isReadingJpeg && contentLength > 0) {
                        if (frameBuffer.size >= contentLength) {
                            val jpegData = frameBuffer.take(contentLength).toByteArray()
                            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)

                            if (bitmap != null) {
                                // 1) Detectar con bitmap original
                                processFrameForFaceDetection(bitmap)
                                // 2) Rotar SOLO para mostrar
                                val displayBitmap = rotateBitmap(bitmap, imageRotation)
                                withContext(Dispatchers.Main) {
                                    if (isAdded) {
                                        binding.streamImageView.setImageBitmap(displayBitmap)
                                    }
                                }
                                // No reciclar el bitmap asignado al ImageView
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
            // Se desconectó por error
            isConnected = false
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    renderer?.setStatus("Se desconectó")
                }
            }
        }
    }

    // Override to avoid 'No face detected' when not connected
    override fun onEmpty() {
        if (!isAdded) return
        if (!isConnected) {
            renderer?.setStatus("No conectado")
            requireActivity().runOnUiThread { binding.overlay.clear() }
            return
        }
        // Connected but no face detected
        renderer?.setStatus("No face detected")
        requireActivity().runOnUiThread { binding.overlay.clear() }
    }
}