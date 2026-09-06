package dev.froa.xiaoimu.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.froa.xiaoimu.ConnState
import dev.froa.xiaoimu.SourceKind
import dev.froa.xiaoimu.gym.Machine
import dev.froa.xiaoimu.gym.SetRecord
import dev.froa.xiaoimu.gym.WorkoutTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun TrackingScreen(
    machine: Machine,
    tracker: WorkoutTracker,
    connState: ConnState,
    source: SourceKind,
    onSourceChange: (SourceKind) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val reps by tracker.reps.collectAsState()
    val setsDone by tracker.setsDone.collectAsState()
    val resting by tracker.resting.collectAsState()
    val moving by tracker.active.collectAsState()
    val tick by tracker.tick.collectAsState()

    var weightText by remember(machine.id) {
        mutableStateOf(if (tracker.weightKg > 0f) trimWeight(tracker.weightKg) else "")
    }
    var sensitivity by remember { mutableFloatStateOf(tracker.detector.sensitivity) }
    var recordRaw by remember { mutableStateOf(tracker.recordRaw) }

    // Snapshot the tracker's plain (non-state) counters once per frame tick.
    // Reading them directly inside a child lambda lets Compose skip the
    // subtree, which is why the buffered count sat at zero.
    @Suppress("UNUSED_EXPRESSION") tick
    val bufferedSamples = tracker.sampleCount
    val detectedReps = tracker.detectedReps
    val manualDelta = tracker.manualDelta
    val connected = connState is ConnState.Connected

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("‹  Home", color = Accent, fontSize = 14.sp,
            modifier = Modifier.clickable { onBack() })
        Text(machine.name, color = TextHi, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text(machine.category, color = TextLo, fontSize = 13.sp)

        // --- sensor link -------------------------------------------------
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (connected) Good else Warn, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (connState) {
                        is ConnState.Connected -> "Sensor connected"
                        is ConnState.Scanning -> "Scanning…"
                        is ConnState.Connecting -> "Connecting…"
                        is ConnState.Failed -> connState.reason
                        else -> "Sensor disconnected"
                    },
                    color = TextHi, fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                // Padding inside the clickable so the touch target reaches the
                // ~48dp minimum rather than being just the glyph bounds.
                Text(
                    if (connected) "Disconnect" else "Connect",
                    color = if (connected) Warn else Good, fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { if (connected) onDisconnect() else onConnect() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            if (!connected) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChip("Bluetooth", source == SourceKind.BLE) { onSourceChange(SourceKind.BLE) }
                    SmallChip("Bridge", source == SourceKind.BRIDGE) { onSourceChange(SourceKind.BRIDGE) }
                }
            }
        }

        // --- weight ------------------------------------------------------
        Panel {
            Text("Weight", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−") {
                    val v = max(0f, (weightText.toFloatOrNull() ?: 0f) - 2.5f)
                    weightText = trimWeight(v); tracker.setWeight(v)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = {
                        weightText = it.filter { c -> c.isDigit() || c == '.' }
                        tracker.setWeight(weightText.toFloatOrNull() ?: 0f)
                    },
                    singleLine = true,
                    suffix = { Text("kg", color = TextLo) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF222836),
                        unfocusedContainerColor = Color(0xFF222836),
                        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
                        cursorColor = Accent,
                        focusedIndicatorColor = Accent, unfocusedIndicatorColor = Edge,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                StepButton("+") {
                    val v = (weightText.toFloatOrNull() ?: 0f) + 2.5f
                    weightText = trimWeight(v); tracker.setWeight(v)
                }
            }
        }

        // --- live rep count ----------------------------------------------
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Set ${setsDone.size + 1}", color = TextLo, fontSize = 13.sp)
                    Text("$reps", color = TextHi, fontSize = 62.sp, fontWeight = FontWeight.Bold)
                    Text("reps", color = TextLo, fontSize = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            !connected -> "no sensor"
                            resting -> "resting"
                            moving -> "moving"
                            else -> "ready"
                        },
                        color = when {
                            !connected -> Warn
                            resting -> Accent
                            moving -> Good
                            else -> TextLo
                        },
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepButton("−") { tracker.adjustReps(-1) }
                        StepButton("+") { tracker.adjustReps(1) }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            SignalPlot(tracker, tick)
            Text(
                "Detection signal. A rep is counted on each upward crossing.",
                color = TextLo, fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))
            Text("Sensitivity  ${"%.1f".format(sensitivity)}×", color = TextLo, fontSize = 12.sp)
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it; tracker.detector.sensitivity = it },
                valueRange = 0.5f..2.5f,
                colors = SliderDefaults.colors(
                    thumbColor = Accent, activeTrackColor = Accent,
                    inactiveTrackColor = Edge,
                ),
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { tracker.finishSet() },
                    enabled = reps > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E3A2C), contentColor = Good,
                        disabledContainerColor = Color(0xFF1D222C), disabledContentColor = TextLo,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("Finish set") }
                Button(
                    onClick = { tracker.resetCurrentSet() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2F3A), contentColor = TextLo,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("Reset") }
            }
        }

        // --- data capture -------------------------------------------------
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Raw data capture", color = TextHi, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text(
                        "Records every sample of each set to CSV for offline analysis",
                        color = TextLo, fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = recordRaw,
                    onCheckedChange = { recordRaw = it; tracker.recordRaw = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Good, checkedTrackColor = Color(0xFF1E3A2C),
                        uncheckedThumbColor = TextLo, uncheckedTrackColor = Edge,
                    ),
                )
            }
            if (recordRaw) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("buffered", "$bufferedSamples")
                    Stat("detected", "$detectedReps")
                    Stat(
                        "adjusted",
                        if (manualDelta == 0) "—"
                        else (if (manualDelta > 0) "+" else "") + "$manualDelta",
                        if (manualDelta != 0) Accent else TextHi,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("detector: ${tracker.detector.name}", color = TextLo, fontSize = 11.sp)
            }
        }

        // --- completed sets ----------------------------------------------
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sets", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (setsDone.isNotEmpty()) {
                    Text("Clear", color = Warn, fontSize = 13.sp,
                        modifier = Modifier.clickable { tracker.clearHistory() })
                }
            }
            Spacer(Modifier.height(8.dp))
            if (setsDone.isEmpty()) {
                Text("No sets logged yet. Start moving and they appear here.",
                    color = TextLo, fontSize = 13.sp)
            } else {
                val total = setsDone.sumOf { (it.repsFinal * it.weightKg).toDouble() }
                setsDone.forEachIndexed { i, rec -> SetRow(setsDone.size - i, rec) }
                Spacer(Modifier.height(8.dp))
                Text("Volume: ${"%.0f".format(total)} kg · ${setsDone.sumOf { it.repsFinal }} reps",
                    color = TextLo, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SetRow(index: Int, rec: SetRecord) {
    val time = remember(rec.finishedAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(rec.finishedAt))
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Set $index", color = TextLo, fontSize = 13.sp)
        Text(
            "${rec.repsFinal} × ${trimWeight(rec.weightKg)} kg" + if (rec.manual) "  ✎" else "",
            color = TextHi, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
        )
        Text(time, color = TextLo, fontSize = 12.sp)
    }
}

/** Live plot of the detection signal with the rising-edge threshold marked. */
@Composable
private fun SignalPlot(tracker: WorkoutTracker, @Suppress("UNUSED_PARAMETER") tick: Long) {
    Box(Modifier.fillMaxWidth().height(90.dp)) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val buf = FloatArray(tracker.signal.capacity)
            val n = tracker.signal.copyInto(buf)
            val mid = size.height / 2f
            val scale = 3f      // signal is normalised, so a fixed scale is fine

            drawLine(Edge, Offset(0f, mid), Offset(size.width, mid), 1f)
            val thresholdY = mid - (tracker.detector.sensitivity.let { 0.9f / it } / scale) * mid
            drawLine(Good.copy(alpha = 0.5f), Offset(0f, thresholdY),
                Offset(size.width, thresholdY), 1.5f)

            if (n >= 2) {
                val stepX = size.width / (n - 1).toFloat()
                val path = Path()
                for (i in 0 until n) {
                    val y = mid - min(1f, max(-1f, buf[i] / scale)) * mid
                    if (i == 0) path.moveTo(i * stepX, y) else path.lineTo(i * stepX, y)
                }
                drawPath(path, Accent, style = Stroke(width = 2f, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = TextHi) {
    Column {
        Text(label, color = TextLo, fontSize = 11.sp)
        Text(value, color = color, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CardBgColor, RoundedCornerShape(14.dp))
            .border(1.dp, Edge, RoundedCornerShape(14.dp)).padding(14.dp),
    ) { content() }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(Color(0xFF262C38), RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = TextHi, fontSize = 20.sp) }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(
            if (selected) Color(0xFF23364A) else Color(0xFF222836), RoundedCornerShape(8.dp)
        ).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = if (selected) Accent else TextLo, fontSize = 13.sp) }
}

/** 40.0 -> "40", 42.5 -> "42.5" */
private fun trimWeight(v: Float): String =
    if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else "%.1f".format(v)
