package com.techgv.cineverse.ui.nav

import java.io.ObjectInputStream
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the L4 review probe: a back stack saved by a previous release must restore after an upgrade. */
class BrowseKindUpgradeTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test fun legacyNavigationEnumStillRestoresIntoTheRoute() {
        // Android Navigation's default EnumType stores a Serializable enum in the saved Bundle; the fixture is
        // `BrowseKind.Movies` serialized by the pre-L4 app (class com.techgv.cineverse.ui.nav.BrowseKind).
        val restored = runCatching {
            ObjectInputStream(javaClass.classLoader!!.getResourceAsStream("legacy-browse-kind-movies.bin")).use { it.readObject() }
        }
        assertTrue("A saved Browse argument from the old class cannot restore: " + restored.exceptionOrNull(), restored.isSuccess)
        val kind = restored.getOrThrow() as Enum<*>
        assertEquals(BrowseKind.Movies, kind)
        assertEquals(Route.Browse.serializer().descriptor.getElementDescriptor(0).serialName, kind.javaClass.name)
    }

    @Test fun routeEnumNameAndConstantsAreFrozen() {
        assertEquals("com.techgv.cineverse.ui.nav.BrowseKind", BrowseKind::class.java.name)
        assertEquals(listOf("Movies", "Tv", "People"), BrowseKind.entries.map { it.name })
    }
}
