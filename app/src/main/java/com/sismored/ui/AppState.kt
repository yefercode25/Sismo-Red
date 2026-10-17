package com.sismored.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sismored.R
import com.sismored.data.MeasurementPoint
import com.sismored.data.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val Navy = Color(0xFF18324A)
internal val Teal = Color(0xFF087F73)
internal val Aqua = Color(0xFF56AFA5)
internal val CanvasBackground = Color(0xFFF3F4F1)
internal val Muted = Color(0xFF68757D)
internal val Rule = Color(0xFFD9DEDA)
internal val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold)
)

internal enum class AppScreen { HOME, MONITOR, HISTORY }
internal enum class CaptureMode { IDLE, CALIBRATING, RECORDING }

internal data class RemoteReading(
    val id: String,
    val name: String,
    val elapsedMs: Long,
    val amplitude: Float,
    val anomaly: Boolean,
    val updatedAtMs: Long
)

internal data class AppUiState(
    val screen: AppScreen = AppScreen.HOME,
    val capture: CaptureMode = CaptureMode.IDLE,
    val deviceName: String = "Teléfono Android",
    val batteryPercent: Int? = null,
    val hasAccelerometer: Boolean = false,
    val hasGyroscope: Boolean = false,
    val hasLinearSensor: Boolean = false,
    val calibrated: Boolean = false,
    val calibrationProgress: Float = 0f,
    val baseline: Float = 0f,
    val elapsedMs: Long = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val amplitude: Float = 0f,
    val threshold: Float = 0f,
    val anomalyCount: Int = 0,
    val chartPoints: List<MeasurementPoint> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: Long? = null,
    val selectedPoints: List<MeasurementPoint> = emptyList(),
    val isSaving: Boolean = false,
    val networkStatus: String = "Sin conexión",
    val networkRole: String? = null,
    val hostAddress: String? = null,
    val connectedDevices: Int = 0,
    val hostAddressInput: String = "",
    val remoteReadings: List<RemoteReading> = emptyList(),
    val notice: String? = null
)

@Composable
internal fun SismoTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Navy,
        onPrimary = Color.White,
        secondary = Teal,
        onSecondary = Color.White,
        tertiary = Aqua,
        background = CanvasBackground,
        surface = Color.White,
        onSurface = Navy,
        onSurfaceVariant = Muted
    )
    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Poppins)) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

internal fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

internal fun formatNumber(value: Float): String = "%.2f".format(Locale.US, value)

internal fun formatDate(timeMs: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale("es", "CO")).format(Date(timeMs))

internal fun csvTimestamp(timeMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timeMs))

