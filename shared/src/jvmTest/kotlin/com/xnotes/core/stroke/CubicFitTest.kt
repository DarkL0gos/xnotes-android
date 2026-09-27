package com.xnotes.core.stroke

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Spec-style checks for the single cubic Bézier fitter. */
class CubicFitTest {

    private fun nearestDist(p: Pt, curve: List<Pt>): Double {
        var d = Double.MAX_VALUE
        for (j in 0 until curve.size - 1) {
            val s = Geometry.distancePointToSegment(p, curve[j], curve[j + 1])
            if (s < d) d = s
        }
        return d
    }

    @Test fun followsACArc() {
        // A ~150° arc is well within one cubic's reach.
        val cx = 400.0
        val cy = 400.0
        val r = 250.0
        val arc = (0..50).map { i ->
            val a = -PI * 0.42 + (0.83 * PI) * i / 50
            Pt(cx + r * cos(a), cy + r * sin(a))
        }
        val curve = CubicFit.fitSampled(arc, 0.09 * (2 * r), 32)
        assertNotNull(curve)
        for (p in arc) assertTrue("strayed from the arc", nearestDist(p, curve!!) <= 14.0)
        // Endpoints are pinned to the stroke.
        assertTrue(curve!!.first().distanceTo(arc.first()) < 1.0)
        assertTrue(curve.last().distanceTo(arc.last()) < 1.0)
    }

    @Test fun followsAnS() {
        val s = (0..60).map { i ->
            val t = i / 60.0
            Pt(200.0 + 80.0 * sin(2.0 * PI * t), 40.0 + 320.0 * t)
        }
        val curve = CubicFit.fitSampled(s, 0.09 * hypot(160.0, 320.0), 32)
        assertNotNull(curve)
        for (p in s) assertTrue("strayed from the S", nearestDist(p, curve!!) <= 22.0)
    }

    @Test fun leavesTooWideArcAsInk() {
        // A ~290° arc is beyond a single cubic, so it must be left as ink (null).
        val arc = (0..70).map { i ->
            val a = (1.6 * PI) * i / 70
            Pt(400.0 + 250.0 * cos(a), 400.0 + 250.0 * sin(a))
        }
        assertNull(CubicFit.fitSampled(arc, 0.09 * 500.0, 32))
    }

    @Test fun rejectsAComplexSquiggle() {
        val sq = (0..80).map { i ->
            val t = i / 80.0
            Pt(100.0 + 360.0 * t, 300.0 + 110.0 * sin(2.0 * PI * 3.0 * t))
        }
        assertNull(CubicFit.fitSampled(sq, 0.09 * hypot(360.0, 220.0), 32))
    }
}
