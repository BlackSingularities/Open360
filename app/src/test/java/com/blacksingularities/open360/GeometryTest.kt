package com.blacksingularities.open360

import com.blacksingularities.open360.data.CaptureRange
import com.blacksingularities.open360.math.DEG
import com.blacksingularities.open360.math.Mat3
import com.blacksingularities.open360.math.Vec3
import com.blacksingularities.open360.math.angleBetween
import com.blacksingularities.open360.stitch.TargetGrid
import com.blacksingularities.open360.update.Updates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    @Test
    fun viewLooksAtYaw() {
        val f = Mat3.view(90 * DEG, 0f).transform(Vec3(0f, 0f, -1f))
        assertEquals(90f, f.lon / DEG, 0.01f)
        assertEquals(0f, f.lat / DEG, 0.01f)
        val up = Mat3.view(0f, 30 * DEG).transform(Vec3(0f, 0f, -1f))
        assertEquals(30f, up.lat / DEG, 0.01f)
    }

    @Test
    fun lonLatRoundTrip() {
        val v = Vec3.fromLonLat(-120 * DEG, 40 * DEG)
        assertEquals(-120f, v.lon / DEG, 0.01f)
        assertEquals(40f, v.lat / DEG, 0.01f)
    }

    @Test
    fun axisAngleIsRotation() {
        val r = Mat3.axisAngle(Vec3(0f, 0f, 1f), 90 * DEG)
        val v = r.transform(Vec3(1f, 0f, 0f))
        assertEquals(1f, v.y, 1e-5f)
        val back = r.transposed() * r
        assertEquals(1f, back[0, 0], 1e-5f)
    }

    @Test
    fun gridCoversSphere() {
        val t = TargetGrid.build(CaptureRange.SPHERE, 0.5f, 0.66f, 0.35f)
        assertTrue(t.size in 20..80)
        // Każdy kierunek ma cel w odległości mniejszej niż połowa przekątnej kadru.
        for (lat in -85..85 step 10) for (lon in -180 until 180 step 10) {
            val d = Vec3.fromLonLat(lon * DEG, lat * DEG)
            val nearest = t.minOf { angleBetween(it.dir, d) } / DEG
            assertTrue("lat=$lat lon=$lon: $nearest", nearest < 45f)
        }
    }

    @Test
    fun versions() {
        assertTrue(Updates.newer("1.2.0", "1.1.9"))
        assertFalse(Updates.newer("1.0.0", "1.0.0"))
        assertFalse(Updates.newer("2.0.0", "1.0-dev"))
    }
}
