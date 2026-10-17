package com.sismored.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sismored.data.MeasurementPoint
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun VibrationChart(points: List<MeasurementPoint>) {
    val shown = if (points.size <= 100) points else List(100) { index -> points[index * points.lastIndex / 99] }
    val axisColors = listOf(Navy, Teal, Aqua)
    Canvas(Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFF7F8F6)).padding(8.dp)) {
        val chartHeight = size.height
        val chartWidth = size.width
        val centerY = chartHeight / 2f
        val maxAbs = max(2.5f, (shown.maxOfOrNull { max(max(abs(it.x), abs(it.y)), abs(it.z)) } ?: 0f) * 1.2f)
        for (i in 0..4) {
            val y = chartHeight * i / 4f
            drawLine(Color(0xFFE7EDF1), Offset(0f, y), Offset(chartWidth, y), strokeWidth = 1.dp.toPx())
        }
        if (shown.size < 2) return@Canvas
        axisColors.forEachIndexed { axis, color ->
            val path = Path()
            shown.forEachIndexed { index, point ->
                val value = when (axis) { 0 -> point.x; 1 -> point.y; else -> point.z }
                val x = chartWidth * index / shown.lastIndex.toFloat()
                val y = centerY - value / maxAbs * chartHeight * 0.43f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
internal fun AxisLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem("X", Navy)
        LegendItem("Y", Teal)
        LegendItem("Z", Aqua)
        Spacer(Modifier.weight(1f))
        Text("m/s²", fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
internal fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(5.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Rule)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), fontSize = 9.sp, color = Muted, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, letterSpacing = 0.4.sp)
            Text(value, fontSize = 18.sp, color = Navy, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AxisValue(axis: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFFF3F5F2), shape = RoundedCornerShape(4.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("Eje $axis", color = color, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text("${formatNumber(value)} m/s²", color = Navy, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
internal fun NoticeCard(message: String) {
    Surface(color = Color(0xFFEAF2EE), shape = RoundedCornerShape(5.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(13.dp), color = Teal, fontSize = 12.sp)
    }
}

