package com.sismored.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sismored.R
import com.composables.icons.lucide.R as LucideR

private val PanelShape = RoundedCornerShape(5.dp)

@Composable
internal fun MonitorScreen(state: AppUiState, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("MEDICIÓN", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 1.1.sp)
                Text("Monitoreo", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Navy)
            }
            if (state.capture == CaptureMode.RECORDING) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiveDot()
                    Text("EN CURSO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Teal, letterSpacing = 0.7.sp)
                }
            }
        }

        if (state.networkRole != null || state.remoteReadings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = PanelShape,
                border = BorderStroke(1.dp, Rule)
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LucideIcon(LucideR.drawable.lucide_ic_radio_tower, Teal, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Red comunitaria", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.weight(1f))
                        Text("${state.connectedDevices} ENLACE${if (state.connectedDevices == 1) "" else "S"}", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal)
                    }
                    HorizontalDivider(color = Rule)
                    if (state.remoteReadings.isEmpty()) Text("Esperando lecturas de la estación…", fontSize = 11.sp, color = Muted)
                    state.remoteReadings.take(3).forEach { reading ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(if (reading.anomaly) Color(0xFFC58736) else Teal)
                            Text(reading.name, color = Navy, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("${formatNumber(reading.amplitude)} m/s²${if (reading.anomaly) " · variación" else ""}", color = if (reading.anomaly) Color(0xFF8A5100) else Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        when (state.capture) {
            CaptureMode.CALIBRATING -> {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = PanelShape, border = BorderStroke(1.dp, Rule)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text("Calibrando en reposo", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Navy)
                        Text("Deja el teléfono quieto sobre una superficie estable.", fontSize = 12.sp, color = Muted)
                        LinearProgressIndicator(progress = { state.calibrationProgress }, modifier = Modifier.fillMaxWidth(), color = Teal, trackColor = Color(0xFFE5EAE6))
                        Text("${(state.calibrationProgress * 100).toInt()}% · 5 segundos", fontSize = 11.sp, color = Teal)
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(48.dp), shape = PanelShape) { Text("Cancelar calibración") }
            }
            CaptureMode.RECORDING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("Tiempo activo", formatDuration(state.elapsedMs), Modifier.weight(1f))
                    MetricCard("Variaciones", state.anomalyCount.toString(), Modifier.weight(1f))
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = PanelShape, border = BorderStroke(1.dp, Rule)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("ACELERACIÓN POR EJE", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.8.sp)
                                Text("Señal del dispositivo", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                            }
                            LucideIcon(LucideR.drawable.lucide_ic_chart_line, Teal, Modifier.size(20.dp))
                        }
                        VibrationChart(state.chartPoints)
                        AxisLegend()
                        HorizontalDivider(color = Rule)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            AxisValue("X", state.x, Navy, Modifier.weight(1f))
                            AxisValue("Y", state.y, Teal, Modifier.weight(1f))
                            AxisValue("Z", state.z, Aqua, Modifier.weight(1f))
                        }
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (state.anomalyCount > 0) Color(0xFFFFF4E7) else Color(0xFFEAF2EE)),
                    shape = PanelShape
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LucideIcon(
                            if (state.anomalyCount > 0) LucideR.drawable.lucide_ic_triangle_alert else LucideR.drawable.lucide_ic_activity,
                            if (state.anomalyCount > 0) Color(0xFF9A5A12) else Teal,
                            Modifier.size(19.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(state.notice ?: "Vibración dentro de la referencia.", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (state.anomalyCount > 0) Color(0xFF8A5100) else Teal)
                            Text("Amplitud ${formatNumber(state.amplitude)} m/s² · umbral ${formatNumber(state.threshold)} m/s²", fontSize = 10.sp, color = Muted)
                        }
                    }
                }
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(50.dp), shape = PanelShape, colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                    LucideIcon(LucideR.drawable.lucide_ic_square, Color.White, Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Detener y guardar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Text("Registro experimental del movimiento del teléfono; no es una alerta sísmica certificada.", fontSize = 10.sp, color = Muted)
            }
            CaptureMode.IDLE -> {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = PanelShape, border = BorderStroke(1.dp, Rule)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        LucideIcon(LucideR.drawable.lucide_ic_activity, Teal, Modifier.size(22.dp))
                        Text("Estación en reposo", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                        Text("Inicia una medición para registrar aceleración en los tres ejes.", fontSize = 12.sp, color = Muted)
                        if (state.isSaving) Text("Guardando en el dispositivo…", fontSize = 11.sp, color = Teal)
                        state.notice?.let { Text(it, fontSize = 11.sp, color = Teal) }
                    }
                }
                Button(onClick = onStart, enabled = state.hasAccelerometer && !state.isSaving, modifier = Modifier.fillMaxWidth().height(50.dp), shape = PanelShape, colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
                    Text(if (state.calibrated) "Iniciar medición" else "Calibrar e iniciar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

