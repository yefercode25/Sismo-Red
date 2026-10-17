package com.sismored.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.sismored.R

private val FieldPaper = Color(0xFFF7F4EC)
internal val Ink = Color(0xFF102F36)
internal val SignalMint = Color(0xFF9FD9B6)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AuthScreen(
    busy: Boolean,
    message: String?,
    configured: Boolean,
    onSubmit: (register: Boolean, role: String, email: String, password: String, name: String, municipality: String) -> Unit
) {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.let { WindowInsetsControllerCompat(it, view).isAppearanceLightStatusBars = false }
        onDispose { window?.let { WindowInsetsControllerCompat(it, view).isAppearanceLightStatusBars = true } }
    }
    var register by remember { mutableStateOf(false) }
    var role by remember { mutableStateOf("receiver") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var municipality by remember { mutableStateOf("") }
    var municipalityPickerVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    LaunchedEffect(register) { scrollState.animateScrollTo(0) }
    LaunchedEffect(message) {
        if (message != null) {
            focusManager.clearFocus()
            keyboardController?.hide()
            scrollState.animateScrollTo(0)
        }
    }
    val canSubmit = configured && !busy && email.contains('@') && password.length >= 8 &&
        (!register || name.isNotBlank() && (role != "station" || municipality.isNotBlank()))
    val submit = {
        if (canSubmit) {
            keyboardController?.hide()
            onSubmit(register, role, email.trim(), password, name.trim(), municipality.trim())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val sheetHeight = maxHeight * 0.67f
        val sheetTop = maxHeight - sheetHeight

        Column(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 54.dp, bottom = sheetHeight + 17.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("SismoRed", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Red comunitaria del Guavio", fontSize = 11.sp, color = SignalMint)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                if (register) "Una estación\nen cada punto." else "El territorio\ntambién escucha.",
                fontSize = 27.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Column(
            Modifier.fillMaxWidth().height(sheetHeight).align(Alignment.BottomCenter)
                .background(FieldPaper, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp)
                .padding(top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text(if (register) "Crear cuenta" else "Bienvenido a la red", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                if (register) "Elige cómo aportar a las mediciones del Guavio."
                else "Ingresa como estación fija o recibe señales de otras estaciones.",
                fontSize = 12.sp, lineHeight = 17.sp, color = Muted
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoleChoice("ESTACIÓN", "Mide desde un punto fijo", role == "station", Modifier.weight(1f)) { role = "station" }
                RoleChoice("RECEPTOR", "Sigue estaciones y avisos", role == "receiver", Modifier.weight(1f)) { role = "receiver" }
            }

            AnimatedContent(
                targetState = register,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { if (targetState) it / 8 else -it / 8 }) togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(100)) { if (targetState) -it / 8 else it / 8 })
                },
                label = "account-fields"
            ) { isRegister ->
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (isRegister) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true, shape = RoundedCornerShape(15.dp))
                        OutlinedButton(onClick = { municipalityPickerVisible = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                            Text(municipality.ifBlank { if (role == "station") "Elige el municipio de la estación" else "Todo Guavio o un municipio" }, color = if (municipality.isBlank()) Muted else Ink)
                        }
                    }
                    OutlinedTextField(
                        email,
                        { email = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Correo electrónico") },
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )
                    OutlinedTextField(
                        password,
                        { password = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Contraseña · mínimo 8 caracteres") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(15.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() })
                    )
                }
            }

            if (!configured) {
                Surface(color = Color(0xFFFFE9D8), shape = RoundedCornerShape(14.dp)) {
                    Text("Falta configurar la conexión a Supabase en local.properties.", Modifier.fillMaxWidth().padding(12.dp), color = Color(0xFF854226), fontSize = 11.sp)
                }
            }
            message?.let { Surface(color = Color(0xFFE4EEE4), shape = RoundedCornerShape(14.dp)) { Text(it, Modifier.fillMaxWidth().padding(12.dp), color = Ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) } }

            Button(
                onClick = submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, disabledContainerColor = Ink.copy(alpha = 0.22f))
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(if (register) "Crear cuenta" else "Entrar a la red", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            OutlinedButton(onClick = { register = !register }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Ink.copy(alpha = 0.18f))) {
                Text(if (register) "Ya tengo una cuenta" else "Quiero participar en SismoRed", color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
            Column(Modifier.fillMaxWidth().padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = Rule)
                Text("SismoRed comparte mediciones experimentales para coordinación comunitaria. No reemplaza alertas oficiales.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        Surface(
            Modifier.align(Alignment.TopEnd).offset(x = (-22).dp, y = sheetTop - 25.dp).size(50.dp),
            color = FieldPaper,
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f))
        ) {
            Image(painterResource(R.drawable.ic_sismored_foreground), "Símbolo de SismoRed", Modifier.padding(4.dp))
        }
    }

    if (municipalityPickerVisible) {
        ModalBottomSheet(
            onDismissRequest = { municipalityPickerVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = FieldPaper,
            contentColor = Ink
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Punto de la red", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text("Selecciona el municipio que quieres asociar.", fontSize = 12.sp, color = Muted)
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (role == "receiver") MunicipalityOption("Todo Guavio", municipality.isBlank()) { municipality = ""; municipalityPickerVisible = false }
                    GUAVIO_MUNICIPALITIES.forEach { town ->
                        MunicipalityOption(town, municipality == town) { municipality = town; municipalityPickerVisible = false }
                    }
                }
            }
        }
    }
}

private val GUAVIO_MUNICIPALITIES = listOf("Gachalá", "Gachetá", "Gama", "Guasca", "Guatavita", "Junín", "La Calera", "Ubalá")

@Composable
private fun RoleChoice(title: String, detail: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) Ink else Color.White, tween(180), label = "role-background")
    val corner by animateDpAsState(if (selected) 20.dp else 14.dp, tween(180), label = "role-corner")
    Surface(
        modifier.height(76.dp).clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(topStart = corner, topEnd = 14.dp, bottomEnd = corner, bottomStart = 14.dp),
        border = BorderStroke(1.dp, if (selected) Ink else Rule)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = if (selected) SignalMint else Teal, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp)
            Text(detail, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun MunicipalityOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick),
        color = if (selected) Ink else Color.White,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) Ink else Rule)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), color = if (selected) Color.White else Ink, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            if (selected) Text("SELECCIONADO", color = SignalMint, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
    }
}
