package com.sismored.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sismored.data.StationStatus
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

private enum class ReceiverView { SIGNAL, PHONE }

@Composable
internal fun ReceiverVisualization(state: AppUiState) {
    val status = state.stationStatuses.firstOrNull { it.stationId == state.selectedStationId }
        ?: state.stationStatuses.maxByOrNull { parseCloudTime(it.updatedAt) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var view by remember { mutableStateOf(ReceiverView.SIGNAL) }
    var samples by remember(status?.stationId) { mutableStateOf(emptyList<StationStatus>()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            currentTime = System.currentTimeMillis()
        }
    }
    LaunchedEffect(status?.stationId, status?.updatedAt) {
        status?.let { samples = (samples + it).takeLast(40) }
    }

    val isLive = status != null && currentTime - parseCloudTime(status.updatedAt) < 15_000L
    val signalColor = if (status?.anomaly == true && isLive) Color(0xFFC58736) else Teal
    val animatedRotationX by animateFloatAsState(status?.rotationX?.coerceIn(-80f, 80f) ?: 0f, tween(180), label = "station-rotation-x")
    val animatedRotationY by animateFloatAsState(status?.rotationY?.coerceIn(-80f, 80f) ?: 0f, tween(180), label = "station-rotation-y")
    val animatedRotationZ by animateFloatAsState(status?.rotationZ ?: 0f, tween(180), label = "station-rotation-z")

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Rule)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Lectura de la red", color = Navy, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(status?.let { "${it.stationName} · ${it.municipality}" } ?: "Esperando una estación", color = Muted, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(if (isLive) Teal else Muted)
                    Text(if (isLive) "EN VIVO" else if (status == null) "SIN SEÑAL" else "ÚLTIMO DATO", color = if (isLive) Teal else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0xFFF1F4F1)).padding(3.dp)) {
                ReceiverView.entries.forEach { option ->
                    val selected = view == option
                    Surface(
                        onClick = { view = option },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(5.dp),
                        color = if (selected) Color.White else Color.Transparent,
                        shadowElevation = if (selected) 1.dp else 0.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(if (option == ReceiverView.SIGNAL) "Señal" else "Teléfono", fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) Teal else Muted)
                        }
                    }
                }
            }

            if (view == ReceiverView.SIGNAL) {
                SignalPlot(samples, signalColor)
                AxisReadouts(status, rotations = false)
            } else {
                PhoneMotionView(status, signalColor, animatedRotationX, animatedRotationY, animatedRotationZ)
                AxisReadouts(status, rotations = true)
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("AMPLITUD RECIBIDA", fontSize = 8.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                    Text(status?.let { "${formatNumber(it.amplitude)} m/s²" } ?: "— m/s²", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = if (status?.anomaly == true && isLive) Color(0xFF8A5100) else Navy)
                }
                Text(if (status == null) "Sin lecturas todavía" else if (status.accelerationX != null) "Aceleración y\nrotación X/Y/Z" else "La estación aún\nenvía intensidad", fontSize = 9.sp, lineHeight = 12.sp, color = Muted)
            }
            Text("Giro relativo a la posición de montaje; el rumbo absoluto depende del magnetómetro.", fontSize = 9.sp, lineHeight = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun SignalPlot(samples: List<StationStatus>, color: Color) {
    Box(Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF2F6F4))) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp)) {
            for (line in 0..3) {
                val y = size.height * line / 3f
                drawLine(Color(0xFFDDE6E1), Offset.Zero.copy(y = y), Offset(size.width, y), 1.dp.toPx())
            }
            if (samples.isEmpty()) return@Canvas
            val hasAxes = samples.any { it.accelerationX != null && it.accelerationY != null && it.accelerationZ != null }
            val axes = if (hasAxes) listOf(
                samples.map { it.accelerationX ?: 0f }, samples.map { it.accelerationY ?: 0f }, samples.map { it.accelerationZ ?: 0f }
            ) else listOf(samples.map { it.amplitude })
            val axisColors = if (hasAxes) listOf(Navy, Teal, Aqua) else listOf(color)
            val maximum = max(0.08f, axes.flatten().maxOfOrNull(::abs) ?: 0f) * 1.25f
            axes.forEachIndexed { axisIndex, values ->
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = if (values.size == 1) size.width / 2f else size.width * index / values.lastIndex.toFloat()
                    val y = size.height / 2f - value / maximum * size.height * 0.43f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (values.size > 1) drawPath(path, axisColors[axisIndex], style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                else drawCircle(axisColors[axisIndex], radius = 4.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f - values.first() / maximum * size.height * 0.43f))
            }
        }
        Text(if (samples.isEmpty()) "La señal aparecerá al recibir una lectura" else "Aceleración · m/s²", Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp), fontSize = 9.sp, color = Muted)
        if (samples.size > 1) Text("${samples.size} muestras", Modifier.align(Alignment.BottomEnd).padding(end = 11.dp, bottom = 6.dp), fontSize = 8.sp, color = Muted)
    }
}

@Composable
private fun AxisReadouts(status: StationStatus?, rotations: Boolean) {
    val values = if (rotations) listOf(status?.rotationX, status?.rotationY, status?.rotationZ) else listOf(status?.accelerationX, status?.accelerationY, status?.accelerationZ)
    val unit = if (rotations) "°" else "m/s²"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("X" to Navy, "Y" to Teal, "Z" to Aqua).forEachIndexed { index, (axis, axisColor) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Color(0xFFF3F5F2)).padding(horizontal = 8.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (rotations) "ROT. $axis" else "EJE $axis", fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = axisColor)
                Text(values[index]?.let { "${formatNumber(it)} $unit" } ?: "— $unit", fontSize = 8.sp, color = Navy, maxLines = 1)
            }
        }
    }
    if (rotations && status?.gyroX != null) {
        Text("Velocidad angular: ${formatNumber(status.gyroX)} · ${formatNumber(status.gyroY ?: 0f)} · ${formatNumber(status.gyroZ ?: 0f)} rad/s", fontSize = 8.sp, color = Muted)
    }
}

@Composable
private fun PhoneMotionView(status: StationStatus?, color: Color, rotationX: Float, rotationY: Float, rotationZ: Float) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(Color(0xFFF2F6F4)).padding(top = 10.dp, bottom = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier.width(96.dp).height(150.dp).graphicsLayer {
                    this.rotationX = rotationX
                    this.rotationY = rotationY
                    this.rotationZ = rotationZ
                    translationX = ((status?.accelerationY ?: 0f) * 8.dp.toPx()).coerceIn(-14.dp.toPx(), 14.dp.toPx())
                    translationY = (-(status?.accelerationX ?: 0f) * 8.dp.toPx()).coerceIn(-14.dp.toPx(), 14.dp.toPx())
                    cameraDistance = 28f * density
                }
            ) {
                val phoneShape = RoundedCornerShape(19.dp)
                Box(Modifier.fillMaxSize().offset(x = 5.dp, y = 6.dp).clip(phoneShape).background(Color(0xFF0D2029)))
                Box(Modifier.fillMaxSize().offset(x = 3.dp, y = 4.dp).clip(phoneShape).background(Color(0xFF49616A)))
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = phoneShape,
                    color = Color(0xFF142B39),
                    border = BorderStroke(1.dp, Color(0xFF69818A)),
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFFEAF1ED)).padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.width(25.dp).height(4.dp).clip(CircleShape).background(Color(0xFF142B39)))
                        Text("SismoRed", fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(status?.stationName ?: "Esperando señal", fontSize = 7.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(color = Color.White, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)) {
                                Text(status?.let { "${formatNumber(it.amplitude)} m/s²" } ?: "— m/s²", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Navy, maxLines = 1)
                                Text("RX ${status?.rotationX?.let(::formatNumber) ?: "—"}° · RY ${status?.rotationY?.let(::formatNumber) ?: "—"}° · RZ ${status?.rotationZ?.let(::formatNumber) ?: "—"}°", fontSize = 6.sp, color = color, maxLines = 1)
                            }
                        }
                        Text(if (status?.anomaly == true) "VARIACIÓN" else "RECIBIENDO", fontSize = 6.sp, fontWeight = FontWeight.SemiBold, color = color)
                    }
                }
            }
        }
        Text("Inclinación y movimiento recibidos en vivo", Modifier.padding(top = 3.dp), fontSize = 8.sp, lineHeight = 10.sp, color = Muted)
    }
}
