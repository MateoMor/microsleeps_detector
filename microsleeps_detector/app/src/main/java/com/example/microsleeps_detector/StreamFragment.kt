package com.example.microsleeps_detector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.*
import com.example.microsleeps_detector.databinding.FragmentStreamBinding
import com.example.microsleeps_detector.ui.OverlayView

class StreamFragment : BaseFaceDetectionFragment<FragmentStreamBinding>() {

    override val binding get() = _binding!!

    // Usaremos la misma instancia del servicio que el Dashboard
    private var serviceBound = false
    private var service: DrowsinessDetectionService? = null

    // Listeners para suscribir/desuscribir limpiamente
    private var stateListener: ((String) -> Unit)? = null
    private var resultListener: ((FaceLandmarkerHelper.ResultBundle) -> Unit)? = null
    private var analysisListener: ((FaceAnalysis.Result) -> Unit)? = null
    private var emptyListener: (() -> Unit)? = null
    private var errorListener: ((String, Int) -> Unit)? = null

    // Evitar usar el listener del Activity y evitar alarma local (el servicio ya maneja alarma)
    override val useActivityLandmarkerListener: Boolean = false
    override val playLocalAlarm: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? DrowsinessDetectionService.LocalBinder
            service = local?.getService()
            serviceBound = service != null
            if (!serviceBound) return

            // Estado del servicio -> etiqueta inferior y estado visual
            stateListener = { state ->
                renderer?.setStatus(state)
                if (state == DrowsinessDetectionService.STATE_NOT_CONNECTED || state.startsWith("Se desconectó")) {
                    if (isAdded) {
                        requireActivity().runOnUiThread {
                            binding.streamImageView.setImageDrawable(null)
                            getOverlayView()?.clear()
                        }
                    }
                }
            }
            stateListener?.let { service?.addStateListener(it) }

            // Resultados y análisis -> overlay y labels
            resultListener = { bundle ->
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        getOverlayView()?.setResults(bundle)
                    }
                }
            }
            resultListener?.let { service?.addResultListener(it) }

            analysisListener = { result ->
                renderer?.render(result)
            }
            analysisListener?.let { service?.addAnalysisListener(it) }

            emptyListener = {
                if (isAdded) {
                    renderer?.setStatus("No face detected")
                    requireActivity().runOnUiThread { getOverlayView()?.clear() }
                }
            }
            emptyListener?.let { service?.addEmptyListener(it) }

            errorListener = { error, _ ->
                renderer?.setStatus("Error: $error")
            }
            errorListener?.let { service?.addErrorListener(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            unsubscribeAll()
            service = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStreamBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreatedImpl(view: View, savedInstanceState: Bundle?) {
        // Mostrar estado inicial
        renderer?.setStatus(DrowsinessDetectionService.STATE_NOT_CONNECTED)
        // Vincular al servicio (si ya está corriendo lo reutiliza; si no, solo mostrará estado)
        bindToService()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_stream, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stop_service -> {
                val intent = Intent(requireContext(), DrowsinessDetectionService::class.java).apply {
                    action = DrowsinessDetectionService.ACTION_STOP
                }
                requireContext().startService(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun getOverlayView(): OverlayView? = binding.overlay

    override fun onPauseImpl() {
        // No detener el servicio aquí: compartido entre fragments
    }

    override fun onDestroyViewImpl() {
        unbindFromService()
    }

    private fun bindToService() {
        val intent = Intent(requireContext(), DrowsinessDetectionService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        if (serviceBound) {
            unsubscribeAll()
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun unsubscribeAll() {
        stateListener?.let { service?.removeStateListener(it) }
        resultListener?.let { service?.removeResultListener(it) }
        analysisListener?.let { service?.removeAnalysisListener(it) }
        emptyListener?.let { service?.removeEmptyListener(it) }
        errorListener?.let { service?.removeErrorListener(it) }
        stateListener = null
        resultListener = null
        analysisListener = null
        emptyListener = null
        errorListener = null
    }
}