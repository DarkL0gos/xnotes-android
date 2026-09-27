package com.xnotes.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    private val square = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))

    @Test fun pointInsidePolygon() {
        assertTrue(Geometry.pointInPolygon(square, Pt(5.0, 5.0)))
    }

    @Test fun pointOutsidePolygon() {
        assertFalse(Geometry.pointInPolygon(square, Pt(15.0, 5.0)))
        assertFalse(Geometry.pointInPolygon(square, Pt(-1.0, 5.0)))
    }

    @Test fun degeneratePolygonIsNeverInside() {
        assertFalse(Geometry.pointInPolygon(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0)), Pt(0.5, 0.5)))
    }

    @Test fun evenOddConcaveHole() {
        // A C-shape where the ray crosses an even number of edges in the notch.
        val poly = listOf(
            Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0),
            Pt(0.0, 7.0), Pt(8.0, 7.0), Pt(8.0, 3.0), Pt(0.0, 3.0),
        )
        assertTrue(Geometry.pointInPolygon(poly, Pt(9.0, 5.0)))
        assertFalse(Geometry.pointInPolygon(poly, Pt(4.0, 5.0)))
    }

    @Test fun distancePointToSegment() {
        assertEquals(5.0, Geometry.distancePointToSegment(Pt(0.0, 5.0), Pt(0.0, 0.0), Pt(10.0, 0.0)), 1e-9)
        // Beyond the endpoint clamps to the endpoint distance.
        assertEquals(5.0, Geometry.distancePointToSegment(Pt(-5.0, 0.0), Pt(0.0, 0.0), Pt(10.0, 0.0)), 1e-9)
    }

    @Test fun dotProduct() {
        assertEquals(11.0, Geometry.dot(Pt(1.0, 2.0), Pt(3.0, 4.0)), 1e-9)
        assertEquals(0.0, Geometry.dot(Pt(1.0, 0.0), Pt(0.0, 1.0)), 1e-9)
    }

    @Test fun projectPointOntoLine() {
        val a = Pt(0.0, 0.0)
        val b = Pt(10.0, 0.0)
        // Drops to the foot on the horizontal line.
        val foot = Geometry.projectPointOntoLine(Pt(5.0, 7.0), a, b)
        assertEquals(5.0, foot.x, 1e-9)
        assertEquals(0.0, foot.y, 1e-9)
        // Unclamped: a point beyond b projects past b (segment-clamp would stop at b).
        val far = Geometry.projectPointOntoLine(Pt(20.0, 3.0), a, b)
        assertEquals(20.0, far.x, 1e-9)
        assertEquals(0.0, far.y, 1e-9)
        // Works on a diagonal line through the origin.
        val diag = Geometry.projectPointOntoLine(Pt(0.0, 2.0), Pt(0.0, 0.0), Pt(2.0, 2.0))
        assertEquals(1.0, diag.x, 1e-9)
        assertEquals(1.0, diag.y, 1e-9)
        // Degenerate a == b returns a.
        assertEquals(a, Geometry.projectPointOntoLine(Pt(4.0, 4.0), a, a))
    }

    @Test fun rectIntersection() {
        val a = Rect(0.0, 0.0, 10.0, 10.0)
        assertTrue(a.intersects(Rect(5.0, 5.0, 10.0, 10.0)))
        assertFalse(a.intersects(Rect(20.0, 20.0, 5.0, 5.0)))
    }

    @Test fun rectFromPointsIsNormalized() {
        val r = Rect.fromPoints(Pt(10.0, 10.0), Pt(0.0, 4.0))
        assertEquals(0.0, r.left, 1e-9)
        assertEquals(4.0, r.top, 1e-9)
        assertEquals(10.0, r.w, 1e-9)
        assertEquals(6.0, r.h, 1e-9)
    }

    @Test fun ptVectorOps() {
        assertEquals(5.0, Pt(3.0, 4.0).length(), 1e-9)
        assertEquals(7.0, Pt(0.0, 0.0).manhattanTo(Pt(3.0, 4.0)), 1e-9)
        val n = Pt(2.0, 0.0).normalized()
        assertEquals(1.0, n.x, 1e-9)
        val perp = Pt(1.0, 0.0).perp() // (-y, x) = (0, 1)
        assertEquals(0.0, perp.x, 1e-9)
        assertEquals(1.0, perp.y, 1e-9)
    }

    // --- Swept-disc ribbon bridge ---
    private fun signedArea2(a: Pt, b: Pt, c: Pt) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    @Test fun ribbonQuadCornersSitOnBothDiscRims() {
        // Each of the four corners lies exactly on the rim of the disc it belongs to, so the quad
        // meets the discs flush and the union has no gap and no overshoot.
        val q = Geometry.ribbonQuad(Pt(0.0, 0.0), 3.0, Pt(10.0, 0.0), 5.0)
        assertEquals(4, q.size)
        for (p in q) {
            val onFirst = p.distanceTo(Pt(0.0, 0.0))
            val onSecond = p.distanceTo(Pt(10.0, 0.0))
            assertTrue("corner on neither rim", kotlin.math.min(kotlin.math.abs(onFirst - 3.0), kotlin.math.abs(onSecond - 5.0)) < 1e-9)
        }
    }

    @Test fun ribbonQuadWindsPositiveSoFillsUnion() {
        // The quad is returned positively wound, regardless of which way the segment runs, so a
        // nonzero fill of several quads plus their discs unions instead of cancelling a gap.
        val forward = Geometry.ribbonQuad(Pt(0.0, 0.0), 3.0, Pt(10.0, 1.0), 4.0)
        val backward = Geometry.ribbonQuad(Pt(10.0, 1.0), 4.0, Pt(0.0, 0.0), 3.0)
        assertTrue(signedArea2(forward[0], forward[1], forward[2]) >= 0.0)
        assertTrue(signedArea2(backward[0], backward[1], backward[2]) >= 0.0)
    }

    @Test fun ribbonQuadEmptyForCoincidentCentres() {
        assertTrue(Geometry.ribbonQuad(Pt(5.0, 5.0), 3.0, Pt(5.0, 5.0), 4.0).isEmpty())
    }
}
