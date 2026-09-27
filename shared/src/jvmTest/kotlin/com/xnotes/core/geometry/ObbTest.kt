package com.xnotes.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ObbTest {

    @Test fun fromAabbIsUpright() {
        val o = Obb.fromAabb(Rect(0.0, 0.0, 100.0, 50.0))
        assertEquals(Pt(50.0, 25.0), o.center)
        assertEquals(50.0, o.halfW, 1e-9)
        assertEquals(25.0, o.halfH, 1e-9)
        assertEquals(0.0, o.angle, 1e-9)
    }

    @Test fun localWorldRoundTrip() {
        val o = Obb(Pt(10.0, 20.0), 30.0, 15.0, 0.7)
        val back = o.worldToLocal(o.localToWorld(Pt(42.0, 17.0)))
        assertEquals(42.0, back.x, 1e-9)
        assertEquals(17.0, back.y, 1e-9)
    }

    @Test fun uprightCornersClockwiseFromTopLeft() {
        val c = Obb.fromAabb(Rect(0.0, 0.0, 100.0, 50.0)).corners()
        assertEquals(Pt(0.0, 0.0), c[0])
        assertEquals(Pt(100.0, 0.0), c[1])
        assertEquals(Pt(100.0, 50.0), c[2])
        assertEquals(Pt(0.0, 50.0), c[3])
    }

    @Test fun containsRespectsRotation() {
        val o = Obb(Pt(0.0, 0.0), 50.0, 10.0, PI / 2) // a 100x20 box turned 90 degrees
        assertTrue(o.contains(Pt(0.0, 40.0))) // along the rotated long axis
        assertFalse(o.contains(Pt(40.0, 0.0))) // along the rotated short axis
    }
}
