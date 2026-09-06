package dev.froa.xiaoimu.gym

/** One week of training activity, for the home screen chart. */
data class WeekBucket(
    val startMs: Long,
    /** Distinct days with at least one completed set. */
    val workouts: Int,
    val sets: Int,
    val reps: Int,
    val isCurrent: Boolean,
)
