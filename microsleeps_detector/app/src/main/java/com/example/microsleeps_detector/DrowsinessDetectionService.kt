package com.example.microsleeps_detector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import android.graphics.Bitmap

/**
 * Servicio en segundo plano que mantiene la conexión con ESP32-CAM
 * y detecta microsueños incluso cuando la app está en segundo plano.
 */
class DrowsinessDetectionService : Service(), FaceLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "DrowsinessService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "drowsiness_detection_channel"
        private const val CHANNEL_NAME = "Detección de Microsueños"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_STREAM_URL = "EXTRA_STREAM_URL"

        const val STATE_NOT_CONNECTED = "No conectado"
        const val STATE_CONNECTING = "Conectando..."
        const val STATE_RUNNING = "Detectando"
        const val STATE_NO_FACE = "Sin rostro detectado"
        const val STATE_EYES_CLOSED = "⚠️ OJOS CERRADOS"
        const val STATE_DISCONNECTED = "Se desconectó"
        const val STATE_DISCONNECTED_TIMEOUT = "Se desconectó (timeout)"
        const val STATE_ERROR = "Error"
        const val STATE_SENDING_CLOSE = "Cerrando conexión" // nuevo estado opcional

        // Timeout para detectar desconexión (3 segundos sin frames)
        private const val FRAME_TIMEOUT_MS = 3000L
        // Intervalo de chequeo del watchdog
        private const val WATCHDOG_INTERVAL_MS = 1000L
        // Reintentos de reconexión
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 5000L
        const val EXTRA_FRAME_TIMEOUT_MS = "EXTRA_FRAME_TIMEOUT_MS"

        private const val PREVIEW_MIN_INTERVAL_MS = 300L
        private const val PREVIEW_MAX_WIDTH = 480
        private const val PREVIEW_MAX_HEIGHT = 360
    }

    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var streamJob: Job? = null
    private var watchdogJob: Job? = null

    private var faceHelper: FaceLandmarkerHelper? = null
    private var alarmPlayer: AlarmPlayer? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentState = STATE_NOT_CONNECTED
    private var streamUrl: String = "http://192.168.43.74/stream"
    //private var streamUrl: String = "http://192.168.4.1/stream"

    // Track connection lifecycle
    private var wasEverConnected = false
    private var isConnected = false

    // Watchdog: timestamp del último frame recibido
    private val lastFrameTime = AtomicLong(0L)

    // Control de reconexión
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    // Parametrizar timeout de frames
    private var frameTimeoutMs: Long = FRAME_TIMEOUT_MS

    // Callbacks para actualizar UI
    private val stateListeners = mutableListOf<(String) -> Unit>()
    private val frameListeners = mutableListOf<(Bitmap) -> Unit>()
    private val lastPreviewTime = AtomicLong(0L)

    // Nueva variable para seguimiento de conexión HTTP actual
    private var currentCall: okhttp3.Call? = null
    private var currentResponseBody: okhttp3.ResponseBody? = null
    private var isStoppingStream = false

    // Nuevos listeners para resultados y eventos
    private val resultListeners = mutableListOf<(FaceLandmarkerHelper.ResultBundle) -> Unit>()
    private val analysisListeners = mutableListOf<(FaceAnalysis.Result) -> Unit>()
    private val emptyListeners = mutableListOf<() -> Unit>()
    private val errorListeners = mutableListOf<(String, Int) -> Unit>()

    inner class LocalBinder : Binder() {
        fun getService(): DrowsinessDetectionService = this@DrowsinessDetectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Crear canal de notificaciones
        createNotificationChannel()

        // Inicializar FaceLandmarkerHelper
        faceHelper = FaceLandmarkerHelper(
            runningMode = RunningMode.LIVE_STREAM,
            context = applicationContext,
            faceLandmarkerHelperListener = this
        )

        // Inicializar alarma
        alarmPlayer = AlarmPlayer(applicationContext)
        alarmPlayer?.initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: streamUrl
                frameTimeoutMs = intent.getLongExtra(EXTRA_FRAME_TIMEOUT_MS, FRAME_TIMEOUT_MS).coerceAtLeast(1000L)
                shouldReconnect = true
                reconnectAttempts = 0
                startForegroundService()
                startStreamDetection()
                startWatchdog()
            }
            ACTION_STOP -> {
                // Enviar señal de cierre antes de parar si había conexión
                if (wasEverConnected || isConnected) {
                    sendDisconnectSignal()
                }
                shouldReconnect = false
                stopStreamDetection()
                stopWatchdog()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification(STATE_CONNECTING)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitoreo continuo de microsueños"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(state: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DrowsinessDetectionService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Detección de Microsueños Activa")
            .setContentText(state)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Detener", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: String) {
        currentState = state
        val notification = createNotification(state)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Notificar a listeners
        stateListeners.forEach { it(state) }
    }

    /**
     * Watchdog que monitorea si llegan frames regularmente.
     * Si no llega ningún frame en FRAME_TIMEOUT_MS, marca como desconectado.
     */
    private fun startWatchdog() {
        stopWatchdog() // Detener watchdog previo si existe

        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)

                if (isConnected) {
                    val lastFrame = lastFrameTime.get()
                    val now = System.currentTimeMillis()
                    val timeSinceLastFrame = now - lastFrame
                    // Verificación de timeout deshabilitada por solicitud
                    // Antes:
                    // if (lastFrame != 0L && timeSinceLastFrame > frameTimeoutMs) {
                    //     Log.w(TAG, "Timeout sin frames (${timeSinceLastFrame}ms > ${frameTimeoutMs}ms)")
                    //     handleDisconnection(timeout = true)
                    // }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Maneja la desconexión: actualiza estado e intenta reconectar si está habilitado.
     */
    private fun handleDisconnection(timeout: Boolean = false) {
        if (!isConnected && currentState.startsWith("Se desconectó")) return // evitar repetir
        isConnected = false
        wasEverConnected = true
        streamJob?.cancel()
        val newState = if (timeout) STATE_DISCONNECTED_TIMEOUT else STATE_DISCONNECTED
        updateNotification(newState)
        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            Log.d(TAG, "Reintentando conexión $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS en ${RECONNECT_DELAY_MS}ms")
            serviceScope.launch {
                delay(RECONNECT_DELAY_MS)
                if (shouldReconnect && !isConnected) {
                    startStreamDetection()
                }
            }
        }
    }

    private fun startStreamDetection() {
        // Cancelar job anterior si existe
        streamJob?.cancel()

        if (!shouldReconnect) return

        updateNotification(STATE_CONNECTING)

        streamJob = serviceScope.launch {
            try {
                isStoppingStream = false
                Log.d(TAG, "Connecting to stream: $streamUrl")
                val request = Request.Builder()
                    .url(streamUrl)
                    .header("Connection", "close")
                    .build()
                val call = client.newCall(request)
                currentCall = call
                val response = call.execute()
                currentResponseBody = response.body

                if (!response.isSuccessful) {
                    Log.e(TAG, "Response not successful: ${response.code}")
                    updateNotification(STATE_NOT_CONNECTED)

                    // Reintentar si está habilitado
                    if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++
                        delay(RECONNECT_DELAY_MS)
                        if (shouldReconnect) startStreamDetection()
                    }
                    return@launch
                }

                val inputStream = currentResponseBody?.byteStream()
                if (inputStream != null && !isStoppingStream) {
                    Log.d(TAG, "Connected, parsing MJPEG stream")
                    wasEverConnected = true
                    isConnected = true
                    reconnectAttempts = 0 // Reset en conexión exitosa
                    lastFrameTime.set(System.currentTimeMillis())
                    updateNotification(STATE_RUNNING)
                    parseMjpegStream(inputStream)
                } else {
                    Log.e(TAG, "Response body is null")
                    updateNotification(STATE_NOT_CONNECTED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception en conexión/parsing: ${e.message}")
                if (wasEverConnected) {
                    handleDisconnection(timeout = false)
                } else {
                    updateNotification(STATE_NOT_CONNECTED)
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

            while (streamJob?.isActive == true && isConnected && !isStoppingStream) {
                bytesRead = withContext(Dispatchers.IO) { inputStream.read(buffer) }

                if (bytesRead <= 0) {
                    Log.d(TAG, "Fin de stream (EOF)")
                    handleDisconnection(timeout = false)
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
                                // Actualizar timestamp del último frame recibido
                                lastFrameTime.set(System.currentTimeMillis())
                                // Publicar frame de debug (escalado y con throttling)
                                maybePublishPreview(bitmap)
                                // Analizar con modelo
                                processFrameForFaceDetection(bitmap)
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
            Log.e(TAG, "Error parseando stream: ${e.message}")
            handleDisconnection(timeout = false)
        } finally {
            // Cerrar explícitamente
            try { inputStream.close() } catch (_: Exception) {}
        }
    }

    private fun maybePublishPreview(src: Bitmap) {
        val now = System.currentTimeMillis()
        val last = lastPreviewTime.get()
        if (now - last < PREVIEW_MIN_INTERVAL_MS) return
        if (!lastPreviewTime.compareAndSet(last, now)) return

        val w = src.width
        val h = src.height
        val scale = minOf(PREVIEW_MAX_WIDTH.toFloat() / w, PREVIEW_MAX_HEIGHT.toFloat() / h, 1f)
        val preview: Bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        } else {
            src
        }
        // Notificar listeners (en el hilo actual; el consumidor debe saltar al main thread)
        frameListeners.forEach { listener ->
            try { listener(preview) } catch (_: Exception) {}
        }
    }

    private suspend fun processFrameForFaceDetection(bitmap: android.graphics.Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                val helper = faceHelper
                if (helper == null || helper.isClose()) {
                    Log.w(TAG, "FaceLandmarkerHelper not available")
                    return@withContext
                }

                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                val timestamp = android.os.SystemClock.uptimeMillis()
                helper.detectAsync(mpImage, timestamp)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame: ${e.message}", e)
            }
        }
    }

    private fun stopStreamDetection() {
        streamJob?.cancel()
        streamJob = null
        isConnected = false
        wasEverConnected = false
        lastFrameTime.set(0L)
        alarmPlayer?.stop()

        // Nueva lógica para cerrar conexión HTTP
        isStoppingStream = true
        currentCall?.cancel()
        try { currentResponseBody?.close() } catch (_: Exception) {}
        currentCall = null
        currentResponseBody = null
    }

    private fun sendDisconnectSignal() {
        // Primero cerrar conexión física antes de notificar al host
        isStoppingStream = true
        currentCall?.cancel()
        try { currentResponseBody?.close() } catch (_: Exception) {}
        currentCall = null
        currentResponseBody = null

        val baseUrl = streamUrl.trim()
        if (baseUrl.isEmpty()) return
        // Derivar endpoint de desconexión: reemplaza /stream por /disconnect, si no existe añade /disconnect
        val disconnectUrl = if (baseUrl.endsWith("/stream")) {
            baseUrl.removeSuffix("/stream") + "/disconnect"
        } else {
            // evitar doble slash
            (if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl) + "/disconnect"
        }
        Log.d(TAG, "Enviando señal de desconexión a: $disconnectUrl")
        // Usar un cliente con timeout corto para no bloquear el stop
        val quickClient = client.newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()
        serviceScope.launch(Dispatchers.IO) {
            try {
                updateNotification(STATE_SENDING_CLOSE)
                val req = Request.Builder().url(disconnectUrl).get().build()
                quickClient.newCall(req).execute().use { resp ->
                    Log.d(TAG, "Respuesta desconexión: code=${resp.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallo enviando desconexión: ${e.message}")
            }
        }
    }

    fun addStateListener(listener: (String) -> Unit) {
        stateListeners.add(listener)
        listener(currentState) // Enviar estado actual inmediatamente
    }

    fun removeStateListener(listener: (String) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addFrameListener(listener: (Bitmap) -> Unit) {
        frameListeners.add(listener)
    }

    fun removeFrameListener(listener: (Bitmap) -> Unit) {
        frameListeners.remove(listener)
    }

    fun addResultListener(listener: (FaceLandmarkerHelper.ResultBundle) -> Unit) {
        resultListeners.add(listener)
    }

    fun removeResultListener(listener: (FaceLandmarkerHelper.ResultBundle) -> Unit) {
        resultListeners.remove(listener)
    }

    fun addAnalysisListener(listener: (FaceAnalysis.Result) -> Unit) {
        analysisListeners.add(listener)
    }

    fun removeAnalysisListener(listener: (FaceAnalysis.Result) -> Unit) {
        analysisListeners.remove(listener)
    }

    fun addEmptyListener(listener: () -> Unit) {
        emptyListeners.add(listener)
    }

    fun removeEmptyListener(listener: () -> Unit) {
        emptyListeners.remove(listener)
    }

    fun addErrorListener(listener: (String, Int) -> Unit) {
        errorListeners.add(listener)
    }

    fun removeErrorListener(listener: (String, Int) -> Unit) {
        errorListeners.remove(listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        shouldReconnect = false
        stopStreamDetection()
        stopWatchdog()

        alarmPlayer?.release()
        alarmPlayer = null

        faceHelper?.clearFaceLandmarker()
        faceHelper = null

        serviceScope.cancel()

        // Cierre final de conexión HTTP
        isStoppingStream = true
        currentCall?.cancel()
        try { currentResponseBody?.close() } catch (_: Exception) {}
        currentCall = null
        currentResponseBody = null
    }

    // FaceLandmarkerHelper.LandmarkerListener implementation

    override fun onAnalysis(result: FaceAnalysis.Result) {
        if (result.eyesClosed) {
            updateNotification(STATE_EYES_CLOSED)
            alarmPlayer?.play()
        } else {
            if (currentState == STATE_EYES_CLOSED) {
                updateNotification(STATE_RUNNING)
            }
            alarmPlayer?.stop()
        }
        // Notificar a listeners de análisis
        analysisListeners.forEach { l ->
            try { l(result) } catch (_: Exception) {}
        }
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        if (isConnected) {
            if (currentState != STATE_RUNNING && currentState != STATE_EYES_CLOSED) {
                updateNotification(STATE_RUNNING)
            }
        }
        // Notificar a listeners de resultados
        resultListeners.forEach { l ->
            try { l(resultBundle) } catch (_: Exception) {}
        }
    }

    override fun onEmpty() {
        if (isConnected) {
            if (currentState != STATE_NO_FACE && currentState != STATE_EYES_CLOSED) {
                updateNotification(STATE_NO_FACE)
            }
        } else {
            if (currentState != STATE_NOT_CONNECTED && currentState != STATE_DISCONNECTED && currentState != STATE_DISCONNECTED_TIMEOUT) {
                updateNotification(STATE_NOT_CONNECTED)
            }
        }
        alarmPlayer?.stop()
        // Notificar a listeners de vacío
        emptyListeners.forEach { l ->
            try { l() } catch (_: Exception) {}
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "Detection error: $error")
        updateNotification("$STATE_ERROR: $error")
        // Notificar a listeners de error
        errorListeners.forEach { l ->
            try { l(error, errorCode) } catch (_: Exception) {}
        }
    }
}
