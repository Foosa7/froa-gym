package dev.froa.xiaoimu.gym

import dev.froa.xiaoimu.ImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fixed-capacity ring of the detection signal, for the live plot. */
class SignalRing(val capacity: Int = 220) {
    private val v = FloatArray(capacity)
    @Volatile var count = 0; private set
    private var head = 0

    fun add(x: Float) {
        v[head] = x
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() { count = 0; head = 0 }

    fun copyInto(out: FloatArray): Int {
        val n = minOf(count, out.size)
        for (k in 0 until n) {
            val idx = ((head - 1 - k) % capacity + capacity) % capacity
            out[n - 1 - k] = v[idx]
        }
        return n
    }
}

/**
 * Turns rep events into sets, and records the raw stream behind each one.
 *
 * Detector-agnostic: swap the [detector] passed in and nothing else here
 * changes. The set's record keeps both the detector's own count and the
 * user-corrected count, which is what makes the saved data trainable.
 */
class WorkoutTracker(
    private val store: WorkoutStore,
    val detector: RepDetector = HeuristicRepDetector(),
) {
    val signal = SignalRing()
    private val recorder = SetRecorder(store.recordingsDir)

    private val _reps = MutableStateFlow(0)
    val reps: StateFlow<Int> = _reps

    private val _setsDone = MutableStateFlow<List<SetRecord>>(emptyList())
    val setsDone: StateFlow<List<SetRecord>> = _setsDone

    private val _resting = MutableStateFlow(false)
    val resting: StateFlow<Boolean> = _resting

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    var machine: Machine? = null; private set
    var weightKg: Float = 0f
    var restMs: Long = 6000L

    /** Detector's own count, untouched by the user. */
    var detectedReps: Int = 0; private set
    /** User's correction. repsFinal = detectedReps + manualDelta. */
    var manualDelta: Int = 0; private set

    var recordRaw: Boolean
        get() = recorder.enabled
        set(v) { recorder.enabled = v }

    val sampleCount: Int get() = recorder.sampleCount

    private var setStartedAt = 0L
    private var lastRepAtMs = 0L
    private var lastFrameAt = 0L

    fun open(m: Machine) {
        machine = m
        weightKg = store.weightFor(m.id)
        _setsDone.value = store.setsFor(m.id)
        resetCurrentSet()
    }

    fun resetCurrentSet() {
        detector.reset()
        recorder.reset()
        signal.clear()
        detectedReps = 0
        manualDelta = 0
        _reps.value = 0
        _resting.value = false
        setStartedAt = 0L
        lastRepAtMs = 0L
    }

    fun onSample(s: ImuSample) {
        val now = System.currentTimeMillis()
        val event = detector.update(s, now)
        signal.add(detector.signal)
        recorder.add(s, now, detector.signal, detector.active, event)
        _active.value = detector.active

        if (event == RepEvent.REP) {
            if (setStartedAt == 0L) setStartedAt = now
            lastRepAtMs = now
            detectedReps++
            _reps.value = (detectedReps + manualDelta).coerceAtLeast(0)
            _resting.value = false
            android.util.Log.d("RepCounter", "rep $detectedReps signal=${"%.2f".format(detector.signal)}")
        }

        val shouldRest = detectedReps > 0 && lastRepAtMs > 0 && now - lastRepAtMs > restMs
        if (shouldRest != _resting.value) _resting.value = shouldRest
        if (shouldRest) finishSet()

        if (now - lastFrameAt >= 33) {
            lastFrameAt = now
            _tick.value = _tick.value + 1
        }
    }

    /** Banks the current set: writes the CSV, then appends the JSONL record. */
    fun finishSet() {
        val m = machine ?: return
        val final = (detectedReps + manualDelta).coerceAtLeast(0)
        if (final <= 0 && detectedReps <= 0) return

        val finishedAt = System.currentTimeMillis()
        val started = if (setStartedAt == 0L) finishedAt else setStartedAt
        val setId = buildSetId(m, started)

        val recordingPath = recorder.flush(setId)
        store.appendSet(
            SetRecord(
                setId = setId,
                machineId = m.id,
                machineName = m.name,
                machineCategory = m.category,
                startedAt = started,
                finishedAt = finishedAt,
                durationMs = finishedAt - started,
                weightKg = weightKg,
                repsDetected = detectedReps,
                repsFinal = final,
                manualDelta = manualDelta,
                rejectedImpacts = detector.rejectedImpacts,
                detector = detector.name,
                detectorParams = detector.params,
                sampleCount = recorder.sampleCount,
                sampleRateHz = recorder.sampleRateHz,
                recordingFile = recordingPath,
            )
        )
        _setsDone.value = store.setsFor(m.id)
        resetCurrentSet()
    }

    fun adjustReps(delta: Int) {
        val next = (detectedReps + manualDelta + delta).coerceAtLeast(0)
        manualDelta = next - detectedReps
        if (setStartedAt == 0L && next > 0) setStartedAt = System.currentTimeMillis()
        _reps.value = next
        _tick.value = _tick.value + 1
    }

    fun setWeight(kg: Float) {
        weightKg = kg.coerceAtLeast(0f)
        machine?.let { store.setWeight(it.id, weightKg) }
        _tick.value = _tick.value + 1
    }

    fun clearHistory() {
        machine?.let {
            store.clearSetsFor(it.id)
            _setsDone.value = emptyList()
        }
    }

    /** Sortable, filename-safe, and unique enough within a session. */
    private fun buildSetId(m: Machine, startedAt: Long): String {
        val stamp = WorkoutStore.iso(startedAt).replace("[-:]".toRegex(), "")
        return "$stamp-${m.id}-${(startedAt % 1000).toString().padStart(3, '0')}"
    }
}
