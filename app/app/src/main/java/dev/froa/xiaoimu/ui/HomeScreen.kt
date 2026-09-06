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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.froa.xiaoimu.ConnState
import dev.froa.xiaoimu.gym.WeekBucket
import dev.froa.xiaoimu.gym.WorkoutStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    store: WorkoutStore,
    connState: ConnState,
    onOpenCategory: (String) -> Unit,
    onOpenAllMachines: () -> Unit,
    onOpenSensor: () -> Unit,
    refreshKey: Int,
) {
    // Recomputed whenever we come back to this screen, so a set logged on the
    // tracking screen shows up in the chart immediately.
    val weeks = remember(refreshKey) { store.weeklyActivity(weeks = 8) }
    val thisWeek = weeks.lastOrNull()
    var facing by remember { mutableStateOf(BodyFacing.FRONT) }
    var selected by remember { mutableStateOf<BodyRegion?>(null) }

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Workouts", color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    thisWeek?.let {
                        if (it.workouts == 0) "Nothing logged this week yet"
                        else "${it.workouts} this week · ${it.sets} set${if (it.sets == 1) "" else "s"} · ${it.reps} reps"
                    } ?: "",
                    color = TextLo, fontSize = 13.sp,
                )
            }
            Box(
                Modifier.size(10.dp).background(
                    if (connState is ConnState.Connected) Good else Warn, CircleShape
                )
            )
        }

        // --- weekly activity ------------------------------------------------
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Weekly activity", color = TextHi, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text("Days trained per week, last 8 weeks", color = TextLo, fontSize = 11.sp)
                }
                Text(
                    "${weeks.sumOf { it.workouts }} total",
                    color = TextLo, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            WeeklyChart(weeks)
        }

        // --- body map -------------------------------------------------------
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Train a muscle group", color = TextHi, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text("Tap a body part to see its machines", color = TextLo, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FacingChip("Front", facing == BodyFacing.FRONT) { facing = BodyFacing.FRONT }
                    FacingChip("Back", facing == BodyFacing.BACK) { facing = BodyFacing.BACK }
                }
            }
            Spacer(Modifier.height(8.dp))
            // Fixed height with a figure-shaped aspect ratio: letting it fill
            // the width made it tall enough to push everything else off screen.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BodyMap(
                    facing = facing,
                    selected = selected,
                    onRegionTap = { region ->
                        selected = region
                        onOpenCategory(region.category)
                    },
                    modifier = Modifier.height(300.dp).aspectRatio(0.62f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                val labels = if (facing == BodyFacing.FRONT) {
                    listOf(BodyRegion.CHEST, BodyRegion.SHOULDERS, BodyRegion.ARMS,
                        BodyRegion.CORE, BodyRegion.LEGS)
                } else {
                    listOf(BodyRegion.BACK, BodyRegion.SHOULDERS, BodyRegion.ARMS, BodyRegion.LEGS)
                }
                labels.forEach { r ->
                    Text(
                        r.label, color = Accent, fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { selected = r; onOpenCategory(r.category) }
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                    )
                }
            }
        }

        // --- shortcuts -------------------------------------------------------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(
                title = "All machines",
                subtitle = "Search the full list",
                modifier = Modifier.weight(1f),
                onClick = onOpenAllMachines,
            )
            ActionTile(
                title = "Raw sensor",
                subtitle = if (connState is ConnState.Connected) "Live stream" else "Not connected",
                accent = connState is ConnState.Connected,
                modifier = Modifier.weight(1f),
                onClick = onOpenSensor,
            )
        }

        // --- recent ----------------------------------------------------------
        val recent = remember(refreshKey) { store.allSets().take(5) }
        if (recent.isNotEmpty()) {
            Card {
                Text("Recent sets", color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                recent.forEach { rec ->
                    val when_ = remember(rec.finishedAt) {
                        SimpleDateFormat("EEE HH:mm", Locale.getDefault())
                            .format(Date(rec.finishedAt))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rec.machineName, color = TextHi, fontSize = 13.sp)
                        Text("${rec.repsFinal} × ${rec.weightKg.toInt()} kg",
                            color = TextLo, fontSize = 13.sp)
                        Text(when_, color = TextLo, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Bar chart of days trained per week. */
@Composable
private fun WeeklyChart(weeks: List<WeekBucket>) {
    val peak = maxOf(weeks.maxOfOrNull { it.workouts } ?: 0, 3)

    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = weeks.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val barW = slot * 0.55f
            val gridTop = 4f
            val baseline = size.height

            // Horizontal guides at each whole workout-count up to the peak.
            for (i in 0..peak) {
                val y = baseline - (i / peak.toFloat()) * (baseline - gridTop)
                drawLine(Edge.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1f)
            }

            weeks.forEachIndexed { i, wk ->
                val frac = wk.workouts / peak.toFloat()
                val h = frac * (baseline - gridTop)
                val x = i * slot + (slot - barW) / 2f
                // An empty week still gets a stub so the axis reads continuously.
                val drawH = if (wk.workouts == 0) 3f else h
                drawRoundRect(
                    color = when {
                        wk.workouts == 0 -> Edge
                        wk.isCurrent -> Accent
                        else -> Good
                    },
                    topLeft = Offset(x, baseline - drawH),
                    size = Size(barW, drawH),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            weeks.forEachIndexed { i, wk ->
                Text(
                    if (wk.isCurrent) "now" else "-${weeks.size - 1 - i}w",
                    color = if (wk.isCurrent) Accent else TextLo,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun FacingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(
            if (selected) Color(0xFF23364A) else Color(0xFF222836), RoundedCornerShape(8.dp)
        ).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(label, color = if (selected) Accent else TextLo, fontSize = 12.sp) }
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .background(CardBgColor, RoundedCornerShape(14.dp))
            .border(1.dp, if (accent) Good.copy(alpha = 0.5f) else Edge, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Text(title, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, color = if (accent) Good else TextLo, fontSize = 11.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CardBgColor, RoundedCornerShape(14.dp))
            .border(1.dp, Edge, RoundedCornerShape(14.dp)).padding(14.dp),
    ) { content() }
}
