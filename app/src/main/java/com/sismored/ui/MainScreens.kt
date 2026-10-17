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
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.sismored.data.StationRecord
import com.sismored.data.StationStatus
import com.sismored.data.CommunityAlert
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.delay

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
    onLeaveGroup: () -> Unit,
    configured: Boolean,
    onAuth: (Boolean, String, String, String, String, String) -> Unit,
    onScanStation: () -> Unit,
    onSelectStation: (StationRecord) -> Unit,
    onShowAllStations: () -> Unit,
    onSignOut: () -> Unit,
    onSaveStationLocation: () -> Unit,
    onRequestReceiverLocation: () -> Unit,
    onRunSensorCheck: () -> Unit
) {
    if (!state.authReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Conectando…", color = Muted) }
        return
    }
    if (state.account == null) {
        AuthScreen(state.authBusy, state.notice, configured, onAuth)
        return
    }
    Scaffold(
        containerColor = CanvasBackground,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                val items = buildList {
                    add(Triple(AppScreen.HOME, "Inicio", LucideR.drawable.lucide_ic_house))
                    if (state.account.role == "station") add(Triple(AppScreen.MONITOR, "Medición", LucideR.drawable.lucide_ic_radio_tower))
                    add(Triple(AppScreen.MAP, "Mapa", LucideR.drawable.lucide_ic_map))
                    add(Triple(AppScreen.HISTORY, "Historial", LucideR.drawable.lucide_ic_history))
                }
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
                AppScreen.HOME -> HomeScreen(state, onStart, onHostGroup, onJoinGroup, onLeaveGroup, onScanStation, onSelectStation, onShowAllStations, onSignOut, onRunSensorCheck, Modifier.padding(padding))
                AppScreen.MONITOR -> MonitorScreen(state, onStart, onStop, Modifier.padding(padding))
                AppScreen.MAP -> LocationMapScreen(state, onSaveStationLocation, onRequestReceiverLocation, Modifier.padding(padding))
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
    onScanStation: () -> Unit,
    onSelectStation: (StationRecord) -> Unit,
    onShowAllStations: () -> Unit,
    onSignOut: () -> Unit,
    onRunSensorCheck: () -> Unit,
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
            TextButtonText("SALIR", onSignOut)
        }

        val isStation = state.account?.role == "station"
        if (isStation) {
            HomeIdentityCard(true, state.account?.municipality.orEmpty())
            DeviceCard(state)
            SensorDiagnosticsCard(state, onRunSensorCheck)
        } else {
            ReceiverVisualization(state)
        }
        CommunityNetworkCard(state, onScanStation, onSelectStation, onShowAllStations)
        if (isStation) NetworkGroupCard(state, onHostGroup, onJoinGroup, onLeaveGroup)

        if (isStation) Card(
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

        if (isStation) Button(
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
        Text(if (isStation) "Prototipo experimental · no confirma ni predice terremotos." else "Receptor de lecturas y alertas comunitarias.", fontSize = 10.sp, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        state.notice?.let { NoticeCard(it) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SensorDiagnosticsCard(state: AppUiState, onRun: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = PanelShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Rule)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LucideIcon(LucideR.drawable.lucide_ic_activity, Teal, Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("DIAGNÓSTICO DE SENSORES", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.7.sp)
                    Text("Comprueba su respuesta", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Navy)
                }
            }
            Text(
                if (state.sensorCheckRunning) state.sensorCheckPhase else "Revisa que entreguen datos y respondan al movimiento. La disponibilidad por sí sola no garantiza una medición fiable.",
                fontSize = 11.sp, color = Muted, lineHeight = 16.sp
            )
            if (state.sensorCheckRunning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveDot()
                    Text("Prueba en curso · ${((state.sensorCheckRemainingMs + 999) / 1000).coerceAtLeast(0)} s", fontSize = 11.sp, color = Teal, fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.sensorCheckRows.isNotEmpty()) {
                HorizontalDivider(color = Rule)
                state.sensorCheckRows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(when {
                            !row.available || row.result.startsWith("Sin respuesta") || row.result.startsWith("No instalado") -> Color(0xFFC58736)
                            row.result.startsWith("Útil") -> Teal
                            else -> Aqua
                        })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Navy, modifier = Modifier.weight(1f))
                                Text(row.result, fontSize = 10.sp, color = if (row.result.startsWith("Útil")) Teal else Muted, fontWeight = FontWeight.Medium)
                            }
                            Text(row.purpose, fontSize = 9.sp, color = Muted)
                            if (row.available && row.samples > 0) {
                                Text("${row.samples} lecturas · ${row.accuracy} · ${row.reading}", fontSize = 9.sp, color = Muted)
                            }
                        }
                    }
                }
                Text("Precisión reportada por Android; la prueba no sustituye una calibración ni valida un sismógrafo certificado.", fontSize = 9.sp, color = Muted, lineHeight = 13.sp)
            }
            OutlinedButton(
                onClick = onRun,
                enabled = !state.sensorCheckRunning && state.capture == CaptureMode.IDLE,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                shape = PanelShape
            ) {
                Text(if (state.sensorCheckRunning) "Comprobando sensores…" else if (state.sensorCheckRows.isEmpty()) "Comprobar sensores · 8 s" else "Repetir comprobación · 8 s", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            if (state.capture != CaptureMode.IDLE && !state.sensorCheckRunning) {
                Text("Detén la medición para ejecutar esta prueba.", fontSize = 10.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun HomeIdentityCard(isStation: Boolean, municipality: String) {
    Box(
        Modifier.fillMaxWidth().height(174.dp)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 7.dp))
            .background(Ink)
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (isStation) "Estación fija · ${municipality.ifBlank { "Guavio" }}" else "Red comunitaria del Guavio", fontSize = 11.sp, color = SignalMint)
            Text(if (isStation) "Una estación,\nun pulso compartido." else "El territorio\ntambién escucha.", fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(if (isStation) "Medición y coordinación comunitaria." else "Señales cercanas, compartidas en tiempo real.", fontSize = 11.sp, color = Color.White.copy(alpha = 0.78f))
        }
    }
}

@Composable
private fun TextButtonText(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clickable(onClick = onClick).padding(6.dp), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 0.5.sp)
}

@Composable
private fun CommunityNetworkCard(state: AppUiState, onScanStation: () -> Unit, onSelectStation: (StationRecord) -> Unit, onShowAllStations: () -> Unit) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            currentTime = System.currentTimeMillis()
        }
    }
    val station = state.station
    val municipality = state.selectedStationMunicipality
    val visibleStatuses = state.stationStatuses.filter { state.selectedStationId?.let { id -> it.stationId == id } ?: (municipality == null || it.municipality.equals(municipality, ignoreCase = true)) }
    val visibleAlerts = state.communityAlerts
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = PanelShape, border = androidx.compose.foundation.BorderStroke(1.dp, Rule)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("RED SUPABASE · TIEMPO REAL", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Teal, letterSpacing = 0.8.sp)
            if (state.account?.role == "station") {
                Text(station?.displayName ?: "Estación SismoRed", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${station?.municipality ?: state.account.municipality} · ${station?.region ?: state.account.region}", color = Muted, fontSize = 12.sp)
                station?.let {
                    val qr = remember(it.id) { createStationQr(it.id) }
                    qr?.let { bitmap -> Image(bitmap.asImageBitmap(), "QR de estación ${it.displayName}", Modifier.size(164.dp).align(Alignment.CenterHorizontally)) }
                    Text("Código de estación  ${it.id.take(8).uppercase()}", Modifier.align(Alignment.CenterHorizontally), color = Navy, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                } ?: Text("El código aparecerá al ejecutar el esquema de Supabase.", fontSize = 11.sp, color = Muted)
                Text("Calibra el teléfono fijo e inicia la medición para publicar lecturas y eventos.", fontSize = 11.sp, color = Muted)
            } else {
                Text("Receptor de estaciones del Guavio", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Elige una estación disponible o escanea su QR. Se marca activa cuando publicó un dato en los últimos 15 segundos.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                OutlinedButton(onClick = onScanStation, modifier = Modifier.fillMaxWidth().height(43.dp), shape = PanelShape) { Text("Escanear QR de estación", fontSize = 12.sp) }
                if (state.selectedStationId != null) {
                    OutlinedButton(
                        onClick = onShowAllStations,
                        modifier = Modifier.fillMaxWidth().height(38.dp), shape = PanelShape
                    ) { Text("Mostrar todas las estaciones", fontSize = 11.sp) }
                }
                HorizontalDivider(color = Rule)
                Text("ESTACIONES REGISTRADAS", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.7.sp)
                if (state.registeredStations.isEmpty()) {
                    Text("Aún no hay estaciones registradas en la red.", color = Muted, fontSize = 11.sp)
                } else {
                    state.registeredStations.sortedByDescending { registered ->
                        state.stationStatuses.firstOrNull { it.stationId == registered.id }?.let { currentTime - parseCloudTime(it.updatedAt) < 15_000L } == true
                    }.forEach { registered ->
                        val status = state.stationStatuses.firstOrNull { it.stationId == registered.id }
                        val fresh = status != null && currentTime - parseCloudTime(status.updatedAt) < 15_000L
                        val selected = state.selectedStationId == registered.id
                        Surface(
                            color = if (selected) Color(0xFFEAF5F2) else Color(0xFFF8F9F7),
                            shape = PanelShape,
                            modifier = Modifier.fillMaxWidth().clickable { onSelectStation(registered) }
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(if (fresh) Teal else Muted)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(registered.displayName, color = Navy, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text("${registered.municipality} · ${registered.region}", color = Muted, fontSize = 9.sp)
                                }
                                Text(if (fresh) "ACTIVA" else if (status == null) "SIN DATOS" else "INACTIVA", color = if (fresh) Teal else Muted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                                if (selected) Text("VIENDO", color = Teal, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (visibleStatuses.isNotEmpty()) {
                HorizontalDivider(color = Rule)
                Text("ÚLTIMAS LECTURAS", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 0.7.sp)
                visibleStatuses.sortedByDescending { it.updatedAt }.take(3).forEach { StatusLine(it, currentTime) }
            }
            if (visibleAlerts.isNotEmpty()) {
                HorizontalDivider(color = Rule)
                visibleAlerts.filter { System.currentTimeMillis() - parseCloudTime(it.detectedAt) < 15 * 60_000L }.take(3).forEach { alert ->
                    Surface(color = Color(0xFFFFF4DD), shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Coincidencia detectada · ${alert.stationCount} estaciones", color = Color(0xFF76520A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Consenso regional · cerca de ${alert.municipality} · ${formatDate(parseCloudTime(alert.detectedAt))}", color = Color(0xFF76520A), fontSize = 10.sp)
                        }
                    }
                }
            }
            state.selectedStationMunicipality?.let { Text("Municipio de la estación seleccionada: $it", color = Muted, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun StatusLine(status: StationStatus, currentTime: Long) {
    val isFresh = currentTime - parseCloudTime(status.updatedAt) < 15_000L
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (status.anomaly && isFresh) Color(0xFFC58736) else if (isFresh) Teal else Muted)
        Text("${status.stationName} · ${status.municipality}${if (isFresh) "" else " · último dato"}", color = Navy, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text("${formatNumber(status.amplitude)} m/s²", color = Muted, fontSize = 10.sp)
    }
}

private fun createStationQr(id: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode("sismored://station/$id", BarcodeFormat.QR_CODE, 512, 512)
    Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) AndroidColor.BLACK else AndroidColor.WHITE }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
    }
}.getOrNull()

internal fun parseCloudTime(value: String): Long = runCatching {
    val normalized = Regex("([+-]\\d{2})$").replace(value.trim().replaceFirst(' ', 'T')) { "${it.groupValues[1]}:00" }
    runCatching { java.time.Instant.parse(normalized) }
        .getOrElse { java.time.OffsetDateTime.parse(normalized).toInstant() }
        .toEpochMilli()
}.getOrDefault(0L)

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
                SensorTag("ROT. 3D", state.hasRotationVector)
                SensorTag("MAG", state.hasMagnetometer)
                SensorTag("GRAV.", state.hasGravitySensor)
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

