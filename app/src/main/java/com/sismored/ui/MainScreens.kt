package com.sismored.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sismored.R
import com.composables.icons.lucide.R as LucideR
import com.sismored.data.SessionSummary

private val PanelShape = RoundedCornerShape(5.dp)

@Composable
internal fun SismoApp(
    state: AppUiState,
    onNavigate: (AppScreen) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onExport: (SessionSummary) -> Unit,
    onHostGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onLeaveGroup: () -> Unit
) {
    Scaffold(
        containerColor = CanvasBackground,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                val items = listOf(
                    Triple(AppScreen.HOME, "Inicio", LucideR.drawable.lucide_ic_house),
                    Triple(AppScreen.MONITOR, "Medición", LucideR.drawable.lucide_ic_radio_tower),
                    Triple(AppScreen.HISTORY, "Historial", LucideR.drawable.lucide_ic_history)
                )
                items.forEach { (screen, label, icon) ->
                    NavigationBarItem(
                        selected = state.screen == screen,
                        onClick = { onNavigate(screen) },
                        icon = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.width(18.dp).height(2.dp).background(if (state.screen == screen) Teal else Color.Transparent))
                                Spacer(Modifier.height(4.dp))
                                LucideIcon(icon, if (state.screen == screen) Teal else Muted, Modifier.size(21.dp), label)
                            }
                        },
                        label = { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Teal,
                            selectedTextColor = Teal,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = state.screen,
            transitionSpec = { fadeIn(tween(170)) + slideInVertically(tween(170)) { it / 24 } togetherWith fadeOut(tween(100)) },
            label = "screen-transition"
        ) { screen ->
            when (screen) {
                AppScreen.HOME -> HomeScreen(state, onStart, onHostGroup, onJoinGroup, onLeaveGroup, Modifier.padding(padding))
                AppScreen.MONITOR -> MonitorScreen(state, onStart, onStop, Modifier.padding(padding))
                AppScreen.HISTORY -> HistoryScreen(state, onSelectSession, onExport, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: AppUiState,
    onStart: () -> Unit,
    onHostGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_sismored_foreground), "SismoRed Comunitaria", Modifier.size(42.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("SismoRed", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Navy)
                Text("OBSERVACIÓN COMUNITARIA", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 0.8.sp)
            }
            Text("01 / LOCAL", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.5.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("ESTACIÓN DE VIBRACIÓN", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 1.2.sp)
            Text("Una red hecha\npor la comunidad", fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, color = Navy)
            Text("Mediciones locales y coordinación entre teléfonos cercanos.", fontSize = 13.sp, color = Muted)
        }

        DeviceCard(state)
        NetworkGroupCard(state, onHostGroup, onJoinGroup, onLeaveGroup)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = PanelShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, Rule)
        ) {
            Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LucideIcon(LucideR.drawable.lucide_ic_activity, Teal, Modifier.size(17.dp))
                    Text("Puesta a punto", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                }
                HorizontalDivider(color = Rule)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatusDot(if (state.calibrated) Teal else Color(0xFFC58736))
                    Text(
                        if (state.calibrated) "Calibrado · referencia ${formatNumber(state.baseline)} m/s²" else "Calibración pendiente · 5 s en reposo",
                        fontSize = 12.sp, color = Muted
                    )
                }
            }
        }

        Button(
            onClick = onStart,
            enabled = state.hasAccelerometer && state.capture == CaptureMode.IDLE && !state.isSaving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PanelShape,
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            LucideIcon(LucideR.drawable.lucide_ic_activity, Color.White, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.calibrated) "Iniciar medición" else "Calibrar e iniciar", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text("Prototipo experimental · no confirma ni predice terremotos.", fontSize = 10.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        state.notice?.let { NoticeCard(it) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DeviceCard(state: AppUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = PanelShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Rule)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(LucideR.drawable.lucide_ic_smartphone, Teal, Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("ESTE DISPOSITIVO", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.8.sp)
                    Text(state.deviceName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                }
                LucideIcon(LucideR.drawable.lucide_ic_battery_full, Muted, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(state.batteryPercent?.let { "$it%" } ?: "—", fontSize = 11.sp, color = Muted)
            }
            HorizontalDivider(color = Rule)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SensorTag("ACC", state.hasAccelerometer)
                SensorTag("LINEAL", state.hasLinearSensor)
                SensorTag("GIRO", state.hasGyroscope)
            }
        }
    }
}

@Composable
private fun SensorTag(label: String, available: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusDot(if (available) Teal else Color(0xFFC58736))
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Muted)
    }
}

@Composable
private fun NetworkGroupCard(state: AppUiState, onHost: () -> Unit, onJoin: () -> Unit, onLeave: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = PanelShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Rule)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(LucideR.drawable.lucide_ic_wifi, Teal, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("RED LOCAL", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.8.sp)
                    Text("Red comunitaria", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                }
                Text("${state.connectedDevices} ENLACE${if (state.connectedDevices == 1) "" else "S"}", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal)
            }
            Text("Comparte amplitud y variaciones con teléfonos en la misma red Wi‑Fi.", fontSize = 12.sp, color = Muted)
            Surface(color = Color(0xFFF1F5F2), shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.networkStatus.startsWith("Buscando")) LiveDot() else StatusDot(if (state.networkRole != null) Teal else Muted)
                    Text(state.networkStatus, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (state.networkRole != null) Teal else Navy)
                }
            }
            if (state.networkRole == null) {
                Button(onClick = onHost, modifier = Modifier.fillMaxWidth().height(46.dp), shape = PanelShape, colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                    Text("Crear estación", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { if (state.networkStatus.startsWith("Buscando")) onLeave() else onJoin() },
                    modifier = Modifier.fillMaxWidth().height(44.dp), shape = PanelShape
                ) {
                    LucideIcon(if (state.networkStatus.startsWith("Buscando")) LucideR.drawable.lucide_ic_triangle else LucideR.drawable.lucide_ic_scan_line, Navy, Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.networkStatus.startsWith("Buscando")) "Cancelar búsqueda" else "Buscar estación cercana", fontSize = 12.sp)
                }
            } else {
                OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().height(44.dp), shape = PanelShape) {
                    Text("Desconectar de la red", fontSize = 12.sp)
                }
            }
            state.remoteReadings.take(3).forEach { reading ->
                HorizontalDivider(color = Rule)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (reading.anomaly) Color(0xFFC58736) else Teal)
                    Text(reading.name, color = Navy, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${formatNumber(reading.amplitude)} m/s²", color = if (reading.anomaly) Color(0xFF8A5100) else Muted, fontSize = 10.sp)
                }
            }
            Text("Enlace directo · puerto ${com.sismored.data.LocalNetworkGroup.PORT}", fontSize = 9.sp, color = Muted)
        }
    }
}

@Composable
internal fun LiveDot() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "connection-pulse")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "connection-alpha"
    )
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Teal.copy(alpha = alpha.value)))
}

@Composable
internal fun LucideIcon(resourceId: Int, tint: Color, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(painterResource(resourceId), contentDescription = contentDescription, modifier = modifier, tint = tint)
}

