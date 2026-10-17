package com.sismored.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sismored.data.StationRecord
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun LocationMapScreen(
    state: AppUiState,
    onSaveStationLocation: () -> Unit,
    onRequestReceiverLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isStation = state.account?.role == "station"
    val stations = buildList {
        state.station?.let(::add)
        state.registeredStations.filter { it.id != state.station?.id }.forEach(::add)
    }
    val positionedStations = stations.filter { it.latitude != null && it.longitude != null }
    val mapMarkers = remember(positionedStations, state.receiverLatitude, state.receiverLongitude) {
        JSONArray().apply {
            positionedStations.forEach { put(it.toMapJson()) }
            if (!isStation && state.receiverLatitude != null && state.receiverLongitude != null) {
                put(JSONObject().apply {
                    put("id", "receiver-location")
                    put("name", "Mi ubicación")
                    put("municipality", "Este dispositivo")
                    put("latitude", state.receiverLatitude)
                    put("longitude", state.receiverLongitude)
                    put("kind", "receiver")
                })
            }
        }.toString()
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mapa de estaciones", color = Navy, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (isStation) "Ubica este punto fijo para compartirlo con los receptores del Guavio."
            else "Consulta las estaciones con ubicación y encuentra su cercanía.",
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp
        )

        if (isStation) {
            Button(
                onClick = onSaveStationLocation,
                enabled = !state.locationBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                if (state.locationBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else LucideIcon(com.composables.icons.lucide.R.drawable.lucide_ic_map_pin_check, Color.White, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.locationBusy) "Obteniendo ubicación…" else if (state.station?.latitude != null) "Actualizar ubicación de estación" else "Guardar ubicación de estación", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(
                onClick = onRequestReceiverLocation,
                enabled = !state.locationBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(5.dp)
            ) {
                if (state.locationBusy) CircularProgressIndicator(Modifier.size(18.dp), color = Teal, strokeWidth = 2.dp)
                else LucideIcon(com.composables.icons.lucide.R.drawable.lucide_ic_locate_fixed, Teal, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.locationBusy) "Buscando ubicación…" else if (state.receiverLatitude != null) "Actualizar mi ubicación" else "Mostrar mi ubicación", fontSize = 12.sp)
            }
        }

        var mapReady by remember { mutableStateOf(false) }
        var mapFailed by remember { mutableStateOf(false) }
        val latestMarkers by rememberUpdatedState(mapMarkers)
        val context = LocalContext.current
        val shape = RoundedCornerShape(5.dp)
        Box(Modifier.weight(1f).fillMaxWidth().clip(shape).background(Color(0xFFE6ECE8))) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    @SuppressLint("SetJavaScriptEnabled")
                    object : WebView(context) {
                        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
                            super.onSizeChanged(width, height, oldWidth, oldHeight)
                            if (width > 0 && height > 0) {
                                post {
                                    evaluateJavascript("window.invalidateSismoMap && window.invalidateSismoMap()", null)
                                }
                            }
                        }
                    }.apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.userAgentString = "${settings.userAgentString} SismoRed/1.0"
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript("typeof window.renderSismoMap === 'function'") { result ->
                                    if (result == "true") {
                                        mapFailed = false
                                        mapReady = true
                                        view.evaluateJavascript("window.renderSismoMap($latestMarkers)", null)
                                        view.postDelayed({
                                            view.evaluateJavascript("window.invalidateSismoMap && window.invalidateSismoMap()", null)
                                        }, 250)
                                    } else {
                                        mapFailed = true
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (request.isForMainFrame) mapFailed = true
                            }
                        }
                        val html = context.assets.open("sismo_map.html").bufferedReader().use { it.readText() }
                        loadDataWithBaseURL("https://appassets.androidplatform.net/assets/", html, "text/html", "UTF-8", null)
                    }
                },
                update = { view ->
                    view.evaluateJavascript("if (window.renderSismoMap) window.renderSismoMap($mapMarkers)", null)
                }
            )
            if (!mapReady && !mapFailed) {
                Surface(Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(5.dp)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Teal, strokeWidth = 2.dp)
                        Text("Cargando mapa…", color = Navy, fontSize = 11.sp)
                    }
                }
            }
            if (mapFailed) {
                Surface(Modifier.align(Alignment.Center).padding(16.dp), color = Color.White, shape = RoundedCornerShape(5.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("No se pudo cargar el mapa", color = Navy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Revisa la conexión a internet e inténtalo de nuevo.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            MapLegendDot(Teal, "Estaciones · ${positionedStations.size}")
            if (!isStation && state.receiverLatitude != null) MapLegendDot(Color(0xFF315FA6), "Tu ubicación")
        }
        Text(
            if (isStation) "La ubicación guardada se comparte con los usuarios autenticados de SismoRed."
            else "Tu ubicación no se guarda en SismoRed. El proveedor del mapa recibe solicitudes de teselas para el área visible.",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
        state.notice?.let { Text(it, color = Teal, fontSize = 11.sp) }
    }
}

private fun StationRecord.toMapJson() = JSONObject().apply {
    put("id", id)
    put("name", displayName)
    put("municipality", municipality)
    put("latitude", latitude)
    put("longitude", longitude)
    put("kind", "station")
}

@Composable
private fun MapLegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Text(text, color = Muted, fontSize = 10.sp)
    }
}
