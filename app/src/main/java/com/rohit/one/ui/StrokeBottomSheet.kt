package com.rohit.one.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrokeBottomSheet(
    initialColor: Color,
    initialWidthDp: Float,
    onColorSelected: (Color) -> Unit,
    onWidthSelected: (Float) -> Unit,
    onDismissRequest: () -> Unit
) {
    var color by remember { mutableStateOf(initialColor) }
    var widthDp by remember { mutableStateOf(initialWidthDp.coerceIn(1f, 40f)) }

    val hsv = remember(color) {
        FloatArray(3).also { AndroidColor.colorToHSV(color.toArgb(), it) }
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clipToBounds(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Stroke", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(8.dp))

            ColorPicker(
                initialHue = hsv[0],
                initialSat = hsv[1],
                initialValue = hsv[2],
                onColorChanged = {
                    color = it
                    onColorSelected(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("Width: ${widthDp.toInt()} dp", fontSize = 14.sp)
            Slider(
                value = widthDp,
                onValueChange = {
                    widthDp = it
                    onWidthSelected(it)
                },
                valueRange = 1f..40f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val px = with(density) { widthDp.dp.toPx() }
                    drawLine(
                        color = color,
                        start = androidx.compose.ui.geometry.Offset(16f, size.height / 2f),
                        end = androidx.compose.ui.geometry.Offset(size.width - 16f, size.height / 2f),
                        strokeWidth = px,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
