package com.sismored

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.sismored.data.MeasurementPoint
import com.sismored.data.LocalNetworkGroup
import com.sismored.data.SessionStore
import com.sismored.data.SessionSummary
import com.sismored.data.AccountProfile
import com.sismored.data.StationRecord
import com.sismored.data.SupabaseRepository
import com.sismored.ui.AppScreen
import com.sismored.ui.AppUiState
import com.sismored.ui.CaptureMode
import com.sismored.ui.SismoApp
import com.sismored.ui.SismoTheme
import com.sismored.ui.SensorCheckRow
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URI
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var store: SessionStore
    private lateinit var networkGroup: LocalNetworkGroup
    private lateinit var supabase: SupabaseRepository
    private val io = Executors.newSingleThreadExecutor()
    private var state by androidx.compose.runtime.mutableStateOf(AppUiState())
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var linearSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var activeSensor: Sensor? = null
    private var rotationReference: FloatArray? = null
    private var rotationReferenceStartedAt = 0L
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var motionSensorsRegistered = false
    private var sensorCheckStartedAt = 0L
    private var sensorCheckSensors: List<Sensor> = emptyList()
    private val sensorCheckData = mutableMapOf<Int, SensorCheckAccumulator>()
    private data class SensorCheckAccumulator(
        var samples: Int = 0,
        var accuracy: Int = -1,
        var latest: String = "",
        var quietMagnitudeSum: Float = 0f,
        var quietSamples: Int = 0,
        var quietVector: FloatArray? = null,
        var respondedToMovement: Boolean = false
    )
    private var activityResumed = false
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
    private var pendingLocationAction: (() -> Unit)? = null
    private var account: AccountProfile? = null
    private var station: StationRecord? = null
    private var lastCloudStatusAtMs = 0L
    private var observingCloud = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stationHeartbeat = object : Runnable {
        override fun run() {
            val currentAccount = account
            val currentStation = station
            if (currentAccount?.role == "station" && currentStation != null && captureMode == CaptureMode.IDLE) {
                publishStationStatus(state.amplitude, false)
            }
            if (account?.role == "station") mainHandler.postDelayed(this, 5_000L)
        }
    }
    private val sensorCheckTicker = object : Runnable {
        override fun run() {
            if (!state.sensorCheckRunning) return
            val elapsed = SystemClock.elapsedRealtime() - sensorCheckStartedAt
            val remaining = (SENSOR_CHECK_DURATION_MS - elapsed).coerceAtLeast(0)
            val phase = if (elapsed < SENSOR_CHECK_REST_MS) "Deja el teléfono quieto un momento; luego inclínalo y muévelo suavemente." else "Ahora inclina y mueve suavemente el teléfono en varios ejes."
            state = state.copy(
                sensorCheckRemainingMs = remaining,
                sensorCheckPhase = phase,
                sensorCheckRows = buildSensorCheckRows(elapsed, false)
            )
            if (remaining == 0L) finishSensorCheck() else mainHandler.postDelayed(this, SENSOR_CHECK_UI_INTERVAL_MS)
        }
    }

    private val networkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = null
        if (granted) action?.invoke() else state = state.copy(notice = "Se necesita permiso de red local para comunicar los teléfonos.")
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingLocationAction
        pendingLocationAction = null
        if (grants.values.any { it }) action?.invoke()
        else state = state.copy(locationBusy = false, notice = "Permite la ubicación para mostrarla en el mapa.")
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
        supabase = SupabaseRepository()
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
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        activeSensor = linearSensor ?: accelerometer
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        state = state.copy(
            deviceName = model.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            hasAccelerometer = accelerometer != null,
            hasGyroscope = gyroscope != null,
            hasLinearSensor = linearSensor != null,
            hasRotationVector = rotationVectorSensor != null,
            hasMagnetometer = magnetometer != null,
            hasGravitySensor = gravitySensor != null
        )
        loadSessions()
        restoreAccount()
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
                    configured = supabase.isConfigured,
                    onAuth = ::authenticate,
                    onScanStation = ::scanStation,
                    onSelectStation = ::selectStation,
                    onShowAllStations = { state = state.copy(selectedStationId = null, selectedStationMunicipality = null, notice = "Viendo todas las estaciones registradas.") },
                    onSignOut = ::signOut,
                    onSaveStationLocation = ::saveStationLocation,
                    onRequestReceiverLocation = ::showReceiverLocation,
                    onRunSensorCheck = ::startSensorCheck,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = registerReceiver(null, filter)
        batteryReceiver.onReceive(this, sticky)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(batteryReceiver, filter)
        }
        startStationHeartbeat()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onPause()
    }

    override fun onStop() {
        activityResumed = false
        mainHandler.removeCallbacks(stationHeartbeat)
        mainHandler.removeCallbacks(sensorCheckTicker)
        if (state.sensorCheckRunning) {
            sensorCheckSensors.forEach { sensorManager.unregisterListener(this, it) }
            sensorCheckSensors = emptyList()
            state = state.copy(sensorCheckRunning = false, sensorCheckPhase = "Prueba interrumpida al salir de la pantalla.")
        }
        if (!isChangingConfigurations && captureMode != CaptureMode.IDLE) stopMonitoring()
        sensorManager.unregisterListener(this)
        motionSensorsRegistered = false
        super.onStop()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        networkGroup.shutdown()
        supabase.close()
        io.execute { store.close() }
        io.shutdown()
        super.onDestroy()
    }

    private fun loadSessions() {
        io.execute {
            val list = runCatching { store.getSessions() }.getOrDefault(emptyList())
            runOnUiThread { state = state.copy(sessions = list) }
        }
    }

    private fun restoreAccount() {
        io.execute {
            val profile = runCatching { runBlocking { supabase.currentProfile() } }.getOrElse {
                runOnUiThread { state = state.copy(authReady = true, notice = readableError(it, "No se pudo comprobar la sesión. Revisa la conexión e intenta de nuevo.")) }
                return@execute
            }
            val stationRecord = if (profile?.role == "station") runCatching { runBlocking { supabase.stationForOwner(profile.userId) } }.getOrNull() else null
            account = profile
            station = stationRecord
            rotationReferenceStartedAt = 0L
            val savedCalibration = profile?.takeIf { it.role == "station" }?.let { user ->
                getSharedPreferences("station_calibration", MODE_PRIVATE).takeIf { it.contains(user.userId) }?.getFloat(user.userId, 0f)
            }
            rotationReference = profile?.takeIf { it.role == "station" }?.userId?.let(::loadRotationReference)
            if (savedCalibration != null) baseline = savedCalibration
            runOnUiThread {
                state = state.copy(
                    authReady = true,
                    account = profile,
                    station = stationRecord,
                    calibrated = savedCalibration != null,
                    baseline = savedCalibration ?: 0f,
                    selectedStationMunicipality = profile?.takeIf { it.role == "station" }?.municipality?.takeIf { it.isNotBlank() },
                    notice = if (profile != null && profile.role == "station" && stationRecord == null) "No se encontró la estación de esta cuenta. Revisa el esquema de Supabase." else null
                )
            }
            if (profile != null) observeCloud()
            if (profile?.role == "station") runOnUiThread(::startStationHeartbeat)
        }
    }

    private fun authenticate(register: Boolean, role: String, email: String, password: String, name: String, municipality: String) {
        state = state.copy(authBusy = true, notice = null)
        io.execute {
            try {
                val profile = runBlocking {
                    if (register) supabase.register(email, password, name, role, "Guavio", municipality) else supabase.signIn(email, password)
                }
                if (!register && profile != null && profile.role != role) {
                    error("Esta cuenta pertenece al modo ${if (profile.role == "station") "Estación" else "Receptor"}.")
                }
                if (profile == null) {
                    runOnUiThread { state = state.copy(authBusy = false, notice = "Cuenta creada. Confirma el correo y luego ingresa.") }
                    return@execute
                }
                val stationRecord = if (profile.role == "station") runBlocking { supabase.stationForOwner(profile.userId) } else null
                account = profile
                station = stationRecord
                rotationReferenceStartedAt = 0L
                val savedCalibration = profile.takeIf { it.role == "station" }?.let { user ->
                    getSharedPreferences("station_calibration", MODE_PRIVATE).takeIf { it.contains(user.userId) }?.getFloat(user.userId, 0f)
                }
                rotationReference = profile.takeIf { it.role == "station" }?.userId?.let(::loadRotationReference)
                if (savedCalibration != null) baseline = savedCalibration
                runOnUiThread {
                    state = state.copy(
                        authReady = true,
                        authBusy = false,
                        account = profile,
                        station = stationRecord,
                        calibrated = savedCalibration != null,
                        baseline = savedCalibration ?: 0f,
                        selectedStationMunicipality = profile.takeIf { it.role == "station" }?.municipality?.takeIf { it.isNotBlank() },
                        screen = AppScreen.HOME,
                        notice = if (profile.role == "station" && stationRecord == null) "No se encontró el registro de estación. Ejecuta supabase/schema.sql." else "Sesión iniciada."
                    )
                }
                observeCloud()
                if (profile.role == "station") runOnUiThread(::startStationHeartbeat)
            } catch (error: Throwable) {
                runOnUiThread { state = state.copy(authBusy = false, notice = readableError(error, "No se pudo iniciar sesión. Revisa los datos e intenta otra vez.")) }
            }
        }
    }

    private fun readableError(error: Throwable, fallback: String): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("invalid login credentials", ignoreCase = true) -> "Correo o contraseña incorrectos."
            message.contains("email not confirmed", ignoreCase = true) -> "Confirma el correo antes de iniciar sesión."
            message.startsWith("HTTP request to", ignoreCase = true) || message.isBlank() -> fallback
            else -> message.take(160)
        }
    }

    private fun observeCloud() {
        if (observingCloud) return
        observingCloud = true
        supabase.observe(
            onAlerts = { alerts -> runOnUiThread { state = state.copy(communityAlerts = alerts.sortedByDescending { it.detectedAt }.take(20)) } },
            onStations = { stations -> runOnUiThread { state = state.copy(stationStatuses = stations) } },
            onRegisteredStations = { stations -> runOnUiThread { state = state.copy(registeredStations = stations.sortedBy { it.municipality }) } },
            onError = { runOnUiThread { state = state.copy(notice = "Tiempo real no disponible. Comprueba la conexión e inténtalo de nuevo.") } }
        )
    }

    private fun startStationHeartbeat() {
        mainHandler.removeCallbacks(stationHeartbeat)
        if (account?.role == "station" && activityResumed) {
            registerMotionSensors()
            mainHandler.post(stationHeartbeat)
        }
    }

    private fun registerMotionSensors() {
        if (motionSensorsRegistered || activeSensor == null) return
        sensorManager.registerListener(this, activeSensor, SensorManager.SENSOR_DELAY_GAME)
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (linearSensor == null) gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        motionSensorsRegistered = true
    }

    private fun startSensorCheck() {
        if (account?.role != "station") return
        if (captureMode != CaptureMode.IDLE) {
            state = state.copy(notice = "Detén la medición antes de comprobar los sensores.")
            return
        }
        sensorCheckSensors = listOfNotNull(
            accelerometer, linearSensor, gyroscope, rotationVectorSensor, magnetometer, gravitySensor, pressureSensor
        ).distinctBy { it.type }
        sensorCheckData.clear()
        sensorCheckSensors.forEach { sensorCheckData[it.type] = SensorCheckAccumulator() }
        sensorCheckStartedAt = SystemClock.elapsedRealtime()
        state = state.copy(
            sensorCheckRunning = true,
            sensorCheckRemainingMs = SENSOR_CHECK_DURATION_MS,
            sensorCheckPhase = "Deja el teléfono quieto un momento; luego inclínalo y muévelo suavemente.",
            sensorCheckRows = buildSensorCheckRows(0L, false)
        )
        sensorCheckSensors.forEach { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        mainHandler.removeCallbacks(sensorCheckTicker)
        mainHandler.post(sensorCheckTicker)
    }

    private fun finishSensorCheck() {
        mainHandler.removeCallbacks(sensorCheckTicker)
        val rows = buildSensorCheckRows(SENSOR_CHECK_DURATION_MS, true)
        sensorCheckSensors.forEach { sensorManager.unregisterListener(this, it) }
        sensorCheckSensors = emptyList()
        state = state.copy(sensorCheckRunning = false, sensorCheckRemainingMs = 0, sensorCheckPhase = "Comprobación terminada.", sensorCheckRows = rows)
        motionSensorsRegistered = false
        if (activityResumed && account?.role == "station") registerMotionSensors()
    }

    private fun buildSensorCheckRows(elapsedMs: Long, finished: Boolean): List<SensorCheckRow> {
        val definitions = listOf(
            Triple(Sensor.TYPE_LINEAR_ACCELERATION, "Aceleración lineal", "Vibración sin gravedad; sensor principal"),
            Triple(Sensor.TYPE_ACCELEROMETER, "Acelerómetro", "Alternativa; la app separa la gravedad"),
            Triple(rotationVectorSensor?.type ?: Sensor.TYPE_ROTATION_VECTOR, "Orientación 3D", "Inclinación relativa X, Y y Z"),
            Triple(Sensor.TYPE_GYROSCOPE, "Giroscopio", "Velocidad de giro en cada eje"),
            Triple(Sensor.TYPE_MAGNETIC_FIELD, "Magnetómetro", "Apoya el rumbo; sensible a interferencias"),
            Triple(Sensor.TYPE_GRAVITY, "Gravedad", "Referencia para separar movimiento"),
            Triple(Sensor.TYPE_PRESSURE, "Barómetro", "Presión/altitud; complemento, no detecta vibración")
        )
        val availableTypes = sensorCheckSensors.mapTo(mutableSetOf()) { it.type }
        return definitions.map { (type, label, purpose) ->
            val available = type in availableTypes
            val data = sensorCheckData[type]
            val accuracy = when (data?.accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Precisión alta"
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Precisión media"
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Precisión baja"
                SensorManager.SENSOR_STATUS_UNRELIABLE -> "No fiable (Android)"
                else -> "Sin reporte"
            }
            val result = when {
                !available -> "No instalado"
                data == null || data.samples < 5 -> if (finished) "Sin respuesta" else "Esperando lectura"
                type == Sensor.TYPE_PRESSURE -> "Complementario"
                !finished -> "Recibiendo datos"
                data.respondedToMovement && data.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Útil · respondió"
                data.respondedToMovement && data.accuracy >= 0 -> "Respondió · precisión baja"
                data.respondedToMovement -> "Respondió · sin dato de precisión"
                else -> "Recibe datos · falta mover"
            }
            SensorCheckRow(
                name = label,
                purpose = purpose,
                available = available,
                samples = data?.samples ?: 0,
                reading = data?.latest.orEmpty(),
                accuracy = accuracy,
                result = result
            )
        }
    }

    private fun recordSensorCheck(event: SensorEvent) {
        if (!state.sensorCheckRunning) return
        val data = sensorCheckData[event.sensor.type] ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - sensorCheckStartedAt
        val values = event.values
        val magnitude = if (event.sensor.type == Sensor.TYPE_PRESSURE) values[0] else {
            val count = minOf(values.size, 3)
            sqrt((0 until count).sumOf { (values[it] * values[it]).toDouble() }.toFloat())
        }
        data.samples++
        if (event.accuracy >= 0) data.accuracy = event.accuracy
        data.latest = when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> "${formatSensorValue(values[0])} hPa"
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> "qX ${formatSensorValue(values[0])}, qY ${formatSensorValue(values.getOrElse(1) { 0f })}, qZ ${formatSensorValue(values.getOrElse(2) { 0f })}"
            Sensor.TYPE_GYROSCOPE -> "X ${formatSensorValue(values[0])}, Y ${formatSensorValue(values.getOrElse(1) { 0f })}, Z ${formatSensorValue(values.getOrElse(2) { 0f })} rad/s"
            Sensor.TYPE_MAGNETIC_FIELD -> "X ${formatSensorValue(values[0])}, Y ${formatSensorValue(values.getOrElse(1) { 0f })}, Z ${formatSensorValue(values.getOrElse(2) { 0f })} µT"
            else -> "X ${formatSensorValue(values[0])}, Y ${formatSensorValue(values.getOrElse(1) { 0f })}, Z ${formatSensorValue(values.getOrElse(2) { 0f })} m/s²"
        }
        if (elapsed < SENSOR_CHECK_REST_MS) {
            data.quietMagnitudeSum += magnitude
            data.quietSamples++
            if (data.quietVector == null) data.quietVector = values.copyOf(minOf(values.size, 3))
        } else if (event.sensor.type != Sensor.TYPE_PRESSURE) {
            val baseline = data.quietVector
            val delta = baseline?.let { reference ->
                sqrt((0 until minOf(values.size, reference.size)).sumOf { index ->
                    val difference = values[index] - reference[index]
                    (difference * difference).toDouble()
                }.toFloat())
            } ?: 0f
            val threshold = when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> 0.04f
                Sensor.TYPE_MAGNETIC_FIELD -> 3f
                Sensor.TYPE_LINEAR_ACCELERATION -> 0.08f
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> 0.025f
                else -> 0.15f
            }
            val movement = delta > threshold
            if (movement) data.respondedToMovement = true
        }
    }

    private fun formatSensorValue(value: Float): String = "%.2f".format(Locale.US, value)

    private fun publishStationStatus(amplitude: Float, anomaly: Boolean) {
        val currentAccount = account ?: return
        val currentStation = station ?: return
        supabase.publishStatus(
            currentAccount, currentStation, amplitude, anomaly,
            state.x, state.y, state.z,
            state.rotationX.takeIf { rotationVectorSensor != null },
            state.rotationY.takeIf { rotationVectorSensor != null },
            state.rotationZ.takeIf { rotationVectorSensor != null },
            gyroX.takeIf { gyroscope != null }, gyroY.takeIf { gyroscope != null }, gyroZ.takeIf { gyroscope != null }
        )
    }

    private fun selectStation(stationRecord: StationRecord) {
        state = state.copy(
            selectedStationId = stationRecord.id,
            selectedStationMunicipality = stationRecord.municipality,
            notice = "Viendo ${stationRecord.displayName} · ${stationRecord.municipality}."
        )
    }

    private fun scanStation() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener { barcode ->
                val id = barcode.rawValue?.let { raw -> runCatching {
                    URI(raw).takeIf { it.scheme == "sismored" && it.host == "station" }?.path?.substringAfterLast('/')
                }.getOrNull() }
                if (id.isNullOrBlank()) {
                    state = state.copy(notice = "El QR no contiene un identificador de estación SismoRed.")
                    return@addOnSuccessListener
                }
                io.execute {
                    val found = runCatching { runBlocking { supabase.findStation(id) } }.getOrNull()
                    runOnUiThread {
                        state = if (found == null) state.copy(notice = "No se encontró esa estación en Supabase.") else state.copy(
                            selectedStationId = found.id,
                            selectedStationMunicipality = found.municipality,
                            notice = "Viendo ${found.displayName} · ${found.municipality}, ${found.region}."
                        )
                    }
                }
            }
            .addOnFailureListener { state = state.copy(notice = "No se pudo leer el QR: ${it.localizedMessage ?: "intenta de nuevo"}") }
    }

    private fun signOut() {
        if (captureMode != CaptureMode.IDLE) stopMonitoring()
        networkGroup.stop()
        io.execute {
            supabase.stopObserving()
            runCatching { runBlocking { supabase.signOut() } }
            account = null
            station = null
            observingCloud = false
            runOnUiThread { state = state.copy(account = null, station = null, receiverLatitude = null, receiverLongitude = null, locationBusy = false, authBusy = false, notice = null, communityAlerts = emptyList(), registeredStations = emptyList(), stationStatuses = emptyList(), selectedStationId = null, selectedStationMunicipality = null) }
        }
    }

    private fun saveStationLocation() = withLocationPermission {
        val currentStation = station
        if (currentStation == null) {
            state = state.copy(notice = "Esta cuenta todavía no tiene una estación registrada.")
            return@withLocationPermission
        }
        readCurrentLocation { location ->
            if (location == null) {
                state = state.copy(locationBusy = false, notice = "No se obtuvo una ubicación. Activa el GPS e inténtalo de nuevo.")
                return@readCurrentLocation
            }
            io.execute {
                val result = runCatching {
                    runBlocking { supabase.updateStationLocation(currentStation, location.latitude, location.longitude) }
                }
                runOnUiThread {
                    result.onSuccess { updated ->
                        station = updated
                        state = state.copy(
                            station = updated,
                            registeredStations = state.registeredStations.map { if (it.id == updated.id) updated else it },
                            locationBusy = false,
                            notice = "Ubicación de ${updated.displayName} guardada y compartida con la red."
                        )
                    }.onFailure { error ->
                        state = state.copy(locationBusy = false, notice = readableError(error, "No se pudo guardar la ubicación de la estación."))
                    }
                }
            }
        }
    }

    private fun showReceiverLocation() = withLocationPermission {
        readCurrentLocation { location ->
            state = if (location == null) state.copy(
                locationBusy = false,
                notice = "No se obtuvo una ubicación. Activa el GPS e inténtalo de nuevo."
            ) else state.copy(
                receiverLatitude = location.latitude,
                receiverLongitude = location.longitude,
                locationBusy = false,
                notice = "Tu ubicación se muestra solo en este dispositivo."
            )
        }
    }

    private fun withLocationPermission(action: () -> Unit) {
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            action()
        } else {
            pendingLocationAction = action
            locationPermissionLauncher.launch(arrayOf(
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun readCurrentLocation(onResult: (Location?) -> Unit) {
        state = state.copy(locationBusy = true, notice = null)
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasFinePermission = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val enabledProviders = locationManager.getProviders(true)
        val preferredProviders = if (hasFinePermission) {
            listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER)
        }
        val provider = preferredProviders.firstOrNull(enabledProviders::contains)
        if (provider == null) {
            state = state.copy(locationBusy = false, notice = "Activa la ubicación del dispositivo para continuar.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, mainExecutor) { location ->
                onResult(location ?: runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull())
            }
        } else {
            lateinit var listener: LocationListener
            val locationTimeout = Runnable {
                locationManager.removeUpdates(listener)
                onResult(runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull())
            }
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    mainHandler.removeCallbacks(locationTimeout)
                    onResult(location)
                }
                override fun onProviderEnabled(providerName: String) = Unit
                override fun onProviderDisabled(providerName: String) = Unit
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(providerName: String?, status: Int, extras: android.os.Bundle?) = Unit
            }
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            mainHandler.postDelayed(locationTimeout, 15_000L)
        }
    }

    private fun startMonitoring() {
        if (account?.role != "station") {
            state = state.copy(notice = "El perfil receptor solo recibe lecturas y alertas.")
            return
        }
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
        registerMotionSensors()
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
        registerMotionSensors()
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
        motionSensorsRegistered = false
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
        if (activityResumed && account?.role == "station") startStationHeartbeat()
    }

    override fun onSensorChanged(event: SensorEvent) {
        recordSensorCheck(event)
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                for (axis in 0..2) gravity[axis] = event.values[axis]
                gravityInitialized = true
                return
            }
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val current = quaternionFromRotationVector(event.values)
                val reference = rotationReference ?: run {
                    val now = SystemClock.elapsedRealtime()
                    if (rotationReferenceStartedAt == 0L) rotationReferenceStartedAt = now
                    if (now - rotationReferenceStartedAt >= ORIENTATION_SETTLE_MS) {
                        current.copyOf().also {
                            rotationReference = it
                            account?.takeIf { profile -> profile.role == "station" }?.let { profile -> saveRotationReference(profile.userId, it) }
                        }
                    } else null
                }
                val relative = reference?.let { relativeQuaternion(it, current) }
                state = state.copy(
                    rotationX = relative?.let { quaternionEulerX(it) } ?: 0f,
                    rotationY = relative?.let { quaternionEulerY(it) } ?: 0f,
                    rotationZ = relative?.let { quaternionEulerZ(it) } ?: 0f
                )
                return
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
                return
            }
        }
        if (event.sensor.type != activeSensor?.type) return
        val now = SystemClock.elapsedRealtime()
        val axes = FloatArray(3)
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && linearSensor == null) {
            if (gravitySensor == null) {
                if (!gravityInitialized) {
                    for (i in 0..2) gravity[i] = event.values[i]
                    gravityInitialized = true
                }
                for (i in 0..2) gravity[i] = 0.8f * gravity[i] + 0.2f * event.values[i]
            }
            for (i in 0..2) {
                axes[i] = event.values[i] - gravity[i]
            }
        } else {
            for (i in 0..2) axes[i] = event.values[i]
        }
        val magnitude = sqrt(axes[0] * axes[0] + axes[1] * axes[1] + axes[2] * axes[2])

        if (captureMode == CaptureMode.IDLE) {
            state = state.copy(x = axes[0], y = axes[1], z = axes[2], amplitude = magnitude)
            return
        }
        if (captureMode == CaptureMode.CALIBRATING) {
            calibrationSum += magnitude
            calibrationSamples++
            val progress = ((now - calibrationStartedAt).toFloat() / CALIBRATION_DURATION_MS).coerceIn(0f, 1f)
            state = state.copy(calibrationProgress = progress, x = axes[0], y = axes[1], z = axes[2], amplitude = magnitude)
            if (now - calibrationStartedAt >= CALIBRATION_DURATION_MS) {
                baseline = if (calibrationSamples > 0) (calibrationSum / calibrationSamples).toFloat() else 0f
                account?.takeIf { it.role == "station" }?.let { profile ->
                    getSharedPreferences("station_calibration", MODE_PRIVATE).edit().putFloat(profile.userId, baseline).apply()
                }
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
        val currentAccount = account
        val currentStation = station
        if (currentAccount?.role == "station" && currentStation != null) {
            if (System.currentTimeMillis() - lastCloudStatusAtMs >= 1_000L) {
                lastCloudStatusAtMs = System.currentTimeMillis()
                publishStationStatus(amplitude, anomalyLatched)
            }
            if (anomalyMarker) supabase.publishAnomaly(currentStation, amplitude)
        }
        val message = if (anomalyLatched) "Variación de vibración detectada. Registro experimental." else "Vibración dentro de la referencia."
        state = state.copy(
            elapsedMs = elapsed, x = axes[0], y = axes[1], z = axes[2],
            amplitude = amplitude, threshold = threshold, anomalyCount = anomalyCount,
            chartPoints = (state.chartPoints + point).takeLast(CHART_WINDOW_SIZE),
            notice = message
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensor?.let { sensorCheckData[it.type]?.accuracy = accuracy }
    }

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

    private fun loadRotationReference(userId: String): FloatArray? {
        val preferences = getSharedPreferences("station_rotation_quaternion", MODE_PRIVATE)
        if (!preferences.contains("${userId}_x")) return null
        return floatArrayOf(
            preferences.getFloat("${userId}_x", 0f),
            preferences.getFloat("${userId}_y", 0f),
            preferences.getFloat("${userId}_z", 0f),
            preferences.getFloat("${userId}_w", 1f)
        )
    }

    private fun saveRotationReference(userId: String, reference: FloatArray) {
        getSharedPreferences("station_rotation_quaternion", MODE_PRIVATE).edit()
            .putFloat("${userId}_x", reference[0])
            .putFloat("${userId}_y", reference[1])
            .putFloat("${userId}_z", reference[2])
            .putFloat("${userId}_w", reference[3])
            .apply()
    }

    private fun quaternionFromRotationVector(vector: FloatArray): FloatArray {
        val x = vector.getOrElse(0) { 0f }
        val y = vector.getOrElse(1) { 0f }
        val z = vector.getOrElse(2) { 0f }
        val w = vector.getOrElse(3) { sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f)) }
        val norm = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(0.0001f)
        return floatArrayOf(x / norm, y / norm, z / norm, w / norm)
    }

    private fun relativeQuaternion(reference: FloatArray, current: FloatArray): FloatArray {
        val x = -reference[0]; val y = -reference[1]; val z = -reference[2]; val w = reference[3]
        val cx = current[0]; val cy = current[1]; val cz = current[2]; val cw = current[3]
        val result = floatArrayOf(
            w * cx + x * cw + y * cz - z * cy,
            w * cy - x * cz + y * cw + z * cx,
            w * cz + x * cy - y * cx + z * cw,
            w * cw - x * cx - y * cy - z * cz
        )
        if (result[3] < 0f) for (i in result.indices) result[i] = -result[i]
        // Android reports device-to-world rotation; the preview needs the inverse pose.
        return floatArrayOf(-result[0], -result[1], -result[2], result[3])
    }

    private fun quaternionEulerX(q: FloatArray): Float = Math.toDegrees(
        atan2(2f * (q[3] * q[0] + q[1] * q[2]), 1f - 2f * (q[0] * q[0] + q[1] * q[1])).toDouble()
    ).toFloat()

    private fun quaternionEulerY(q: FloatArray): Float = Math.toDegrees(
        asin((2f * (q[3] * q[1] - q[2] * q[0])).coerceIn(-1f, 1f).toDouble())
    ).toFloat()

    private fun quaternionEulerZ(q: FloatArray): Float = Math.toDegrees(
        atan2(2f * (q[3] * q[2] + q[0] * q[1]), 1f - 2f * (q[1] * q[1] + q[2] * q[2])).toDouble()
    ).toFloat()

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
        private const val ORIENTATION_SETTLE_MS = 1_500L
        private const val SENSOR_CHECK_DURATION_MS = 8_000L
        private const val SENSOR_CHECK_REST_MS = 3_000L
        private const val SENSOR_CHECK_UI_INTERVAL_MS = 250L
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
