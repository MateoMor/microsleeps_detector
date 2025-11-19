package com.example.microsleeps_detector

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.example.microsleeps_detector.databinding.DashboardBinding

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class Dashboard : Fragment() {

    private var _binding: DashboardBinding? = null
    private val binding get() = _binding!!

    private var serviceBound = false
    private var service: DrowsinessDetectionService? = null
    private var stateListener: ((String) -> Unit)? = null
    private var frameListener: ((Bitmap) -> Unit)? = null

    private val REQ_NOTIF = 1001
    private val REQ_CAMERA = 1002

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? DrowsinessDetectionService.LocalBinder
            service = local?.getService()
            serviceBound = service != null
            // Subscribe to status updates
            stateListener = { state -> updateServiceStatus(state) }
            stateListener?.let { service?.addStateListener(it) }
            // Subscribe to debug frames
            frameListener = { bmp ->
                // Ensure main thread update
                view?.post {
                    binding.debugFrame.setImageBitmap(bmp)
                }
            }
            frameListener?.let { service?.addFrameListener(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Unsubscribe listeners
            stateListener?.let { service?.removeStateListener(it) }
            frameListener?.let { service?.removeFrameListener(it) }
            stateListener = null
            frameListener = null
            service = null
            serviceBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonFirst.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_camera)
        }

        binding.buttonStream.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_stream)
        }

        binding.startServiceBtn.setOnClickListener { ensurePermissionsAndStart() }
        binding.stopServiceBtn.setOnClickListener { stopBackgroundService() }

        // Toggle visibility of URL input depending on selected source
        binding.sourceRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val streamSelected = checkedId == R.id.radioSourceStream
            binding.streamUrlInput.visibility = if (streamSelected) View.VISIBLE else View.GONE
        }
        // Initialize visibility
        binding.streamUrlInput.visibility = if (binding.sourceRadioGroup.checkedRadioButtonId == R.id.radioSourceStream) View.VISIBLE else View.GONE

        // Try binding if service is already running
        bindToService()
    }

    private fun isPhoneSourceSelected(): Boolean =
        binding.sourceRadioGroup.checkedRadioButtonId == R.id.radioSourcePhone

    private fun ensurePermissionsAndStart() {
        // If phone camera selected, ensure CAMERA permission first
        if (isPhoneSourceSelected()) {
            val camGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!camGranted) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                return
            }
        }
        // Ensure notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            val notifGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                return
            }
        }
        startBackgroundService()
    }

    private fun startBackgroundService() {
        val phoneSelected = isPhoneSourceSelected()
        val intent = Intent(requireContext(), DrowsinessDetectionService::class.java).apply {
            action = DrowsinessDetectionService.ACTION_START
            putExtra(DrowsinessDetectionService.EXTRA_SOURCE, if (phoneSelected) DrowsinessDetectionService.SOURCE_PHONE_CAMERA else DrowsinessDetectionService.SOURCE_STREAM)
            if (!phoneSelected) {
                val url = binding.streamUrlInput.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), "Ingresa la URL del stream", Toast.LENGTH_SHORT).show()
                    return
                }
                putExtra(DrowsinessDetectionService.EXTRA_STREAM_URL, url)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
        bindToService()
        updateServiceStatus("Iniciando…")
    }

    private fun stopBackgroundService() {
        val intent = Intent(requireContext(), DrowsinessDetectionService::class.java).apply {
            action = DrowsinessDetectionService.ACTION_STOP
        }
        requireContext().startService(intent)
        unbindFromService()
        updateServiceStatus("Estado del servicio: Inactivo")
        // Clear debug frame
        binding.debugFrame.setImageDrawable(null)
    }

    private fun bindToService() {
        val intent = Intent(requireContext(), DrowsinessDetectionService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        if (serviceBound) {
            stateListener?.let { service?.removeStateListener(it) }
            frameListener?.let { service?.removeFrameListener(it) }
            stateListener = null
            frameListener = null
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun updateServiceStatus(state: String) {
        binding.serviceStatusText.text = "Estado del servicio: $state"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIF -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startBackgroundService()
                } else {
                    Toast.makeText(requireContext(), "Permiso de notificaciones denegado", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_CAMERA -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // After camera granted, continue with notification check/start
                    ensurePermissionsAndStart()
                } else {
                    Toast.makeText(requireContext(), "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unbindFromService()
        _binding = null
    }
}