package org.aurecut.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aurecut.app.EditorDoc
import org.aurecut.app.core.PcmReader
import org.aurecut.app.core.ViewWindow
import org.aurecut.app.core.WaveColumns
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

private enum class Grab { NONE, START, END }

/**
 * The zoomable waveform. One finger on a marker moves that marker, one finger elsewhere pans,
 * two fingers zoom around the pinch center. All frame values are in the clip's own sample frames.
 */
@Composable
fun WaveformView(
    doc: EditorDoc,
    view: ViewWindow,
    selStart: Long,
    selEnd: Long,
    playFrame: Long,
    onView: (ViewWindow) -> Unit,
    onMoveStart: (Long) -> Unit,
    onMoveEnd: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val columns by produceState<WaveColumns?>(null, doc, view, widthPx) {
        if (widthPx > 0) {
            value = withContext(Dispatchers.Default) {
                PcmReader(doc.clip).use { reader -> doc.peaks.columns(reader, view.start, view.span / widthPx, widthPx) }
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val waveColor = colors.primary
    val dimColor = colors.outline
    val markerColor = colors.tertiary
    val playColor = colors.error
    val background = colors.surfaceVariant
    val tint = colors.primary.copy(alpha = 0.14f)
    val lineColor = colors.outlineVariant

    val total = doc.total
    val latestView by rememberUpdatedState(view)
    val latestStart by rememberUpdatedState(selStart)
    val latestEnd by rememberUpdatedState(selEnd)
    val latestOnView by rememberUpdatedState(onView)
    val latestOnMoveStart by rememberUpdatedState(onMoveStart)
    val latestOnMoveEnd by rememberUpdatedState(onMoveEnd)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    if (w <= 0f) return@awaitEachGesture
                    val slop = 28.dp.toPx()
                    val v0 = latestView
                    val startX = v0.toX(latestStart.toDouble(), w)
                    val endX = v0.toX(latestEnd.toDouble(), w)
                    val dStart = abs(down.position.x - startX)
                    val dEnd = abs(down.position.x - endX)
                    val grab = when {
                        dStart > slop && dEnd > slop -> Grab.NONE
                        dStart < dEnd -> Grab.START
                        dEnd < dStart -> Grab.END
                        down.position.x <= startX -> Grab.START // both markers on the same spot
                        else -> Grab.END
                    }

                    if (grab != Grab.NONE) {
                        // keep the finger's offset from the marker so it does not jump under the finger
                        val offset = (if (grab == Grab.START) startX else endX) - down.position.x
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) {
                                val frame = v0.toFrame(change.position.x + offset, w).roundToLong().coerceIn(0L, total)
                                if (grab == Grab.START) latestOnMoveStart(frame) else latestOnMoveEnd(frame)
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    } else {
                        var current = latestView
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (zoom != 1f || pan != Offset.Zero) {
                                var next = current
                                if (zoom != 1f && centroid.isSpecified) {
                                    next = next.zoomed(zoom.toDouble(), next.toFrame(centroid.x, w), total)
                                }
                                if (pan.x != 0f) {
                                    next = next.panned(-pan.x.toDouble() / w * next.span, total)
                                }
                                current = next
                                latestOnView(next)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        drawRect(background)

        val sx = view.toX(selStart.toDouble(), w)
        val ex = view.toX(selEnd.toDouble(), w)
        val left = sx.coerceIn(0f, w)
        val right = ex.coerceIn(0f, w)
        if (right > left) drawRect(tint, topLeft = Offset(left, 0f), size = Size(right - left, h))
        drawLine(lineColor, Offset(0f, mid), Offset(w, mid), strokeWidth = 1.dp.toPx())

        val c = columns
        if (c != null) {
            val half = mid * 0.92f
            val colWidth = max(1f, (c.framesPerPixel / view.span * w).toFloat())
            for (i in c.mins.indices) {
                val frame = c.start + (i + 0.5) * c.framesPerPixel
                val x = view.toX(frame, w)
                if (x < -colWidth || x > w + colWidth) continue
                val top = mid - c.maxs[i] / 32768f * half
                var bottom = mid - c.mins[i] / 32768f * half
                if (bottom - top < 1f) bottom = top + 1f
                val inside = frame >= selStart && frame <= selEnd
                drawLine(if (inside) waveColor else dimColor, Offset(x, top), Offset(x, bottom), strokeWidth = colWidth)
            }
        }

        if (playFrame >= 0) {
            val px = view.toX(playFrame.toDouble(), w)
            drawLine(playColor, Offset(px, 0f), Offset(px, h), strokeWidth = 2.dp.toPx())
        }

        val markerWidth = 3.dp.toPx()
        val handle = 9.dp.toPx()
        if (sx in -handle..(w + handle)) {
            drawLine(markerColor, Offset(sx, 0f), Offset(sx, h), strokeWidth = markerWidth)
            drawCircle(markerColor, radius = handle, center = Offset(sx, handle))
        }
        if (ex in -handle..(w + handle)) {
            drawLine(markerColor, Offset(ex, 0f), Offset(ex, h), strokeWidth = markerWidth)
            drawCircle(markerColor, radius = handle, center = Offset(ex, h - handle))
        }
    }
}

/** The whole track in miniature. A frame shows the visible part; tapping or dragging moves it. */
@Composable
fun OverviewStrip(
    doc: EditorDoc,
    view: ViewWindow,
    selStart: Long,
    selEnd: Long,
    onView: (ViewWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val columns by produceState<WaveColumns?>(null, doc, widthPx) {
        if (widthPx > 0) {
            value = withContext(Dispatchers.Default) {
                PcmReader(doc.clip).use { reader -> doc.peaks.columns(reader, 0.0, doc.total.toDouble() / widthPx, widthPx) }
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val background = colors.surfaceVariant
    val waveColor = colors.outline
    val tint = colors.primary.copy(alpha = 0.18f)
    val frameColor = colors.primary
    val total = doc.total
    val latestView by rememberUpdatedState(view)
    val latestOnView by rememberUpdatedState(onView)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(total) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    if (w <= 0f) return@awaitEachGesture
                    fun centerOn(x: Float) {
                        val span = latestView.span
                        val center = x / w * total
                        latestOnView(ViewWindow(center - span / 2, span).coerced(total))
                    }
                    centerOn(down.position.x)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            centerOn(change.position.x)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        drawRect(background)

        val c = columns
        if (c != null) {
            val half = mid * 0.9f
            for (i in c.mins.indices) {
                val x = i + 0.5f
                val top = mid - c.maxs[i] / 32768f * half
                var bottom = mid - c.mins[i] / 32768f * half
                if (bottom - top < 1f) bottom = top + 1f
                drawLine(waveColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
            }
        }

        val sx = (selStart.toDouble() / total * w).toFloat()
        val ex = (selEnd.toDouble() / total * w).toFloat()
        if (ex > sx) drawRect(colors.primary.copy(alpha = 0.10f), topLeft = Offset(sx, 0f), size = Size(ex - sx, h))

        val vx = (view.start / total * w).toFloat()
        val vw = max(2f, (view.span / total * w).toFloat())
        drawRect(tint, topLeft = Offset(vx, 0f), size = Size(vw, h))
        drawRect(frameColor, topLeft = Offset(vx, 0f), size = Size(vw, h), style = Stroke(width = 2.dp.toPx()))
    }
}
