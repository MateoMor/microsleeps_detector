package com.example.microsleeps_detector

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import com.example.microsleeps_detector.databinding.ActivityMainBinding
import com.google.mediapipe.tasks.vision.core.RunningMode

class MainActivity : AppCompatActivity(), FaceLandmarkerHelper.LandmarkerListener {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    // Exponer el helper para que lo use el fragmento de cámara
    lateinit var faceHelper: FaceLandmarkerHelper
        private set

    // Listener delegado hacia el fragmento activo (p.ej., CameraFragment)
    private var landmarkerDelegate: FaceLandmarkerHelper.LandmarkerListener? = null

    fun setLandmarkerListener(listener: FaceLandmarkerHelper.LandmarkerListener?) {
        landmarkerDelegate = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Inicializar FaceLandmarkerHelper en modo LIVE_STREAM
        faceHelper = FaceLandmarkerHelper(
            // Valores por defecto, puedes ajustarlos luego si quieres
            runningMode = RunningMode.LIVE_STREAM,
            context = this,
            faceLandmarkerHelperListener = this
        )

        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null)
                .setAnchorView(R.id.fab).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    // Implementación del listener: reenviar a quien esté suscrito desde el fragmento
    override fun onError(error: String, errorCode: Int) {
        landmarkerDelegate?.onError(error, errorCode)
    }

    override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
        landmarkerDelegate?.onResults(resultBundle)
    }

    override fun onEmpty() {
        landmarkerDelegate?.onEmpty()
    }

    // Reenviar análisis intermedios si el delegado lo soporta
    override fun onAnalysis(result: FaceAnalysis.Result) {
        landmarkerDelegate?.onAnalysis(result)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!this::faceHelper.isInitialized) return
        // Liberar recursos del helper
        faceHelper.clearFaceLandmarker()
    }

    fun isFaceHelperReady(): Boolean =
        this::faceHelper.isInitialized && !faceHelper.isClose()

    fun faceHelperOrNull(): FaceLandmarkerHelper? =
        if (isFaceHelperReady()) faceHelper else null
}