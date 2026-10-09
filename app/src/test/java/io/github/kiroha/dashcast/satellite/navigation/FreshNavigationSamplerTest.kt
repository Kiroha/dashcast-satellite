package io.github.kiroha.dashcast.satellite.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshNavigationSamplerTest {
    private val active = NavigationNotification(
        key = "route", packageName = "com.google.android.apps.maps", ongoing = true,
        navigationCategory = true, title = "100 m", text = "Turn left",
    )

    @Test fun `each liveness refresh calls current source and carries time from before that read`() {
        var now = 100L
        var reads = 0
        val sampler = FreshNavigationSampler({ reads++; now += 40; listOf(active) }, { now })
        val first = sampler.sample(NavigationSource.MAPS, true, true, true)
        val second = sampler.sample(NavigationSource.MAPS, true, true, true)
        assertEquals(2, reads)
        assertEquals(100L, first.observation.observedAtElapsedMs)
        assertEquals(140L, second.observation.observedAtElapsedMs)
        assertTrue(first.observation is Observation.Valid)
    }

    @Test fun `source removal clears previous guidance immediately`() {
        var current = listOf(active)
        val sampler = FreshNavigationSampler({ current }, { 1_000L })
        assertTrue(sampler.sample(NavigationSource.MAPS, true, true, true).observation is Observation.Valid)
        current = emptyList()
        val stopped = sampler.sample(NavigationSource.MAPS, true, true, true)
        assertTrue(stopped.observation is Observation.Stop)
        assertEquals(SourceStatus.INACTIVE, stopped.status)
    }

    @Test fun `source becoming unsupported clears its previous valid turn`() {
        var current = listOf(active)
        val sampler = FreshNavigationSampler({ current }, { 1_000L })
        assertEquals(SourceStatus.ACTIVE, sampler.sample(NavigationSource.MAPS, true, true, true).status)
        current = listOf(active.copy(text = "Recalculating"))
        val stopped = sampler.sample(NavigationSource.MAPS, true, true, true)
        assertTrue(stopped.observation is Observation.Stop)
        assertEquals(SourceStatus.UNSUPPORTED, stopped.status)
    }

    @Test fun `permission revocation disconnection and disabled transmission do not read notifications`() {
        var reads = 0
        val sampler = FreshNavigationSampler({ reads++; listOf(active) }, { 10L })
        val missing = sampler.sample(NavigationSource.MAPS, true, true, false)
        val disconnected = sampler.sample(NavigationSource.MAPS, true, false, true)
        val disabled = sampler.sample(NavigationSource.MAPS, false, true, true)
        assertEquals(0, reads)
        assertEquals(SourceStatus.PERMISSION_MISSING, missing.status)
        assertEquals(SourceStatus.SOURCE_UNAVAILABLE, disconnected.status)
        assertEquals(SourceStatus.INACTIVE, disabled.status)
        assertTrue(listOf(missing, disconnected, disabled).all { it.observation is Observation.Stop })
    }

    @Test fun `OS read failure never refreshes cached valid guidance`() {
        var fails = false
        val sampler = FreshNavigationSampler({ if (fails) throw IllegalStateException() else listOf(active) }, { 10L })
        assertEquals(SourceStatus.ACTIVE, sampler.sample(NavigationSource.MAPS, true, true, true).status)
        fails = true
        val result = sampler.sample(NavigationSource.MAPS, true, true, true)
        assertEquals(SourceStatus.SOURCE_UNAVAILABLE, result.status)
        assertTrue(result.observation is Observation.Stop)
    }

    @Test fun `explicit source selection prevents switching to a different app on removal`() {
        val abrp = active.copy(packageName = AbrpAdapter.PACKAGE, key = "abrp")
        val sampler = FreshNavigationSampler({ listOf(abrp) }, { 10L })
        assertEquals(SourceStatus.INACTIVE, sampler.sample(NavigationSource.MAPS, true, true, true).status)
        assertEquals(SourceStatus.ACTIVE, sampler.sample(NavigationSource.ABRP, true, true, true).status)
    }

    @Test fun `newer unsupported selected source cannot resurrect older guidance notification`() {
        val newer = active.copy(key = "new", postTime = 20, text = "Recalculating")
        val sampler = FreshNavigationSampler({ listOf(active, newer) }, { 10L })
        assertEquals(SourceStatus.UNSUPPORTED, sampler.sample(NavigationSource.MAPS, true, true, true).status)
    }

    @Test fun `nonongoing navigation category works but ordinary source notifications do not`() {
        val evaluator = NavigationSnapshotEvaluator()
        assertEquals(SourceStatus.ACTIVE, evaluator.evaluate(NavigationSource.MAPS,
            listOf(active.copy(ongoing = false)), 0).status)
        assertEquals(SourceStatus.INACTIVE, evaluator.evaluate(NavigationSource.MAPS,
            listOf(active.copy(ongoing = false, navigationCategory = false)), 0).status)
    }
}
