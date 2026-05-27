package ch.vacd.platform

import ch.vacd.platform.bootstrap.Bootstrap
import ch.vacd.platform.clients.EhrbaseClient
import ch.vacd.platform.clients.HapiClient
import ch.vacd.platform.clients.OpenFhirClient
import ch.vacd.platform.ingestion.FeederAuditEnricher
import ch.vacd.platform.ingestion.ImmunizationIngest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hits the live compose stack. Enabled only when INTEGRATION=1.
 * Reads service URLs from env (falls back to service-name URLs) and loads
 * the canonical CH VACD examples from the repo's top-level examples/.
 */
class EndToEndIntegrationTest {
    private val fhirUrl = System.getenv("FHIR_SERVER_1_URL")
        ?: "http://fhir-server-1:9111/ch-vacd-api-reference-server/fhir"
    private val mapperUrl = System.getenv("MAPPER_URL") ?: "http://openfhir:8083"
    private val cdrUrl = System.getenv("CDR_URL") ?: "http://ehrbase:8080/ehrbase/rest/openehr/v1"
    private val templateId = "ch-vacd-immunization administration.v1-alpha"

    private fun examplesDir(): Path {
        val candidates = listOf(
            Path.of("../../examples"),
            Path.of("examples"),
            Path.of("/workspaces/govtech-hackathon-2026-immunization/examples"),
        )
        return candidates.firstOrNull { Files.exists(it) && Files.isDirectory(it) }
            ?: fail("examples dir not found in $candidates")
    }

    private fun example(slug: String): JsonElement {
        val body = Files.readString(examplesDir().resolve("$slug.json"))
        return Json.parseToJsonElement(body)
    }

    private fun ingestor(): Triple<ImmunizationIngest, EhrbaseClient, Bootstrap> {
        val hapi = HapiClient(fhirUrl)
        val openFhir = OpenFhirClient(mapperUrl, templateId)
        val cdr = EhrbaseClient(cdrUrl, "ehrbase-user", "SuperSecretPassword")
        val bs = Bootstrap(openFhir, cdr, templateId)
        runBlocking { bs.run() }
        return Triple(ImmunizationIngest(hapi, openFhir, cdr), cdr, bs)
    }

    private fun aqlRows(cdr: EhrbaseClient, query: String): JsonArray = runBlocking {
        val raw = cdr.aql(query)
        val parsed = Json.parseToJsonElement(raw) as? JsonObject
        parsed?.get("rows") as? JsonArray ?: JsonArray(emptyList())
    }

    @Test fun `ingests V1 single-immunization Bundles end-to-end`() = runBlocking {
        val (ingest, cdr, _) = ingestor()
        val slugs = listOf(
            "01-immunization-administration-boostrix",
            "02-immunization-administration-comirnaty",
            "03-immunization-administration-priorix",
        )
        for (slug in slugs) {
            val result = ingest.ingest(example(slug))
            assertNotNull(result.compositionUid, "$slug: compositionUid")
            assertNotNull(result.ehrId, "$slug: ehrId")
            assertEquals(1, result.immunizationCount, "$slug: immunizationCount")
            assertEquals(1, result.practitionerIds.size, "$slug: practitioners")
            assertEquals(1, result.organizationIds.size, "$slug: organizations")
            val canonical = cdr.getCompositionCanonical(result.ehrId, result.compositionUid)
            assertTrue(canonical.contains("ACTION"), "$slug: composition missing ACTION")
        }
    }

    @Test fun `ingests V2 multi-immunization Bundles end-to-end`() = runBlocking {
        val (ingest, cdr, _) = ingestor()
        val v2Slugs = listOf(
            "04-immunization-administration-v2-2dose-comirnaty" to 2,
            "05-immunization-administration-v2-3dose-comirnaty" to 3,
            "06-immunization-administration-v2-3dose-mixed" to 3,
        )
        for ((slug, expectedCount) in v2Slugs) {
            val result = ingest.ingest(example(slug))
            assertNotNull(result.compositionUid, "$slug: compositionUid")
            assertNotNull(result.ehrId, "$slug: ehrId")
            assertEquals(expectedCount, result.immunizationCount, "$slug: immunizationCount")
            val canonical = cdr.getCompositionCanonical(result.ehrId, result.compositionUid)
            assertTrue(canonical.contains("ACTION"), "$slug: composition missing ACTION")
            assertTrue(result.originalFhirJson.contains("\"Bundle\""), "$slug: originalFhirJson should be Bundle")
        }
    }

    @Test fun `AQL validates V2 field mappings for 3-dose Comirnaty`() = runBlocking {
        val (ingest, cdr, _) = ingestor()
        val result = ingest.ingest(example("05-immunization-administration-v2-3dose-comirnaty"))


        val countRows = aqlRows(cdr,
            "SELECT count(a) AS cnt FROM EHR e CONTAINS COMPOSITION c " +
            "CONTAINS ACTION a[openEHR-EHR-ACTION.medication.v1] " +
            "WHERE c/uid/value = '${result.compositionUid}'")
        val count = (countRows.firstOrNull() as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        assertEquals(3, count, "expected 3 ACTION entries for 3-dose bundle")

        val vaccineRows = aqlRows(cdr,
            "SELECT a/description[at0017]/items[at0020]/value AS vaccine " +
            "FROM EHR e CONTAINS COMPOSITION c " +
            "CONTAINS ACTION a[openEHR-EHR-ACTION.medication.v1] " +
            "WHERE c/uid/value = '${result.compositionUid}'")
        assertTrue(vaccineRows.size == 3, "expected 3 vaccine rows, got ${vaccineRows.size}")

        val timeRows = aqlRows(cdr,
            "SELECT a/time/value AS t FROM EHR e CONTAINS COMPOSITION c " +
            "CONTAINS ACTION a[openEHR-EHR-ACTION.medication.v1] " +
            "WHERE c/uid/value = '${result.compositionUid}' ORDER BY a/time/value ASC")
        assertTrue(timeRows.size == 3, "expected 3 time rows, got ${timeRows.size}")

        val ismRows = aqlRows(cdr,
            "SELECT a/ism_transition/careflow_step/defining_code/code_string AS step " +
            "FROM EHR e CONTAINS COMPOSITION c " +
            "CONTAINS ACTION a[openEHR-EHR-ACTION.medication.v1] " +
            "WHERE c/uid/value = '${result.compositionUid}'")
        for (row in ismRows) {
            val step = ((row as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
            assertEquals("at0006", step, "ISM careflow_step should be at0006 (Dose administered)")
        }
    }

    @Test fun `AQL validates mixed vaccines in single Composition`() = runBlocking {
        val (ingest, cdr, _) = ingestor()
        val result = ingest.ingest(example("06-immunization-administration-v2-3dose-mixed"))


        val vaccineRows = aqlRows(cdr,
            "SELECT a/description[at0017]/items[at0020]/value/defining_code/code_string AS code " +
            "FROM EHR e CONTAINS COMPOSITION c " +
            "CONTAINS ACTION a[openEHR-EHR-ACTION.medication.v1] " +
            "WHERE c/uid/value = '${result.compositionUid}'")
        val codes = vaccineRows.mapNotNull { row ->
            ((row as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
        }.toSet()
        assertTrue(codes.size >= 2, "expected at least 2 different vaccine codes, got $codes")
    }
}
