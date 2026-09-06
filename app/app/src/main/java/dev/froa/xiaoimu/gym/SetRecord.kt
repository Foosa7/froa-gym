package dev.froa.xiaoimu.gym

import org.json.JSONObject

/**
 * One completed set, as written to `sets.jsonl`.
 *
 * The pairing that matters for training a detector later is
 * [repsDetected] (what the algorithm produced) against [repsFinal] (what the
 * user says actually happened, after any manual correction). That difference
 * is the label. [recordingFile] points at the raw samples behind it.
 */
data class SetRecord(
    val setId: String,
    val machineId: String,
    val machineName: String,
    val machineCategory: String,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    val weightKg: Float,
    /** Raw output of the detector, before any human correction. */
    val repsDetected: Int,
    /** Ground truth after correction. Equals repsDetected when untouched. */
    val repsFinal: Int,
    val manualDelta: Int,
    val rejectedImpacts: Int,
    val detector: String,
    val detectorParams: Map<String, Any>,
    val sampleCount: Int,
    val sampleRateHz: Float,
    /** Path relative to the gym/ root, or null if recording was off. */
    val recordingFile: String?,
) {
    val manual: Boolean get() = manualDelta != 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", WorkoutStore.SCHEMA_VERSION)
        put("setId", setId)
        put("machineId", machineId)
        put("machineName", machineName)
        put("machineCategory", machineCategory)
        put("startedAt", startedAt)
        put("startedAtIso", WorkoutStore.iso(startedAt))
        put("finishedAt", finishedAt)
        put("finishedAtIso", WorkoutStore.iso(finishedAt))
        put("durationMs", durationMs)
        put("weightKg", weightKg.toDouble())
        put("repsDetected", repsDetected)
        put("repsFinal", repsFinal)
        put("manualDelta", manualDelta)
        put("rejectedImpacts", rejectedImpacts)
        put("detector", detector)
        put("detectorParams", JSONObject(detectorParams))
        put("sampleCount", sampleCount)
        put("sampleRateHz", sampleRateHz.toDouble())
        put("recordingFile", recordingFile ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): SetRecord {
            val paramsObj = o.optJSONObject("detectorParams") ?: JSONObject()
            val params = mutableMapOf<String, Any>()
            for (k in paramsObj.keys()) params[k] = paramsObj.get(k)
            return SetRecord(
                setId = o.optString("setId", ""),
                machineId = o.getString("machineId"),
                machineName = o.optString("machineName", ""),
                machineCategory = o.optString("machineCategory", ""),
                startedAt = o.optLong("startedAt", 0L),
                finishedAt = o.getLong("finishedAt"),
                durationMs = o.optLong("durationMs", 0L),
                weightKg = o.optDouble("weightKg", 0.0).toFloat(),
                repsDetected = o.optInt("repsDetected", o.optInt("reps", 0)),
                repsFinal = o.optInt("repsFinal", o.optInt("reps", 0)),
                manualDelta = o.optInt("manualDelta", 0),
                rejectedImpacts = o.optInt("rejectedImpacts", 0),
                detector = o.optString("detector", "unknown"),
                detectorParams = params,
                sampleCount = o.optInt("sampleCount", 0),
                sampleRateHz = o.optDouble("sampleRateHz", 0.0).toFloat(),
                recordingFile = if (o.isNull("recordingFile")) null
                                else o.optString("recordingFile", null),
            )
        }
    }
}
