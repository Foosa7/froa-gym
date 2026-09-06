package dev.froa.xiaoimu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.froa.xiaoimu.ConnState
import dev.froa.xiaoimu.ImuHistory
import dev.froa.xiaoimu.ImuRepository
import dev.froa.xiaoimu.ImuSample
import dev.froa.xiaoimu.SourceKind


@Composable
fun Dashboard(
    repo: ImuRepository,
    state: ConnState,
    sample: ImuSample?,
    stats: ImuRepository.Stats,
    @Suppress("UNUSED_PARAMETER") tick: Long,   // repaint trigger; charts read history directly
    source: SourceKind,
    onSourceChange: (SourceKind) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val accelScale = remember { MutableFloatHolder(2f) }
    val gyroScale = remember { MutableFloatHolder(250f) }

    Column(
        // API 35 draws edge-to-edge by default, so without this the title
        // sits underneath the status bar clock.
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            Text("‹  Home", color = Accent, fontSize = 14.sp,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.height(4.dp))
        }
        Text("XIAO nRF52840 Sense", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("LSM6DS3TR-C · 6-axis IMU", color = TextLo, fontSize = 13.sp)

        ConnectionCard(state, stats, source, onSourceChange, onConnect, onDisconnect)

        if (sample != null) {
            ReadingsCard(sample)
            ChartCard(
                title = "Accelerometer",
                subtitle = "g · autoscaling",
                legend = listOf("X" to AxisX, "Y" to AxisY, "Z" to AxisZ),
            ) {
                StripChart(
                    history = repo.history,
                    channels = listOf(
                        ImuHistory.Channel.AX to AxisX,
                        ImuHistory.Channel.AY to AxisY,
                        ImuHistory.Channel.AZ to AxisZ,
                    ),
                    minRange = 1.2f,
                    unitLabel = "g",
                    scaleState = accelScale,
                )
            }
            ChartCard(
                title = "Gyroscope",
                subtitle = "°/s · autoscaling",
                legend = listOf("X" to AxisX, "Y" to AxisY, "Z" to AxisZ),
            ) {
                StripChart(
                    history = repo.history,
                    channels = listOf(
                        ImuHistory.Channel.GX to AxisX,
                        ImuHistory.Channel.GY to AxisY,
                        ImuHistory.Channel.GZ to AxisZ,
                    ),
                    minRange = 30f,
                    unitLabel = "°/s",
                    scaleState = gyroScale,
                )
            }
            OrientationCard(sample)
        } else {
            Box(
                Modifier.fillMaxWidth().height(180.dp)
                    .background(CardBgColor, RoundedCornerShape(14.dp))
                    .border(1.dp, Edge, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("No data yet — connect to start streaming", color = TextLo, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ConnState,
    stats: ImuRepository.Stats,
    source: SourceKind,
    onSourceChange: (SourceKind) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state is ConnState.Connected
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (connected) Good else Warn, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                when (state) {
                    is ConnState.Idle -> "Disconnected"
                    is ConnState.Scanning -> "Scanning…"
                    is ConnState.Connecting -> "Connecting…"
                    is ConnState.Connected -> "Connected · ${state.detail}"
                    is ConnState.Failed -> state.reason
                },
                color = TextHi, fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("Bluetooth", source == SourceKind.BLE) { onSourceChange(SourceKind.BLE) }
            SourceChip("Bridge (TCP)", source == SourceKind.BRIDGE) { onSourceChange(SourceKind.BRIDGE) }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = if (connected) onDisconnect else onConnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (connected) Color(0xFF3A2226) else Color(0xFF1E3A2C),
                contentColor = if (connected) Warn else Good,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (connected) "Disconnect" else "Connect") }

        if (stats.received > 0) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("rate", "%.1f Hz".format(stats.rateHz))
                Stat("packets", "${stats.received}")
                Stat("dropped", "${stats.dropped}", if (stats.dropped > 0) Warn else TextHi)
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0xFF222836),
            labelColor = TextLo,
            selectedContainerColor = Color(0xFF23364A),
            selectedLabelColor = Color(0xFF7CC4FF),
        ),
    )
}

@Composable
private fun ReadingsCard(s: ImuSample) {
    CardBox {
        Text("Readings", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        AxisRow("accel", "g", s.ax, s.ay, s.az, "%+.3f")
        Spacer(Modifier.height(6.dp))
        AxisRow("gyro", "°/s", s.gx, s.gy, s.gz, "%+.1f")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("|accel|", "%.3f g".format(s.accelMagnitude))
            Stat("temp", "%.1f °C".format(s.tempC))
            Stat("seq", "${s.seq}")
        }
    }
}

@Composable
private fun AxisRow(name: String, unit: String, x: Float, y: Float, z: Float, fmt: String) {
    Column {
        Text("$name  ($unit)", color = TextLo, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AxisValue("X", x, AxisX, fmt, Modifier.weight(1f))
            AxisValue("Y", y, AxisY, fmt, Modifier.weight(1f))
            AxisValue("Z", z, AxisZ, fmt, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AxisValue(label: String, v: Float, c: Color, fmt: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(c, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(label, color = TextLo, fontSize = 11.sp)
        }
        Text(
            fmt.format(v), color = TextHi, fontSize = 17.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OrientationCard(s: ImuSample) {
    CardBox {
        Text("Orientation", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            "From the gravity vector — only valid while the board is still",
            color = TextLo, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TiltIndicator(s.ax, s.ay, Modifier.size(120.dp))
            Spacer(Modifier.width(20.dp))
            Column {
                Stat("pitch", "%+.1f°".format(s.pitchDeg))
                Spacer(Modifier.height(10.dp))
                Stat("roll", "%+.1f°".format(s.rollDeg))
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    legend: List<Pair<String, Color>>,
    content: @Composable () -> Unit,
) {
    CardBox {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextLo, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                legend.forEach { (l, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(c, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(l, color = TextLo, fontSize = 11.sp)
                    }
                }
            }
        }
        content()
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
private fun CardBox(content: @Composable ColumnScopeShim.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(CardBgColor, RoundedCornerShape(14.dp))
            .border(1.dp, Edge, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) { ColumnScopeShim.content() }
}

/** Lets CardBox take a plain lambda without leaking ColumnScope into callers. */
object ColumnScopeShim
