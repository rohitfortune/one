package com.rohit.one.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun ColorPicker(
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
    initialHue: Float = 0f,
    initialSat: Float = 1f,
    initialValue: Float = 1f,
    hueBarWidth: Dp = 36.dp
) {
    var hue by remember { mutableStateOf(initialHue.coerceIn(0f, 360f)) }
    var sat by remember { mutableStateOf(initialSat.coerceIn(0f, 1f)) }
    var value by remember { mutableStateOf(initialValue.coerceAtLeast(0f).coerceAtMost(1f)) }

    LaunchedEffect(hue, sat, value) {
        val intArgb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
        onColorChanged(Color(intArgb))
    }

    // Wrap in BoxWithConstraints using the provided modifier so we can measure available width/height
    BoxWithConstraints(modifier = modifier) {
        val availW = maxWidth
        val availH = maxHeight

        val horizontalPadding = 16.dp
        val verticalSpacing = 12.dp

        // Hue bar height (previously hueBarWidth param) is used as the horizontal bar height now
        val barHeight = hueBarWidth

        // Compute squareSize so square + spacer + hue bar vertically fit into availH,
        // but the square should fill the available width (minus horizontal padding)
        // Make the SV area fill full available width (inside padding) and use a clamped height
        val availableWidthForSV = (availW - horizontalPadding * 2f).coerceAtLeast(56.dp)
        val maxSVHeight = (availH - verticalSpacing - barHeight - 16.dp).coerceAtLeast(56.dp)

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {

            // SV rectangle: fill full width and use clamped height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(maxSVHeight)
                    .background(Color.Transparent)
            ) {
                val wPx = with(LocalDensity.current) { availableWidthForSV.toPx() }
                val hPx = with(LocalDensity.current) { maxSVHeight.toPx() }
                val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
                        .pointerInput(hue) {
                            detectTapGestures { offset ->
                                val x = offset.x.coerceIn(0f, wPx)
                                val y = offset.y.coerceIn(0f, hPx)
                                sat = (x / wPx).coerceIn(0f, 1f)
                                value = (1f - (y / hPx)).coerceIn(0f, 1f)
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

            Spacer(modifier = Modifier.height(verticalSpacing))

            // Horizontal hue bar below the square that fills full width (within padding)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .border(width = 1.dp, color = Color.Black.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            List(7) { i -> Color(AndroidColor.HSVToColor(floatArrayOf(i * 60f, 1f, 1f))) }
                        )
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val totalW = size.width.toFloat().coerceAtLeast(1f)
                            val localX = offset.x.coerceIn(0f, totalW)
                            hue = ((localX / totalW) * 360f).coerceIn(0f, 360f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val totalW = size.width.toFloat().coerceAtLeast(1f)
                                val localX = offset.x.coerceIn(0f, totalW)
                                hue = ((localX / totalW) * 360f).coerceIn(0f, 360f)
                            },
                            onDrag = { change, _ ->
                                val totalW = size.width.toFloat().coerceAtLeast(1f)
                                val localX = change.position.x.coerceIn(0f, totalW)
                                hue = ((localX / totalW) * 360f).coerceIn(0f, 360f)
                                change.consumePositionChange()
                            }
                        )
                    }
            )
        }
    }
}
