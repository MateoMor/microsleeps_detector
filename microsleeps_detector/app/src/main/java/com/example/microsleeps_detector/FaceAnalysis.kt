package com.example.microsleeps_detector

import kotlin.math.hypot

/**
 * Computes per-frame face analysis metrics from MediaPipe Face Mesh landmarks.
 *
 * Metrics:
 * - EAR (Eye Aspect Ratio): left, right, and smoothed average; eyesClosed boolean based on threshold.
 * - Head nod detection: state machine using a normalized pitch proxy; maintains a running count.
 */
class FaceAnalysis(
    private val earClosedThreshold: Float = 0.10f,      // Eyes are considered closed when smoothed EAR < this value.
    private val earSmoothingAlpha: Float = 0.3f,       // EMA alpha for EAR smoothing \[0..1]; higher = faster, noisier.

    private val nodAmpThreshold: Float = 0.09f,       // Delta threshold to trigger a nod (start) in the selected polarity.
    private val nodReleaseThreshold: Float = 0.05f,      // Delta threshold to release/end the nod (hysteresis).

    private val nodMaxDurationMs: Long = 1000L,         // Max time between trigger and release to count a nod.

    private val nodBaselineAlpha: Float = 0.03f ,       // EMA alpha for the slow baseline of pitch (long‑term pose).
    private val nodMeasureAlpha: Float = 0.3f,        // EMA alpha for the fast measure of pitch (short‑term motion).
    // Polarity: DOWN (default) triggers when nose goes down relative to eyes; UP for inverse.
    private val nodPolarity: NodPolarity = NodPolarity.DOWN
) {
    /**
     * Output of a single analysis step.
     * @property earLeft raw EAR for left eye
     * @property earRight raw EAR for right eye
     * @property earAverage smoothed average EAR used for eyesClosed
     * @property eyesClosed true if \[earAverage] < threshold
     * @property isNodEvent true if a nod completes on this frame
     * @property totalNods running nod counter
     * @property pitchProxy smoothed normalized vertical displacement of nose vs eyes
     */
    data class Result(
        val earLeft: Float,
        val earRight: Float,
        val earAverage: Float,
        val eyesClosed: Boolean,
        val isNodEvent: Boolean,
        val totalNods: Int,
        val pitchProxy: Float
    )

    enum class NodPolarity { DOWN, UP }

    private enum class NodState { Idle, Down }
    private var earAvgEma: Float? = null
    private var nodState: NodState = NodState.Idle
    private var nodDownStartTs: Long = 0L
    private var nodBaseline: Float? = null
    private var nodMeasure: Float? = null
    private var nodCount: Int = 0

    /**
     * Processes landmarks for a frame and updates EAR and nod metrics.
     * @param points normalized \[0,1] list of (x,y) landmarks for the first face. Must include indices up to 387.
     * @param timestampMs frame timestamp in milliseconds (monotonic preferred).
     * @return \[Result] if indices are present; null if not enough landmarks.
     */
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
        val delta = meas - base

        // Polarity-dependent triggers
        val triggerDown = delta > nodAmpThreshold
        val releaseDown = delta < nodReleaseThreshold
        val triggerUp = delta < -nodAmpThreshold
        val releaseUp = delta > -nodReleaseThreshold

        val trigger = if (nodPolarity == NodPolarity.DOWN) triggerDown else triggerUp
        val release = if (nodPolarity == NodPolarity.DOWN) releaseDown else releaseUp

        var isEvent = false
        when (nodState) {
            NodState.Idle -> {
                if (trigger) {
                    nodState = NodState.Down
                    nodDownStartTs = timestampMs
                }
            }
            NodState.Down -> {
                val tooLong = (timestampMs - nodDownStartTs) > nodMaxDurationMs
                if (release && !tooLong) {
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

    /**
     * Computes Eye Aspect Ratio (EAR) for one eye using common Face Mesh indices.
     */
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

    /**
     * Pitch proxy normalized by inter-ocular distance: positive when nose goes down relative to eyes.
     * MediaPipe normalized Y increases downward.
     */
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
