package ch.vacd.platform

import ch.vacd.platform.ingestion.FeederAuditEnricher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeederAuditEnricherTest {

    @Test fun `injects original_content and originating_system_audit`() {
        val flat = buildJsonObject {
            put("immunization administration/_uid", "x")
            put("immunization administration/composer|name", "PBLL")
        }
        val fhir = """{"resourceType":"Immunization","status":"completed"}"""
        val out = FeederAuditEnricher.addOriginal(flat, fhir, systemId = "ch-vacd-fhir", time = "2026-05-16T20:00:00Z")
        assertEquals(fhir, (out["immunization administration/_feeder_audit/original_content"] as JsonPrimitive).content)
        assertEquals("application/fhir+json",
            (out["immunization administration/_feeder_audit/original_content|formalism"] as JsonPrimitive).content)
        assertEquals("ch-vacd-fhir",
            (out["immunization administration/_feeder_audit/originating_system_audit|system_id"] as JsonPrimitive).content)
        // Original entries preserved.
        assertEquals("x", (out["immunization administration/_uid"] as JsonPrimitive).content)
        assertTrue(out.containsKey("immunization administration/composer|name"))
    }

    @Test fun `does not overwrite feeder_audit keys already present from openFHIR`() {
        val flat = buildJsonObject {
            put("immunization administration/_uid", "x")
            put("immunization administration/_feeder_audit/original_content", "pre-existing")
            put("immunization administration/_feeder_audit/originating_system_audit|system_id", "openfhir")
        }
        val fhir = """{"resourceType":"Bundle"}"""
        val out = FeederAuditEnricher.addOriginal(flat, fhir, systemId = "ch-vacd-fhir", time = "2026-05-16T20:00:00Z")
        assertEquals("pre-existing",
            (out["immunization administration/_feeder_audit/original_content"] as JsonPrimitive).content)
        assertEquals("openfhir",
            (out["immunization administration/_feeder_audit/originating_system_audit|system_id"] as JsonPrimitive).content)
    }
}
