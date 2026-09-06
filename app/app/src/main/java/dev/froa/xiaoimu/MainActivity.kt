package dev.froa.xiaoimu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.froa.xiaoimu.gym.Machine
import dev.froa.xiaoimu.gym.WorkoutStore
import dev.froa.xiaoimu.gym.WorkoutTracker
import dev.froa.xiaoimu.ui.Dashboard
import dev.froa.xiaoimu.ui.HomeScreen
import dev.froa.xiaoimu.ui.MachineListScreen
import dev.froa.xiaoimu.ui.TrackingScreen

/** Top-level destinations. Hand-rolled rather than pulling in navigation-compose. */
private sealed interface Screen {
    data object Home : Screen
    /** [category] pre-filters the list, e.g. arriving from the body map. */
    data class Machines(val category: String? = null) : Screen
    data class Tracking(val machine: Machine) : Screen
    data object Sensor : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var repo: ImuRepository
    private lateinit var store: WorkoutStore
    private lateinit var tracker: WorkoutTracker
    private var pendingConnect: (() -> Unit)? = null

    /** Held as a single instance so removeSampleListener can actually match it. */
    private val sampleListener: (ImuSample) -> Unit = { tracker.onSample(it) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) pendingConnect?.invoke()
            pendingConnect = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ImuRepository(applicationContext)
        store = WorkoutStore(applicationContext)
        tracker = WorkoutTracker(store)
        // Rep detection needs every packet, so it hangs off the repository
        // rather than off recomposition.
        repo.addSampleListener(sampleListener)

        setContent {
            val state by repo.state.collectAsState()
            val sample by repo.latest.collectAsState()
            val stats by repo.stats.collectAsState()
            val tick by repo.tick.collectAsState()

            var screen by remember { mutableStateOf<Screen>(Screen.Home) }
            // Bumped on returning home so the activity chart and recent list
            // pick up sets logged while away.
            var homeRefresh by remember { mutableStateOf(0) }
            var source by remember { mutableStateOf(repo.defaultSource()) }
            val bridgeHost = remember { if (repo.isEmulator()) "10.0.2.2" else "127.0.0.1" }

            val connect: () -> Unit = {
                val go = { repo.connect(source, bridgeHost, BRIDGE_PORT) }
                if (source == SourceKind.BLE) requestBlePermissions(go) else go()
            }

            val goHome = { homeRefresh++; screen = Screen.Home }

            BackHandler(enabled = screen !is Screen.Home) {
                // Tracking and Sensor were reached from a machine list, but
                // going straight home is the predictable single-step back.
                goHome()
            }

            when (val s = screen) {
                is Screen.Home -> HomeScreen(
                    store = store,
                    connState = state,
                    onOpenCategory = { cat -> screen = Screen.Machines(cat) },
                    onOpenAllMachines = { screen = Screen.Machines(null) },
                    onOpenSensor = { screen = Screen.Sensor },
                    refreshKey = homeRefresh,
                )

                is Screen.Machines -> MachineListScreen(
                    store = store,
                    connState = state,
                    categoryFilter = s.category,
                    onOpenMachine = { m -> tracker.open(m); screen = Screen.Tracking(m) },
                    onOpenSensor = { screen = Screen.Sensor },
                    onBack = goHome,
                )

                is Screen.Tracking -> TrackingScreen(
                    machine = s.machine,
                    tracker = tracker,
                    connState = state,
                    source = source,
                    onSourceChange = { source = it },
                    onConnect = connect,
                    onDisconnect = { repo.disconnect() },
                    onBack = goHome,
                )

                is Screen.Sensor -> Dashboard(
                    repo = repo,
                    state = state,
                    sample = sample,
                    stats = stats,
                    tick = tick,
                    source = source,
                    onSourceChange = { source = it },
                    onConnect = connect,
                    onDisconnect = { repo.disconnect() },
                    onBack = goHome,
                )
            }
        }
    }

    private fun requestBlePermissions(onGranted: () -> Unit) {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onGranted()
        else {
            pendingConnect = onGranted
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repo.removeSampleListener(sampleListener)
        repo.disconnect()
    }

    private companion object { const val BRIDGE_PORT = 9999 }
}
