// kotlin
package com.rohit.one.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Color as AndroidColor

@Composable
fun ColorPicker(
    initialHue: Float = 0f,
    initialSat: Float = 1f,
    initialValue: Float = 1f,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
    hueBarWidth: Dp = 28.dp
) {
    var hue by remember { mutableStateOf(initialHue.coerceIn(0f, 360f)) }
    var sat by remember { mutableStateOf(initialSat.coerceIn(0f, 1f)) }
    var value by remember { mutableStateOf(initialValue.coerceIn(0f, 1f)) }

    LaunchedEffect(hue, sat, value) {
        val intArgb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
        onColorChanged(Color(intArgb))
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .padding(8.dp)
        ) {
            val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    .pointerInput(hue) {
                        detectTapGestures { offset ->
                            val x = offset.x.coerceIn(0f, w)
                            val y = offset.y.coerceIn(0f, h)
                            sat = (x / w).coerceIn(0f, 1f)
                            value = (1f - (y / h)).coerceIn(0f, 1f)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                )
            }
        }

        Column(
            modifier = Modifier
                .width(hueBarWidth)
                .fillMaxHeight()
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val hueStops = List(7) { i ->
                Color(AndroidColor.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)))
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(hueBarWidth)
                    .background(Brush.verticalGradient(hueStops))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val totalH = size.height.toFloat().coerceAtLeast(1f)
                                val localY = offset.y.coerceIn(0f, totalH)
                                val ratio = (localY / totalH).coerceIn(0f, 1f)
                                hue = (ratio * 360f).coerceIn(0f, 360f)
                            },
                            onDrag = { change, _ ->
                                val totalH = size.height.toFloat().coerceAtLeast(1f)
                                val localY = change.position.y.coerceIn(0f, totalH)
                                val ratio = (localY / totalH).coerceIn(0f, 1f)
                                hue = (ratio * 360f).coerceIn(0f, 360f)
                                change.consumePositionChange()
                            }
                        )
                    }
            )
        }
    }
}
