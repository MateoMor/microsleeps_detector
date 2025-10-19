package com.example.microsleeps_detector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.microsleeps_detector.FaceLandmarkerHelper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var resultBundle: FaceLandmarkerHelper.ResultBundle? = null
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 6f
    }

    fun setResults(bundle: FaceLandmarkerHelper.ResultBundle) {
        resultBundle = bundle
        postInvalidateOnAnimation()
    }

    fun clear() {
        resultBundle = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bundle = resultBundle ?: return
        drawLandmarks(canvas, bundle.result)
    }

    private fun drawLandmarks(canvas: Canvas, result: FaceLandmarkerResult) {
        val widthScale = width.toFloat()
        val heightScale = height.toFloat()

        for (landmarks in result.faceLandmarks()) {
            for (pt in landmarks) {
                val x = pt.x() * widthScale
                val y = pt.y() * heightScale
                canvas.drawCircle(x, y, 3f, pointPaint)
            }
        }
    }
}
