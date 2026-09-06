package dev.froa.xiaoimu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.froa.xiaoimu.ImuHistory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Axis colours, kept consistent everywhere X/Y/Z appear. */
val AxisX = Color(0xFFE5484D)
val AxisY = Color(0xFF30A46C)
val AxisZ = Color(0xFF0090FF)

private val GridColor = Color(0xFF2A2F3A)
private val ZeroLine = Color(0xFF454C5A)

/**
 * Scrolling multi-series strip chart.
 *
 * The vertical scale grows to fit the data and then decays back slowly, so a
 * single sharp movement does not permanently flatten the trace afterwards.
 */
@Composable
fun StripChart(
    history: ImuHistory,
    channels: List<Pair<ImuHistory.Channel, Color>>,
    minRange: Float,
    unitLabel: String,
    scaleState: MutableFloatHolder,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val buf = FloatArray(history.capacity)

            // First pass: find the extreme across every channel this frame.
            var peak = minRange
            val seriesData = channels.map { (ch, color) ->
                val n = history.series(ch, buf)
                val copy = buf.copyOf(n)
                for (i in 0 until n) peak = max(peak, abs(copy[i]))
                copy to color
            }
            // Ease the scale toward the peak: instant growth would make the
            // trace jump, and instant shrink would make it twitch.
            val target = peak * 1.15f
            scaleState.value = if (target > scaleState.value) {
                target
            } else {
                scaleState.value + (target - scaleState.value) * 0.05f
            }
            val scale = max(scaleState.value, minRange)

            drawGrid(scale)
            seriesData.forEach { (data, color) -> drawSeries(data, scale, color) }
        }
    }
}

/** Holds the eased chart scale across recompositions. */
class MutableFloatHolder(var value: Float)

private fun DrawScope.drawGrid(scale: Float) {
    val mid = size.height / 2f
    // Zero line plus +/- full-scale guides.
    drawLine(ZeroLine, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
    listOf(0.5f, 1f).forEach { f ->
        val dy = mid * f
        drawLine(GridColor, Offset(0f, mid - dy), Offset(size.width, mid - dy), 1f)
        drawLine(GridColor, Offset(0f, mid + dy), Offset(size.width, mid + dy), 1f)
    }
}

private fun DrawScope.drawSeries(data: FloatArray, scale: Float, color: Color) {
    if (data.size < 2) return
    val mid = size.height / 2f
    val stepX = size.width / (data.size - 1).toFloat()
    val path = Path()
    for (i in data.indices) {
        val x = i * stepX
        // Clamp so an out-of-range spike draws at the edge instead of far
        // outside the canvas.
        val norm = min(1f, max(-1f, data[i] / scale))
        val y = mid - norm * mid
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
}

/**
 * Bubble level: the dot follows the gravity vector's X/Y components, so
 * tilting the board moves it toward the low edge.
 */
@Composable
fun TiltIndicator(ax: Float, ay: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = min(size.width, size.height) / 2f - 6f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(GridColor, r, c, style = Stroke(width = 2f))
        drawCircle(GridColor, r * 0.5f, c, style = Stroke(width = 1f))
        drawLine(GridColor, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(GridColor, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)

        // 1 g of tilt maps to the full radius; clamp so it stays in the dial.
        val dx = min(1f, max(-1f, ax)) * r
        val dy = min(1f, max(-1f, ay)) * r
        drawCircle(AxisZ.copy(alpha = 0.30f), 13f, Offset(c.x + dx, c.y + dy))
        drawCircle(AxisZ, 7f, Offset(c.x + dx, c.y + dy))
    }
}
