package dev.froa.xiaoimu.gym

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * On-disk store for machines, weights, completed sets and raw sample
 * recordings.
 *
 * Laid out for offline analysis rather than for the app's convenience:
 *
 *   gym/
 *     SCHEMA.md          field-by-field description, written on first run
 *     machines.json      user-defined machines
 *     weights.json       last weight used per machine
 *     sets.jsonl         one JSON object per completed set, append-only
 *     recordings/<id>.csv  raw samples for that set
 *
 * `sets.jsonl` is append-only newline-delimited JSON so a long history never
 * requires rewriting the file and a truncated write can only ever cost the
 * last record. Raw samples go to CSV because that is what a dataframe loads
 * without ceremony.
 *
 * Files live under getExternalFilesDir when available, so the whole directory
 * can be pulled with `adb pull` without root.
 */
class WorkoutStore(context: Context) {

    val rootDir: File = (context.getExternalFilesDir(null) ?: context.filesDir)
        .resolve("gym").apply { mkdirs() }

    private val machinesFile = rootDir.resolve("machines.json")
    private val weightsFile = rootDir.resolve("weights.json")
    private val setsFile = rootDir.resolve("sets.jsonl")
    val recordingsDir: File = rootDir.resolve("recordings").apply { mkdirs() }

    private val customMachines = mutableListOf<Machine>()
    private val lastWeight = mutableMapOf<String, Float>()
    private val sets = mutableListOf<SetRecord>()

    init {
        writeSchemaDoc()
        loadMachines()
        loadWeights()
        loadSets()
    }

    // ---- machines --------------------------------------------------------

    fun allMachines(): List<Machine> =
        (MachineCatalog.builtIn + customMachines).sortedBy { it.name }

    fun machineById(id: String): Machine? = allMachines().firstOrNull { it.id == id }

    fun addCustom(name: String, category: String = "Custom"): Machine {
        val clean = name.trim()
        val slug = clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val machine = Machine(
            id = "custom-$slug-${System.currentTimeMillis().toString().takeLast(5)}",
            name = clean,
            category = category.trim().ifBlank { "Custom" },
            custom = true,
        )
        customMachines += machine
        saveMachines()
        return machine
    }

    fun removeCustom(machine: Machine) {
        if (!machine.custom) return
        customMachines.removeAll { it.id == machine.id }
        saveMachines()
    }

    // ---- weights ---------------------------------------------------------

    fun weightFor(machineId: String): Float = lastWeight[machineId] ?: 0f

    fun setWeight(machineId: String, kg: Float) {
        lastWeight[machineId] = kg
        saveWeights()
    }

    // ---- sets ------------------------------------------------------------

    fun setsFor(machineId: String): List<SetRecord> =
        sets.filter { it.machineId == machineId }.sortedByDescending { it.finishedAt }

    /** Appends one set. The raw samples are written separately by SetRecorder. */
    fun appendSet(record: SetRecord) {
        sets += record
        runCatching { setsFile.appendText(record.toJson().toString() + "\n") }
    }

    /** Drops a machine's sets and their recordings. */
    fun clearSetsFor(machineId: String) {
        sets.filter { it.machineId == machineId }.forEach { rec ->
            rec.recordingFile?.let { runCatching { rootDir.resolve(it).delete() } }
        }
        sets.removeAll { it.machineId == machineId }
        rewriteSets()
    }

    /** Every recorded set, newest first. */
    fun allSets(): List<SetRecord> = sets.sortedByDescending { it.finishedAt }

    /**
     * Workout activity bucketed by ISO week, oldest first, always exactly
     * [weeks] buckets so the chart keeps a stable width even with no history.
     *
     * A "workout" is a distinct calendar day on which at least one set was
     * completed -- three sets in one evening is one workout, not three.
     */
    fun weeklyActivity(weeks: Int = 8): List<WeekBucket> {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            // Snap to the start of the current week.
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        val currentWeekStart = cal.timeInMillis

        val buckets = ArrayList<WeekBucket>(weeks)
        for (i in weeks - 1 downTo 0) {
            val start = currentWeekStart - i * WEEK_MS
            val end = start + WEEK_MS
            val inWeek = sets.filter { it.finishedAt in start until end }
            val days = inWeek.map { dayKey(it.finishedAt) }.toHashSet().size
            buckets += WeekBucket(
                startMs = start,
                workouts = days,
                sets = inWeek.size,
                reps = inWeek.sumOf { it.repsFinal },
                isCurrent = i == 0,
            )
        }
        return buckets
    }

    private fun dayKey(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun totalRecordingBytes(): Long =
        recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L

    // ---- persistence -----------------------------------------------------

    private fun loadMachines() {
        if (!machinesFile.exists()) return
        runCatching {
            val arr = JSONObject(machinesFile.readText()).optJSONArray("machines") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                customMachines += Machine(
                    o.getString("id"), o.getString("name"),
                    o.optString("category", "Custom"), custom = true,
                )
            }
        }
    }

    private fun saveMachines() {
        runCatching {
            val arr = JSONArray()
            customMachines.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name)
                        .put("category", it.category)
                )
            }
            machinesFile.writeText(
                JSONObject().put("schemaVersion", SCHEMA_VERSION).put("machines", arr).toString(2)
            )
        }
    }

    private fun loadWeights() {
        if (!weightsFile.exists()) return
        runCatching {
            val o = JSONObject(weightsFile.readText()).optJSONObject("lastWeightKg") ?: JSONObject()
            for (k in o.keys()) lastWeight[k] = o.getDouble(k).toFloat()
        }
    }

    private fun saveWeights() {
        runCatching {
            val o = JSONObject()
            lastWeight.forEach { (k, v) -> o.put(k, v.toDouble()) }
            weightsFile.writeText(
                JSONObject().put("schemaVersion", SCHEMA_VERSION).put("lastWeightKg", o).toString(2)
            )
        }
    }

    private fun loadSets() {
        if (!setsFile.exists()) return
        runCatching {
            setsFile.forEachLine { line ->
                if (line.isNotBlank()) {
                    // One malformed line must not lose the whole history.
                    runCatching { sets += SetRecord.fromJson(JSONObject(line)) }
                }
            }
        }
    }

    private fun rewriteSets() {
        runCatching {
            setsFile.writeText(sets.joinToString("") { it.toJson().toString() + "\n" })
        }
    }

    private fun writeSchemaDoc() {
        val doc = rootDir.resolve("SCHEMA.md")
        // Rewritten whenever the version changes so the export is always
        // self-describing.
        if (doc.exists() && doc.readText().contains("schemaVersion $SCHEMA_VERSION")) return
        runCatching { doc.writeText(SCHEMA_DOC) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun iso(epochMs: Long): String = synchronized(ISO) { ISO.format(java.util.Date(epochMs)) }
    }
}
