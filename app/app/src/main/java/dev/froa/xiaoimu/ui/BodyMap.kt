package dev.froa.xiaoimu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize

/**
 * Tappable muscle groups. [category] matches `Machine.category`, which is what
 * links a body region to the machines that train it.
 */
enum class BodyRegion(val label: String, val category: String) {
    SHOULDERS("Shoulders", "Shoulders"),
    CHEST("Chest", "Chest"),
    BACK("Back", "Back"),
    ARMS("Arms", "Arms"),
    CORE("Core", "Core"),
    LEGS("Legs", "Legs"),
}

/** Which way the figure is facing. Back muscles need the reverse view. */
enum class BodyFacing { FRONT, BACK }

/**
 * A simple front/back human figure whose muscle groups can be tapped.
 *
 * Regions are axis-aligned rounded boxes in normalised (0..1) coordinates
 * rather than real silhouette paths. That keeps hit-testing exact and cheap --
 * a tap maps to a region by containment, with no path maths - and the drawn
 * shapes still read clearly as a body at the size this renders at.
 */
private data class RegionBox(
    val region: BodyRegion,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val round: Float = 0.35f,
) {
    fun rect(size: Size) = Rect(l * size.width, t * size.height, r * size.width, b * size.height)
}

private val FRONT_REGIONS = listOf(
    // Deltoid caps, sitting proud of the torso on each side.
    RegionBox(BodyRegion.SHOULDERS, 0.095f, 0.163f, 0.305f, 0.258f, 0.50f),
    RegionBox(BodyRegion.SHOULDERS, 0.695f, 0.163f, 0.905f, 0.258f, 0.50f),
    RegionBox(BodyRegion.CHEST, 0.280f, 0.175f, 0.720f, 0.315f, 0.22f),
    RegionBox(BodyRegion.CORE, 0.310f, 0.320f, 0.690f, 0.455f, 0.18f),
    // Upper arm + forearm as one column each side.
    RegionBox(BodyRegion.ARMS, 0.085f, 0.265f, 0.258f, 0.560f, 0.45f),
    RegionBox(BodyRegion.ARMS, 0.742f, 0.265f, 0.915f, 0.560f, 0.45f),
    RegionBox(BodyRegion.LEGS, 0.298f, 0.470f, 0.478f, 0.950f, 0.30f),
    RegionBox(BodyRegion.LEGS, 0.522f, 0.470f, 0.702f, 0.950f, 0.30f),
)

private val BACK_REGIONS = listOf(
    RegionBox(BodyRegion.SHOULDERS, 0.095f, 0.163f, 0.305f, 0.258f, 0.50f),
    RegionBox(BodyRegion.SHOULDERS, 0.695f, 0.163f, 0.905f, 0.258f, 0.50f),
    // Lats and lower back fill the whole rear torso.
    RegionBox(BodyRegion.BACK, 0.280f, 0.175f, 0.720f, 0.455f, 0.20f),
    RegionBox(BodyRegion.ARMS, 0.085f, 0.265f, 0.258f, 0.560f, 0.45f),
    RegionBox(BodyRegion.ARMS, 0.742f, 0.265f, 0.915f, 0.560f, 0.45f),
    RegionBox(BodyRegion.LEGS, 0.298f, 0.470f, 0.478f, 0.950f, 0.30f),
    RegionBox(BodyRegion.LEGS, 0.522f, 0.470f, 0.702f, 0.950f, 0.30f),
)

@Composable
fun BodyMap(
    facing: BodyFacing,
    selected: BodyRegion?,
    onRegionTap: (BodyRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val boxes = if (facing == BodyFacing.FRONT) FRONT_REGIONS else BACK_REGIONS

    Canvas(
        modifier.pointerInput(facing, boxes) {
            detectTapGestures { pos ->
                val canvas = size.toSize()
                // Exact containment first, then nearest region within a
                // finger's reach. Without the fallback, a tap in the gap
                // between the thighs or beside an arm silently does nothing,
                // which just reads as the screen being broken.
                val exact = boxes.firstOrNull { it.rect(canvas).contains(pos) }
                val hit = exact ?: boxes
                    .map { it to it.rect(canvas).distanceTo(pos) }
                    .filter { (_, d) -> d <= TAP_SLOP_PX }
                    .minByOrNull { (_, d) -> d }
                    ?.first
                if (hit != null) onRegionTap(hit.region)
            }
        }
    ) {
        drawHeadAndFrame(facing)
        boxes.forEach { box ->
            val isSel = selected == box.region
            val rect = box.rect(size)
            val radius = minOf(rect.width, rect.height) * box.round
            drawRoundRect(
                color = if (isSel) Accent.copy(alpha = 0.55f) else MuscleFill,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = if (isSel) Accent else MuscleEdge,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(radius, radius),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isSel) 3f else 1.5f),
            )
        }
    }
}

/** Head, neck and hips: not tappable, just enough to read as a person. */
private fun DrawScope.drawHeadAndFrame(facing: BodyFacing) {
    val w = size.width
    val h = size.height
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)

    // Head
    val headR = 0.105f * w
    val headC = Offset(0.5f * w, 0.078f * h)
    drawCircle(MuscleFill, radius = headR, center = headC)
    drawCircle(MuscleEdge, radius = headR, center = headC, style = stroke)

    // Neck
    drawRoundRect(
        color = MuscleFill,
        topLeft = Offset(0.435f * w, 0.118f * h),
        size = Size(0.13f * w, 0.062f * h),
        cornerRadius = CornerRadius(6f, 6f),
    )
    // Hips, bridging torso to legs
    drawRoundRect(
        color = MuscleFill,
        topLeft = Offset(0.295f * w, 0.432f * h),
        size = Size(0.41f * w, 0.062f * h),
        cornerRadius = CornerRadius(10f, 10f),
    )
    if (facing == BodyFacing.BACK) {
        // A faint spine line, so front and back are distinguishable at a glance.
        drawLine(
            MuscleEdge,
            Offset(0.5f * w, 0.19f * h), Offset(0.5f * w, 0.45f * h),
            strokeWidth = 1.5f,
        )
    }
}

/** How far outside a region a tap still counts, in pixels (~9dp at 3x). */
private const val TAP_SLOP_PX = 28f

/** Shortest distance from a point to this rectangle; 0 when inside. */
private fun Rect.distanceTo(p: Offset): Float {
    val dx = maxOf(left - p.x, 0f, p.x - right)
    val dy = maxOf(top - p.y, 0f, p.y - bottom)
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private val MuscleFill = Color(0xFF232A36)
private val MuscleEdge = Color(0xFF3A4356)
