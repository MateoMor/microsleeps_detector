package com.example.microsleeps_detector

import kotlin.math.hypot

class FaceAnalysis(
    private val earClosedThreshold: Float = 0.21f,
    private val earSmoothingAlpha: Float = 0.3f,
    private val nodAmpThreshold: Float = 0.12f,
    private val nodReleaseThreshold: Float = 0.05f,
    private val nodMaxDurationMs: Long = 1200L,
    private val nodBaselineAlpha: Float = 0.03f,
    private val nodMeasureAlpha: Float = 0.3f
) {
    data class Result(
        val earLeft: Float,
        val earRight: Float,
        val earAverage: Float,
        val eyesClosed: Boolean,
        val isNodEvent: Boolean,
        val totalNods: Int,
        val pitchProxy: Float
    )

    private enum class NodState { Idle, Down }
    private var earAvgEma: Float? = null
    private var nodState: NodState = NodState.Idle
    private var nodDownStartTs: Long = 0L
    private var nodBaseline: Float? = null
    private var nodMeasure: Float? = null
    private var nodCount: Int = 0

    // Call for each frame. points must contain Face Mesh indices up to 387.
    fun update(points: List<Pair<Float, Float>>, timestampMs: Long): Result? {
        if (points.size <= 387) return null

        // EAR
        val leftEar = ear(points, true)
        val rightEar = ear(points, false)
        val avgEar = (leftEar + rightEar) / 2f
        earAvgEma = ema(avgEar, earAvgEma, earSmoothingAlpha)
        val earSmoothed = earAvgEma ?: avgEar
        val eyesClosed = earSmoothed < earClosedThreshold

        // Head nod (pitch proxy normalized by inter-ocular distance)
        val pitch = pitchProxy(points)
        nodBaseline = ema(pitch, nodBaseline, nodBaselineAlpha)
        nodMeasure = ema(pitch, nodMeasure, nodMeasureAlpha)
        val base = nodBaseline ?: pitch
        val meas = nodMeasure ?: pitch

        var isEvent = false
        when (nodState) {
            NodState.Idle -> {
                if (meas - base > nodAmpThreshold) {
                    nodState = NodState.Down
                    nodDownStartTs = timestampMs
                }
            }
            NodState.Down -> {
                val tooLong = (timestampMs - nodDownStartTs) > nodMaxDurationMs
                val released = (meas - base) < nodReleaseThreshold
                if (released && !tooLong) {
                    nodCount += 1
                    isEvent = true
                    nodState = NodState.Idle
                } else if (tooLong) {
                    nodState = NodState.Idle
                }
            }
        }

        return Result(
            earLeft = leftEar,
            earRight = rightEar,
            earAverage = earSmoothed,
            eyesClosed = eyesClosed,
            isNodEvent = isEvent,
            totalNods = nodCount,
            pitchProxy = meas
        )
    }

    private fun ear(p: List<Pair<Float, Float>>, left: Boolean): Float {
        val p1 = if (left) 33 else 263
        val p4 = if (left) 133 else 362
        val p2 = if (left) 160 else 387
        val p6 = if (left) 144 else 373
        val p3 = if (left) 158 else 385
        val p5 = if (left) 153 else 380
        val num = d(p, p2, p6) + d(p, p3, p5)
        val den = 2f * d(p, p1, p4)
        return if (den > 0f) num / den else 0f
    }

    private fun pitchProxy(p: List<Pair<Float, Float>>): Float {
        val leftEyeCorner = p[33]
        val rightEyeCorner = p[263]
        val eyeMidY = (leftEyeCorner.second + rightEyeCorner.second) / 2f
        val noseTip = p[1]
        val faceScale = d(p, 33, 263)
        val scale = if (faceScale > 1e-6f) faceScale else 1f
        return (noseTip.second - eyeMidY) / scale
    }

    private fun d(p: List<Pair<Float, Float>>, a: Int, b: Int): Float {
        val ax = p[a].first; val ay = p[a].second
        val bx = p[b].first; val by = p[b].second
        return hypot(ax - bx, ay - by)
    }

    private fun ema(x: Float, prev: Float?, alpha: Float): Float =
        if (prev == null) x else prev + alpha * (x - prev)
}