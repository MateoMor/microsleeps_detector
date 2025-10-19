package com.example.microsleeps_detector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.microsleeps_detector.FaceLandmarkerHelper
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Store only the result and input image dimensions
    private var results: FaceLandmarkerResult? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var scaleFactor: Float = 1f
    private var lastRunningMode: RunningMode = RunningMode.LIVE_STREAM

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 6f
    }

    // Keep current call site compatibility: CameraFragment passes a ResultBundle
    fun setResults(bundle: FaceLandmarkerHelper.ResultBundle) {
        setResults(
            faceLandmarkerResults = bundle.result,
            imageHeight = bundle.inputImageHeight,
            imageWidth = bundle.inputImageWidth,
            runningMode = RunningMode.LIVE_STREAM
        )
    }

    // Sample-like setter that accepts the raw result and image size
    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults
        this.imageHeight = max(1, imageHeight)
        this.imageWidth = max(1, imageWidth)
        lastRunningMode = runningMode

        // Precompute scale factor using current view size if available
        if (width > 0 && height > 0) {
            scaleFactor = when (runningMode) {
                RunningMode.IMAGE, RunningMode.VIDEO ->
                    min(width.toFloat() / this.imageWidth, height.toFloat() / this.imageHeight)
                RunningMode.LIVE_STREAM ->
                    max(width.toFloat() / this.imageWidth, height.toFloat() / this.imageHeight)
            }
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        results = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = results ?: return

        // Recalculate scale factor on draw in case the view size changed
        if (width > 0 && height > 0) {
            scaleFactor = when (lastRunningMode) {
                RunningMode.IMAGE, RunningMode.VIDEO ->
                    min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
                RunningMode.LIVE_STREAM ->
                    max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            }
        }

        val scaledImageWidth = imageWidth * scaleFactor
        val scaledImageHeight = imageHeight * scaleFactor
        val offsetX = (width - scaledImageWidth) / 2f
        val offsetY = (height - scaledImageHeight) / 2f

        // Draw landmarks using image dimensions, scaled and centered
        for (landmarks in res.faceLandmarks()) {
            for (pt in landmarks) {
                val x = pt.x() * imageWidth * scaleFactor + offsetX
                val y = pt.y() * imageHeight * scaleFactor + offsetY
                canvas.drawCircle(x, y, 3f, pointPaint)
            }
        }
    }
}
