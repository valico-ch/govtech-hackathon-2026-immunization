package ch.vacd.platform

import ch.vacd.platform.ingestion.BundleExtractor
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundleExtractorTest {

    private fun loadExample(name: String): String {
        // examples/ is at the repo root; tests run from services/platform-business-logic-example/
        val candidates = listOf(
            Path.of("../../examples/$name"),
            Path.of("examples/$name"),
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: fail("example not found in any candidate path: $candidates")
        return Files.readString(path)
    }

    @Test fun `peels the official CH VACD document Bundle`() {
        val input = Json.parseToJsonElement(loadExample("01-immunization-administration-boostrix.json"))
        val out = BundleExtractor.peel(input)
        assertEquals("Composition",
            (out.composition["resourceType"] as JsonPrimitive).content)
        assertEquals(1, out.immunizations.size)
        assertEquals("Immunization",
            (out.immunizations[0]["resourceType"] as JsonPrimitive).content)
        assertNotNull(out.patient)
        assertEquals(1, out.practitioners.size)
        assertEquals(1, out.organizations.size)
        assertEquals(1, out.practitionerRoles.size)
    }

    @Test fun `peels a V2 multi-immunization Bundle`() {
        val input = Json.parseToJsonElement(loadExample("06-immunization-administration-v2-3dose-mixed.json"))
        val out = BundleExtractor.peel(input)
        assertEquals("Composition",
            (out.composition["resourceType"] as JsonPrimitive).content)
        assertTrue(out.immunizations.size >= 2, "expected multiple immunizations, got ${out.immunizations.size}")
        out.immunizations.forEach { imm ->
            assertEquals("Immunization", (imm["resourceType"] as JsonPrimitive).content)
        }
        assertNotNull(out.patient)
    }

    @Test fun `rejects a bare Immunization`() {
        val input = Json.parseToJsonElement("""{"resourceType":"Immunization","status":"completed"}""")
        val ex = assertFails { BundleExtractor.peel(input) }
        assertTrue((ex.message ?: "").contains("Bundle"), "expected error to mention Bundle: ${ex.message}")
    }

    @Test fun `rejects a Bundle whose type is not document`() {
        val input = Json.parseToJsonElement("""{"resourceType":"Bundle","type":"collection","entry":[]}""")
        val ex = assertFails { BundleExtractor.peel(input) }
        assertTrue((ex.message ?: "").contains("document"))
    }

    @Test fun `rejects a document Bundle whose first entry is not Composition`() {
        val input = Json.parseToJsonElement("""{
            "resourceType":"Bundle","type":"document",
            "entry":[{"resource":{"resourceType":"Immunization","status":"completed"}}]
        }""")
        val ex = assertFails { BundleExtractor.peel(input) }
        assertTrue((ex.message ?: "").contains("Composition"), "expected error to mention Composition: ${ex.message}")
    }

    @Test fun `rejects a Composition with no Immunization in any section`() {
        val input = Json.parseToJsonElement("""{
            "resourceType":"Bundle","type":"document",
            "entry":[
                {"resource":{"resourceType":"Composition","section":[]}}
            ]
        }""")
        val ex = assertFails { BundleExtractor.peel(input) }
        assertTrue((ex.message ?: "").contains("Composition"))
    }
}
