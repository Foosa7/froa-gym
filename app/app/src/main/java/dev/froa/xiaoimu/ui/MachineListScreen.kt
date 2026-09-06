package dev.froa.xiaoimu.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.froa.xiaoimu.ConnState
import dev.froa.xiaoimu.gym.Machine
import dev.froa.xiaoimu.gym.WorkoutStore

@Composable
fun MachineListScreen(
    store: WorkoutStore,
    connState: ConnState,
    /** Pre-filter to one muscle group, e.g. arriving from the body map. */
    categoryFilter: String? = null,
    onOpenMachine: (Machine) -> Unit,
    onOpenSensor: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    // The category filter is independent of the text query, so typing narrows
    // within the group rather than escaping it.
    var category by remember(categoryFilter) { mutableStateOf(categoryFilter) }
    // Bumped after adding or deleting so the list re-reads from the store.
    var version by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }

    val machines = remember(version, query, category) {
        store.allMachines()
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .filter { it.matches(query) }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                if (onBack != null) {
                    Text("‹  Home", color = Accent, fontSize = 14.sp,
                        modifier = Modifier.clickable { onBack() })
                    Spacer(Modifier.height(2.dp))
                }
                Text(category ?: "Machines", color = TextHi, fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (category != null) "${machines.size} machine${if (machines.size == 1) "" else "s"} for ${category!!.lowercase()}"
                    else "Pick a machine to track sets and reps",
                    color = TextLo, fontSize = 13.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onOpenSensor() }) {
                Box(
                    Modifier.size(8.dp).background(
                        if (connState is ConnState.Connected) Good else Warn, CircleShape
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text("Sensor", color = Accent, fontSize = 13.sp)
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search machines…", color = TextLo) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CardBgColor,
                unfocusedContainerColor = CardBgColor,
                focusedTextColor = TextHi,
                unfocusedTextColor = TextHi,
                cursorColor = Accent,
                focusedIndicatorColor = Accent,
                unfocusedIndicatorColor = Edge,
            ),
        )

        if (category != null) {
            Box(
                Modifier.background(Color(0xFF23364A), RoundedCornerShape(8.dp))
                    .clickable { category = null }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text("$category  ✕", color = Accent, fontSize = 13.sp) }
        }

        Box(
            Modifier.fillMaxWidth().background(Color(0xFF1E3A2C), RoundedCornerShape(12.dp))
                .clickable { showAdd = true }.padding(14.dp),
        ) { Text("+  Add custom machine", color = Good, fontSize = 15.sp) }

        if (machines.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("Nothing matches" + (category?.let { " in $it" } ?: "") +
                        (if (query.isNotBlank()) " for \"$query\"" else ""),
                    color = TextLo, fontSize = 14.sp)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(machines, key = { it.id }) { m ->
                MachineRow(
                    machine = m,
                    setCount = store.setsFor(m.id).size,
                    onClick = { onOpenMachine(m) },
                    onDelete = if (m.custom) {
                        { store.removeCustom(m); version++ }
                    } else null,
                )
            }
        }
    }

    if (showAdd) {
        AddMachineDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, cat ->
                val created = store.addCustom(name, cat)
                version++
                showAdd = false
                onOpenMachine(created)
            },
        )
    }
}

@Composable
private fun MachineRow(
    machine: Machine,
    setCount: Int,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(CardBgColor, RoundedCornerShape(12.dp))
            .border(1.dp, Edge, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(machine.name, color = TextHi, fontSize = 16.sp)
            Row {
                Text(machine.category, color = TextLo, fontSize = 12.sp)
                if (setCount > 0) {
                    Text("  ·  $setCount set${if (setCount == 1) "" else "s"} logged",
                        color = Good, fontSize = 12.sp)
                }
            }
        }
        if (onDelete != null) {
            Text("Delete", color = Warn, fontSize = 13.sp,
                modifier = Modifier.clickable { onDelete() }.padding(start = 12.dp))
        }
    }
}

@Composable
private fun AddMachineDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBgColor,
        title = { Text("Add custom machine", color = TextHi) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name", color = TextLo) },
                    colors = dialogFieldColors(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = category, onValueChange = { category = it }, singleLine = true,
                    label = { Text("Category (optional)", color = TextLo) },
                    colors = dialogFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onAdd(name, category) },
                enabled = name.isNotBlank(),
            ) { Text("Add", color = if (name.isNotBlank()) Good else TextLo) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextLo) }
        },
    )
}

@Composable
private fun dialogFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF222836),
    unfocusedContainerColor = Color(0xFF222836),
    focusedTextColor = TextHi,
    unfocusedTextColor = TextHi,
    cursorColor = Accent,
    focusedIndicatorColor = Accent,
    unfocusedIndicatorColor = Edge,
)
