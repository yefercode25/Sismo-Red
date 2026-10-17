package com.sismored.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.sismored.data.SessionSummary
import com.sismored.R
import com.composables.icons.lucide.R as LucideR

private val PanelShape = RoundedCornerShape(5.dp)

@Composable
internal fun HistoryScreen(
    state: AppUiState,
    onSelectSession: (Long) -> Unit,
    onExport: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("ARCHIVO LOCAL", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 1.1.sp)
            Text("Historial", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Navy)
            Text("Sesiones guardadas en este teléfono.", fontSize = 12.sp, color = Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIcon(LucideR.drawable.lucide_ic_database, Muted, Modifier.size(14.dp))
            Text("${state.sessions.size} SESIÓN${if (state.sessions.size == 1) "" else "ES"}", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.7.sp)
        }
        if (state.sessions.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = PanelShape, border = BorderStroke(1.dp, Rule)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LucideIcon(LucideR.drawable.lucide_ic_history, Teal, Modifier.size(21.dp))
                    Text("Todavía no hay sesiones", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Navy)
                    Text("Al detener una medición se guardará aquí y podrás consultarla sin conexión.", fontSize = 12.sp, color = Muted)
                }
            }
        }
        state.sessions.forEachIndexed { index, session ->
            val selected = state.selectedSessionId == session.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelectSession(session.id) },
                colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEAF2EE) else Color.White),
                shape = PanelShape,
                border = BorderStroke(1.dp, if (selected) Teal.copy(alpha = 0.45f) else Rule)
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${(state.sessions.size - index).toString().padStart(2, '0')}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Teal)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(formatDate(session.startedAtMs), fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 12.sp)
                        Text("${formatDuration(session.durationMs)} · ${session.anomalyCount} variaciones", color = Muted, fontSize = 10.sp)
                    }
                    LucideIcon(LucideR.drawable.lucide_ic_chevron_right, Muted, Modifier.size(17.dp))
                }
            }
            AnimatedVisibility(visible = selected) {
                SessionDetails(state, session, onExport)
            }
        }
        state.notice?.let { NoticeCard(it) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SessionDetails(state: AppUiState, session: SessionSummary, onExport: (SessionSummary) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = PanelShape, border = BorderStroke(1.dp, Rule)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("RESUMEN DE SESIÓN", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 0.8.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Duración", formatDuration(session.durationMs), Modifier.weight(1f))
                MetricCard("Variaciones", session.anomalyCount.toString(), Modifier.weight(1f))
            }
            Text("Pico ${formatNumber(session.maxAmplitude)} m/s² · referencia ${formatNumber(session.baseline)} m/s²", fontSize = 10.sp, color = Muted)
            if (state.selectedPoints.isNotEmpty()) {
                HorizontalDivider(color = Rule)
                VibrationChart(state.selectedPoints)
                AxisLegend()
                val events = state.selectedPoints.filter { it.anomaly }
                if (events.isNotEmpty()) {
                    Text("VARIACIONES REGISTRADAS", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 0.8.sp)
                    events.forEach { event ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDate(event.capturedAtMs), Modifier.weight(1f), fontSize = 10.sp, color = Muted)
                            Text("${formatNumber(event.amplitude)} m/s²", fontSize = 10.sp, color = Navy, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                OutlinedButton(onClick = { onExport(session) }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = PanelShape) {
                    LucideIcon(LucideR.drawable.lucide_ic_arrow_down_to_line, Navy, Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Exportar CSV", color = Navy, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            } else {
                Text("Cargando mediciones…", fontSize = 11.sp, color = Muted)
            }
        }
    }
}

