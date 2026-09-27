package com.xnotes.bench

import com.xnotes.canvas.InteractionController
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.Stroke
import com.xnotes.core.platform.monotonicNanos
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The core's share of inking, per pen sample, exactly as the infinite-canvas host drives it
 * (InfiniteEditor.publishWetStroke / commitStroke): add the sample, mesh newly settled runs of
 * [RUN_POINTS], re-mesh the moving tail; ink that cannot be cached in runs (highlighter, neon) is
 * re-meshed whole. At pen-up the stroke is simplified and meshed whole for the scene.
 * Common code, so the same measurement runs on the JVM and on Kotlin/Native.
 */
object PenBench {
    const val RUN_POINTS = 96
    private const val HZ = 240.0

    class Result(val label: String, private val perSample: LongArray, private val perCommit: LongArray, val checksum: Long) {
        private fun pct(a: LongArray, p: Double): Long = a.sortedArray().let { it[((it.size - 1) * p).toInt()] }
        private fun us(ns: Long) = ns / 1000.0
        private fun fmt(v: Double) = ((v * 10).toLong() / 10.0).toString()

        fun report(): String = buildString {
            val budget = (1_000_000_000L / HZ).toLong()
            append(label.padEnd(34))
            append(" sample µs: p50 ").append(fmt(us(pct(perSample, 0.5))))
            append(" p99 ").append(fmt(us(pct(perSample, 0.99))))
            append(" max ").append(fmt(us(perSample.max())))
            append(" | over 240Hz budget: ").append(perSample.count { it > budget })
            append(" of ").append(perSample.size)
            append(" | pen-up µs: p50 ").append(fmt(us(pct(perCommit, 0.5))))
            append(" max ").append(fmt(us(perCommit.max())))
        }
    }

    /** A handwriting-like path: loops drifting right, pressure swelling and easing. */
    private fun sampleAt(stroke: Int, i: Int): Sample {
        val t = i / HZ
        val a = t * 9.0 + stroke
        val x = 200.0 + stroke * 3.0 + t * 180.0 + 22.0 * cos(a) + 6.0 * sin(a * 2.7)
        val y = 300.0 + (stroke % 17) * 40.0 + 30.0 * sin(a) + 4.0 * cos(a * 3.1)
        val p = 0.35 + 0.5 * (0.5 + 0.5 * sin(t * 5.0 + stroke))
        return Sample(x, y, p, i * 1000.0 / HZ)
    }

    fun run(label: String, tool: Tool, strokes: Int, points: Int, warmupStrokes: Int = 0): Result {
        val perSample = LongArray(strokes * points)
        val perCommit = LongArray(strokes)
        var checksum = 0L
        var n = 0
        for (s in -warmupStrokes until strokes) {
            val measured = s >= 0
            val stroke = Stroke(tool, ToolDefaults.configFor(tool))
            stroke.finished = false
            var wetMeshed = 0
            var wetArc = 0.0
            for (i in 0 until points) {
                val sample = sampleAt(s + warmupStrokes, i)
                val t0 = monotonicNanos()
                stroke.addSample(sample)
                val ribbon = stroke.wetRibbon
                if (ribbon == null || !stroke.wetCacheable) {
                    checksum += ItemMesher.mesh(stroke)?.parts?.sumOf { it.mesh.positions.size } ?: 0
                } else {
                    val settled = ribbon.settledCount
                    if (settled - wetMeshed >= RUN_POINTS) {
                        val from = (wetMeshed - 1).coerceAtLeast(0)
                        checksum += ItemMesher.meshRun(stroke, ribbon, from, settled - from, wetArc)?.mesh?.positions?.size ?: 0
                        for (k in from + 1 until settled) {
                            wetArc += hypot(ribbon.cx(k) - ribbon.cx(k - 1), ribbon.cy(k) - ribbon.cy(k - 1))
                        }
                        wetMeshed = settled
                    }
                    val tailFrom = (wetMeshed - 1).coerceAtLeast(0)
                    checksum += ItemMesher.meshRun(stroke, ribbon, tailFrom, ribbon.pointCount - tailFrom, wetArc)?.mesh?.positions?.size ?: 0
                }
                val dt = monotonicNanos() - t0
                if (measured) perSample[n++] = dt
            }
            val t0 = monotonicNanos()
            stroke.finished = true
            if (StrokeSimplify.enabled && !stroke.straight) {
                val slim = StrokeSimplify.simplify(stroke.samples, stroke.geometry().halfWidths, InteractionController.SIMPLIFY_EPS,
                    stroke.smoothScale, stroke.config.directionStrength)
                if (slim.size != stroke.sampleCount) {
                    stroke.setSamples(slim)
                    stroke.invalidate()
                }
            }
            checksum += ItemMesher.mesh(stroke)?.parts?.sumOf { it.mesh.positions.size } ?: 0
            if (measured) perCommit[s] = monotonicNanos() - t0
        }
        return Result(label, perSample, perCommit, checksum)
    }

    /** The scenarios reported for stage 4.4. */
    fun all(): List<Result> = listOf(
        run("pen, 0.5 s strokes (120 samples)", Tool.PEN, strokes = 300, points = 120, warmupStrokes = 100),
        run("pen, 8 s stroke (1920 samples)", Tool.PEN, strokes = 20, points = 1920, warmupStrokes = 3),
        run("calligraphy, 2 s (480 samples)", Tool.CALLIGRAPHY, strokes = 60, points = 480, warmupStrokes = 10),
        run("highlighter, 2 s (480 samples)", Tool.HIGHLIGHTER, strokes = 30, points = 480, warmupStrokes = 5),
    )
}
