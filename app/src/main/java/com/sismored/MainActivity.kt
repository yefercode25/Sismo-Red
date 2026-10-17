package com.sismored

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sismored.data.MeasurementPoint
import com.sismored.data.LocalNetworkGroup
import com.sismored.data.SessionStore
import com.sismored.data.SessionSummary
import com.sismored.ui.AppScreen
import com.sismored.ui.AppUiState
import com.sismored.ui.CaptureMode
import com.sismored.ui.SismoApp
import com.sismored.ui.SismoTheme
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var store: SessionStore
    private lateinit var networkGroup: LocalNetworkGroup
    private val io = Executors.newSingleThreadExecutor()
    private var state by androidx.compose.runtime.mutableStateOf(AppUiState())
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var linearSensor: Sensor? = null
    private var activeSensor: Sensor? = null
    private var captureMode = CaptureMode.IDLE
    private val sessionPoints = mutableListOf<MeasurementPoint>()
    private val amplitudeWindow = ArrayDeque<Float>()
    private var gravity = FloatArray(3)
    private var gravityInitialized = false
    private var calibrationStartedAt = 0L
    private var calibrationSum = 0.0
    private var calibrationSamples = 0
    private var baseline = 0f
    private var recordingStartedAtElapsed = 0L
    private var recordingStartedAtWall = 0L
    private var lastSampleAt = 0L
    private var aboveThresholdSince: Long? = null
    private var belowThresholdSince: Long? = null
    private var anomalyLatched = false
    private var anomalyCount = 0
    private var maxAmplitude = 0f
    private var pendingExport: Pair<SessionSummary, List<MeasurementPoint>>? = null
    private var pendingNetworkAction: (() -> Unit)? = null

    private val networkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = null
        if (granted) action?.invoke() else state = state.copy(notice = "Se necesita permiso de red local para comunicar los teléfonos.")
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
            state = state.copy(batteryPercent = percent)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val payload = pendingExport
        if (uri == null || payload == null) return@registerForActivityResult
        io.execute {
            val success = runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { writer ->
                        writer.write("session_id,started_at,elapsed_ms,captured_at,acceleration_x_m_s2,acceleration_y_m_s2,acceleration_z_m_s2,amplitude_m_s2,baseline_m_s2,variation_detected")
                        writer.newLine()
                        payload.second.forEach { point ->
                            writer.append(payload.first.id.toString()).append(',')
                            writer.append(csvTime(payload.first.startedAtMs)).append(',')
                            writer.append(point.elapsedMs.toString()).append(',')
                            writer.append(csvTime(point.capturedAtMs)).append(',')
                            writer.append("%.4f".format(Locale.US, point.x)).append(',')
                            writer.append("%.4f".format(Locale.US, point.y)).append(',')
                            writer.append("%.4f".format(Locale.US, point.z)).append(',')
                            writer.append("%.4f".format(Locale.US, point.amplitude)).append(',')
                            writer.append("%.4f".format(Locale.US, point.baseline)).append(',')
                            writer.append(if (point.anomaly) "true" else "false")
                            writer.newLine()
                        }
                    }
                } ?: error("No se pudo abrir el archivo")
            }.isSuccess
            runOnUiThread { state = state.copy(notice = if (success) "CSV exportado correctamente." else "No se pudo exportar el CSV.") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        store = SessionStore(this)
        networkGroup = LocalNetworkGroup(
            context = this,
            onStatus = { message, role, address, peers -> runOnUiThread {
                state = state.copy(networkStatus = message, networkRole = role, hostAddress = address, connectedDevices = peers)
            } },
            onReadings = { readings -> runOnUiThread { state = state.copy(remoteReadings = readings) } }
        )
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        activeSensor = linearSensor ?: accelerometer
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        state = state.copy(
            deviceName = model.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            hasAccelerometer = accelerometer != null,
            hasGyroscope = gyroscope != null,
            hasLinearSensor = linearSensor != null
        )
        loadSessions()
        setContent {
            SismoTheme {
                SismoApp(
                    state = state,
                    onNavigate = { screen -> state = state.copy(screen = screen, notice = null) },
                    onStart = ::startMonitoring,
                    onStop = ::stopMonitoring,
                    onSelectSession = ::selectSession,
                    onExport = ::exportSession,
                    onHostGroup = { withNetworkPermission { networkGroup.startHost() } },
                    onJoinGroup = { withNetworkPermission { networkGroup.discoverAndJoin() } },
                    onLeaveGroup = { networkGroup.stop() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = registerReceiver(null, filter)
        batteryReceiver.onReceive(this, sticky)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, filter)
        }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations && captureMode != CaptureMode.IDLE) stopMonitoring()
        super.onStop()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        networkGroup.shutdown()
        io.shutdown()
        super.onDestroy()
    }

    private fun loadSessions() {
        io.execute {
            val list = runCatching { store.getSessions() }.getOrDefault(emptyList())
            runOnUiThread { state = state.copy(sessions = list) }
        }
    }

    private fun startMonitoring() {
        if (activeSensor == null) {
            state = state.copy(notice = "Este dispositivo no tiene acelerómetro disponible.")
            return
        }
        state = state.copy(screen = AppScreen.MONITOR, notice = null)
        if (state.calibrated) {
            beginRecording()
            return
        }
        captureMode = CaptureMode.CALIBRATING
        calibrationStartedAt = SystemClock.elapsedRealtime()
        calibrationSum = 0.0
        calibrationSamples = 0
        gravityInitialized = false
        sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME)
        state = state.copy(capture = CaptureMode.CALIBRATING, calibrationProgress = 0f)
    }

    private fun beginRecording() {
        captureMode = CaptureMode.RECORDING
        recordingStartedAtElapsed = SystemClock.elapsedRealtime()
        recordingStartedAtWall = System.currentTimeMillis()
        lastSampleAt = 0L
        sessionPoints.clear()
        amplitudeWindow.clear()
        aboveThresholdSince = null
        belowThresholdSince = null
        anomalyLatched = false
        anomalyCount = 0
        maxAmplitude = 0f
        val threshold = max(0.35f, baseline * 3f + 0.15f)
        activeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        state = state.copy(
            screen = AppScreen.MONITOR,
            capture = CaptureMode.RECORDING,
            calibrated = true,
            baseline = baseline,
            threshold = threshold,
            elapsedMs = 0,
            x = 0f, y = 0f, z = 0f, amplitude = 0f, anomalyCount = 0,
            chartPoints = emptyList(), notice = "Medición experimental activa."
        )
    }

    private fun stopMonitoring() {
        if (captureMode == CaptureMode.IDLE) return
        val mode = captureMode
        captureMode = CaptureMode.IDLE
        sensorManager.unregisterListener(this)
        if (mode == CaptureMode.RECORDING && sessionPoints.isNotEmpty()) {
            val points = sessionPoints.toList()
            val startedAt = recordingStartedAtWall
            val duration = (SystemClock.elapsedRealtime() - recordingStartedAtElapsed).coerceAtLeast(0L)
            val count = anomalyCount
            val maximum = maxAmplitude
            val reference = baseline
            state = state.copy(capture = CaptureMode.IDLE, isSaving = true, notice = "Guardando sesión en este dispositivo…")
            io.execute {
                val saved = runCatching { store.saveSession(startedAt, duration, count, maximum, reference, points) }.getOrNull()
                val sessions = runCatching { store.getSessions() }.getOrDefault(emptyList())
                val savedPoints = saved?.let { id -> runCatching { store.getPoints(id) }.getOrDefault(emptyList()) }.orEmpty()
                runOnUiThread {
                    state = state.copy(
                        isSaving = false,
                        sessions = sessions,
                        selectedSessionId = saved ?: state.selectedSessionId,
                        selectedPoints = if (saved != null) savedPoints else state.selectedPoints,
                        notice = if (saved != null) "Sesión guardada localmente." else "No se pudo guardar la sesión."
                    )
                }
            }
        } else {
            state = state.copy(capture = CaptureMode.IDLE, notice = "Calibración cancelada.")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val axes = FloatArray(3)
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && linearSensor == null) {
            if (!gravityInitialized) {
                for (i in 0..2) gravity[i] = event.values[i]
                gravityInitialized = true
            }
            for (i in 0..2) {
                gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
                axes[i] = event.values[i] - gravity[i]
            }
        } else {
            for (i in 0..2) axes[i] = event.values[i]
        }
        val magnitude = sqrt(axes[0] * axes[0] + axes[1] * axes[1] + axes[2] * axes[2])

        if (captureMode == CaptureMode.CALIBRATING) {
            calibrationSum += magnitude
            calibrationSamples++
            val progress = ((now - calibrationStartedAt).toFloat() / CALIBRATION_DURATION_MS).coerceIn(0f, 1f)
            state = state.copy(calibrationProgress = progress)
            if (now - calibrationStartedAt >= CALIBRATION_DURATION_MS) {
                baseline = if (calibrationSamples > 0) (calibrationSum / calibrationSamples).toFloat() else 0f
                state = state.copy(calibrated = true, baseline = baseline, calibrationProgress = 1f)
                beginRecording()
            }
            return
        }
        if (captureMode != CaptureMode.RECORDING || now - lastSampleAt < SAMPLE_INTERVAL_MS) return
        lastSampleAt = now

        amplitudeWindow.addLast(magnitude)
        while (amplitudeWindow.size > AMPLITUDE_WINDOW_SIZE) amplitudeWindow.removeFirst()
        val amplitude = sqrt(amplitudeWindow.map { it * it }.average()).toFloat()
        val threshold = max(0.35f, baseline * 3f + 0.15f)
        var anomalyMarker = false
        if (amplitude >= threshold) {
            belowThresholdSince = null
            if (aboveThresholdSince == null) aboveThresholdSince = now
            if (!anomalyLatched && now - (aboveThresholdSince ?: now) >= ANOMALY_MIN_DURATION_MS) {
                anomalyLatched = true
                anomalyCount++
                anomalyMarker = true
            }
        } else {
            aboveThresholdSince = null
            if (belowThresholdSince == null) belowThresholdSince = now
            if (anomalyLatched && now - (belowThresholdSince ?: now) >= ANOMALY_RESET_MS) anomalyLatched = false
        }
        maxAmplitude = max(maxAmplitude, amplitude)
        val elapsed = now - recordingStartedAtElapsed
        val point = MeasurementPoint(
            elapsedMs = elapsed,
            capturedAtMs = System.currentTimeMillis(),
            x = axes[0], y = axes[1], z = axes[2],
            amplitude = amplitude, baseline = baseline, anomaly = anomalyMarker
        )
        sessionPoints.add(point)
        networkGroup.send(state.deviceName, elapsed, axes[0], axes[1], axes[2], amplitude, anomalyMarker)
        val message = if (anomalyLatched) "Variación de vibración detectada. Registro experimental." else "Vibración dentro de la referencia."
        state = state.copy(
            elapsedMs = elapsed, x = axes[0], y = axes[1], z = axes[2],
            amplitude = amplitude, threshold = threshold, anomalyCount = anomalyCount,
            chartPoints = (state.chartPoints + point).takeLast(CHART_WINDOW_SIZE),
            notice = message
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun selectSession(id: Long) {
        state = state.copy(selectedSessionId = id, selectedPoints = emptyList(), notice = null)
        io.execute {
            val points = runCatching { store.getPoints(id) }.getOrDefault(emptyList())
            runOnUiThread { state = state.copy(selectedSessionId = id, selectedPoints = points) }
        }
    }

    private fun exportSession(session: SessionSummary) {
        io.execute {
            val points = runCatching { store.getPoints(session.id) }.getOrDefault(emptyList())
            pendingExport = session to points
            val name = "sismored-sesion-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(session.startedAtMs))}.csv"
            runOnUiThread { exportLauncher.launch(name) }
        }
    }

    private fun csvTime(timeMs: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timeMs))

    private fun withNetworkPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 37 || checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        pendingNetworkAction = action
        networkPermissionLauncher.launch(LOCAL_NETWORK_PERMISSION)
    }

    companion object {
        private const val CALIBRATION_DURATION_MS = 5_000L
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val ANOMALY_MIN_DURATION_MS = 600L
        private const val ANOMALY_RESET_MS = 1_000L
        private const val AMPLITUDE_WINDOW_SIZE = 5
        private const val CHART_WINDOW_SIZE = 90
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
