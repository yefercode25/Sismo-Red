package com.sismored.data

data class MeasurementPoint(
    val elapsedMs: Long,
    val capturedAtMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val amplitude: Float,
    val baseline: Float,
    val anomaly: Boolean
)

data class SessionSummary(
    val id: Long,
    val startedAtMs: Long,
    val durationMs: Long,
    val anomalyCount: Int,
    val maxAmplitude: Float,
    val baseline: Float
)
