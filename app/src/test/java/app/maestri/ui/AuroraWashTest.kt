package app.maestri.ui

import app.maestri.ui.Style.AuroraWash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The 187 background bug as numbers: the wash used to start and stop on hard edges (it was drawn
 * into a 62%-tall box) and its stops were spaced so the tablet rendered banding. These tests sample
 * a full column the way a screenshot would, and refuse any visible step.
 */
class AuroraWashTest {

    // The tablet the bug was photographed on: 2560x1600 px.
    private val widthPx = 2560f
    private val heightPx = 1600f

    @Test
    fun stopsAreSortedMonotonicAndInRange() {
        listOf(AuroraWash.Deep, AuroraWash.Lift).forEach { stops ->
            assertTrue(stops.isNotEmpty())
            assertEquals(0f, stops.first().first, 0f)
            assertEquals(1f, stops.last().first, 0f)
            stops.forEach { assertTrue("alpha in 0..1: ${it.second}", it.second in 0f..1f) }
            stops.zipWithNext().forEach { (a, b) ->
                assertTrue("positions must increase: ${a.first} -> ${b.first}", b.first > a.first)
                assertTrue("alpha never decreases: ${a.second} -> ${b.second}", b.second >= a.second)
            }
        }
    }

    @Test
    fun theTopMeltsIntoBlackAndTheBottomCarriesTheWash() {
        assertEquals(0f, AuroraWash.sample(AuroraWash.Deep, 0f), 0f)
        assertEquals(0f, AuroraWash.sample(AuroraWash.Deep, 0.2f), 0f)
        assertEquals(0f, AuroraWash.sample(AuroraWash.Lift, 0.5f), 0f)
        assertTrue(AuroraWash.sample(AuroraWash.Deep, 1f) >= 0.45f)
    }

    @Test
    fun aFullColumnHasNoVisibleStep() {
        // Composite the three layers exactly like drawAurora: deep, lift, then the radial bloom down
        // the center column (its center sits at the bottom center of the surface).
        val deepRgb = Triple(8f, 18f, 56f) // Gemini.AuroraDeep
        val liftRgb = Triple(13f, 27f, 76f) // Gemini.AuroraTop
        val bloomRgb = Triple(66f, 133f, 244f) // Gemini.StarBlue
        val rows = heightPx.toInt()
        val radius = widthPx * AuroraWash.BloomRadius
        var previous = Triple(0f, 0f, 0f)
        var worstStep = 0f

        for (row in 0 until rows) {
            val at = row / (rows - 1f)
            val deep = AuroraWash.sample(AuroraWash.Deep, at)
            val lift = AuroraWash.sample(AuroraWash.Lift, at)
            val distance = heightPx - row
            val bloom =
                if (distance < radius) AuroraWash.BloomAlpha * (1f - distance / radius) else 0f

            fun over(base: Float, target: Float, alpha: Float) = base * (1f - alpha) + target * alpha

            var r = deepRgb.first * deep
            var g = deepRgb.second * deep
            var b = deepRgb.third * deep
            r = over(r, liftRgb.first, lift)
            g = over(g, liftRgb.second, lift)
            b = over(b, liftRgb.third, lift)
            r = over(r, bloomRgb.first, bloom)
            g = over(g, bloomRgb.second, bloom)
            b = over(b, bloomRgb.third, bloom)

            val current = Triple(r, g, b)
            if (row > 0) {
                val step = max(
                    max(abs(current.first - previous.first), abs(current.second - previous.second)),
                    abs(current.third - previous.third),
                )
                worstStep = max(worstStep, step)
            }
            previous = current
        }

        assertTrue("adjacent rows jumped $worstStep/255 — that is a visible edge", worstStep <= 3f)
        assertTrue("the wash never becomes bluer than black at the bottom", previous.third >= 15f)
    }
}
