package ch.vacd.platform.ingestion

import ch.vacd.platform.PrettyJson
import ch.vacd.platform.clients.EhrbaseClient
import ch.vacd.platform.clients.HapiClient
import ch.vacd.platform.clients.OpenFhirClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private const val CH_VACD_BUNDLE_PROFILE =
    "http://fhir.ch/ig/ch-vacd/StructureDefinition/ch-vacd-document-immunization-administration"

data class IngestResult(
    val compositionUid: String,
    val ehrId: String,
    val patientId: String,
    val practitionerIds: List<String>,
    val organizationIds: List<String>,
    val immunizationCount: Int,
    val intermediateFlat: JsonObject,
    val enrichedFlat: JsonObject,
    val originalFhirJson: String,
)

class ImmunizationIngest(
    private val hapi: HapiClient,
    private val openFhir: OpenFhirClient,
    private val cdr: EhrbaseClient,
    private val templateId: String = "ch-vacd-immunization administration.v1-alpha",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun ingest(input: JsonElement): IngestResult {
        val peeled = BundleExtractor.peel(input)
        peeled.immunizations.forEach { validateStatus(it) }

        val patientId = hapi.createResource("Patient", hapi.stripId(peeled.patient))
        val practitionerIds = peeled.practitioners.map { hapi.createResource("Practitioner", hapi.stripId(it)) }
        val organizationIds = peeled.organizations.map { hapi.createResource("Organization", hapi.stripId(it)) }

        val ehrId = cdr.findOrCreateEhr(patientId)

        // V2 FHIRconnect mapping starts at COMPOSITION level — send the full Bundle.
        // openFHIR resolves urn:uuid references and navigates section→entry internally.
        val bundle = ensureBundleProfile(input as JsonObject)
        val bundleText = PrettyJson.encodeToString(JsonObject.serializer(), bundle)

        val flat = openFhir.toOpenEhr(bundleText, flat = true)
        val enriched = FeederAuditEnricher.addOriginal(flat, bundleText)

        val flatText = PrettyJson.encodeToString(JsonObject.serializer(), enriched)
        val compositionUid = cdr.postCompositionFlat(ehrId, flatText, templateId)
        log.info("Stored Composition uid={} ehrId={} patientId={} immunizations={}",
            compositionUid, ehrId, patientId, peeled.immunizations.size)

        return IngestResult(
            compositionUid = compositionUid,
            ehrId = ehrId,
            patientId = patientId,
            practitionerIds = practitionerIds,
            organizationIds = organizationIds,
            immunizationCount = peeled.immunizations.size,
            intermediateFlat = flat,
            enrichedFlat = enriched,
            originalFhirJson = bundleText,
        )
    }

    private fun validateStatus(imm: JsonObject) {
        val s = (imm["status"] as? JsonPrimitive)?.content
        if (s != "completed") {
            throw UnprocessableException("Immunization.status must be 'completed' for the tracer bullet (was '$s')")
        }
    }

    private fun ensureBundleProfile(bundle: JsonObject): JsonObject {
        val meta = bundle["meta"] as? JsonObject
        val profiles = (meta?.get("profile") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        if (profiles != null && profiles.contains(CH_VACD_BUNDLE_PROFILE)) return bundle
        val newMeta = buildJsonObject {
            meta?.forEach { (k, v) -> if (k != "profile") put(k, v) }
            put("profile", buildJsonArray {
                add(JsonPrimitive(CH_VACD_BUNDLE_PROFILE))
                profiles?.filter { it != CH_VACD_BUNDLE_PROFILE }?.forEach { add(JsonPrimitive(it)) }
            })
        }
        val out = LinkedHashMap<String, JsonElement>()
        out.putAll(bundle)
        out["meta"] = newMeta
        return JsonObject(out)
    }
}

class UnprocessableException(message: String) : RuntimeException(message)
