package dev.froa.xiaoimu.gym

/** A gym machine the user can track against. */
data class Machine(
    val id: String,
    val name: String,
    val category: String,
    val custom: Boolean = false,
) {
    /** Cheap fuzzy match for the search bar: all typed words must appear. */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val hay = "$name $category".lowercase()
        return query.trim().lowercase().split(" ").all { it.isEmpty() || hay.contains(it) }
    }
}

/**
 * Built-in catalogue. Deliberately just the common plate-stack and lever
 * machines you find in most gyms -- anything missing is covered by
 * "Add custom machine".
 */
object MachineCatalog {
    val builtIn: List<Machine> = listOf(
        m("chest-press", "Chest Press", "Chest"),
        m("incline-chest-press", "Incline Chest Press", "Chest"),
        m("pec-deck", "Pec Deck / Butterfly", "Chest"),
        m("cable-crossover", "Cable Crossover", "Chest"),
        m("lat-pulldown", "Lat Pulldown", "Back"),
        m("seated-row", "Seated Cable Row", "Back"),
        m("t-bar-row", "T-Bar Row", "Back"),
        m("assisted-pullup", "Assisted Pull-up", "Back"),
        m("back-extension", "Back Extension", "Back"),
        m("shoulder-press", "Shoulder Press", "Shoulders"),
        m("lateral-raise", "Lateral Raise Machine", "Shoulders"),
        m("rear-delt-fly", "Rear Delt Fly", "Shoulders"),
        m("bicep-curl", "Biceps Curl Machine", "Arms"),
        m("preacher-curl", "Preacher Curl", "Arms"),
        m("triceps-pushdown", "Triceps Pushdown", "Arms"),
        m("triceps-extension", "Triceps Extension", "Arms"),
        m("leg-press", "Leg Press", "Legs"),
        m("hack-squat", "Hack Squat", "Legs"),
        m("leg-extension", "Leg Extension", "Legs"),
        m("seated-leg-curl", "Seated Leg Curl", "Legs"),
        m("lying-leg-curl", "Lying Leg Curl", "Legs"),
        m("calf-raise", "Calf Raise", "Legs"),
        m("hip-abduction", "Hip Abduction", "Legs"),
        m("hip-adduction", "Hip Adduction", "Legs"),
        m("glute-kickback", "Glute Kickback", "Legs"),
        m("smith-machine", "Smith Machine", "Legs"),
        m("ab-crunch", "Ab Crunch Machine", "Core"),
        m("cable-woodchop", "Cable Woodchop", "Core"),
        m("rowing-machine", "Rowing Machine", "Cardio"),
    )

    private fun m(id: String, name: String, cat: String) = Machine(id, name, cat)
}
