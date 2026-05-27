package ch.vacd.platform.ingestion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Peels a CH VACD **Immunization Administration Document** into its
 * constituent resources.
 *
 * Strictly enforces the CH VACD exchange contract:
 *   - input MUST be a Bundle
 *   - Bundle.type MUST be "document"
 *   - entry[0].resource MUST be a Composition
 *   - the Composition MUST reference at least one Immunization via section.entry
 *
 * Bare Immunization writes (FHIR REST style) are rejected. The CH VACD
 * Immunization Administration Document profile is the only accepted input
 * shape for this platform.
 *
 * Profile reference:
 *   http://fhir.ch/ig/ch-vacd/StructureDefinition/ch-vacd-document-immunization-administration
 */
data class Peeled(
    val composition: JsonObject,
    val immunizations: List<JsonObject>,
    val patient: JsonObject,
    val practitioners: List<JsonObject>,
    val organizations: List<JsonObject>,
    val locations: List<JsonObject>,
    val practitionerRoles: List<JsonObject>,
)

object BundleExtractor {

    fun peel(input: JsonElement): Peeled {
        if (input !is JsonObject) {
            throw IllegalArgumentException("expected a FHIR Bundle (JSON object), got ${input::class.simpleName}")
        }
        val rt = (input["resourceType"] as? JsonPrimitive)?.content
        if (rt != "Bundle") {
            throw IllegalArgumentException(
                "expected a CH VACD Immunization Administration Document Bundle " +
                "(resourceType=Bundle, type=document); got resourceType=$rt"
            )
        }
        val type = (input["type"] as? JsonPrimitive)?.content
        if (type != "document") {
            throw IllegalArgumentException(
                "Bundle.type must be 'document' for a CH VACD Immunization " +
                "Administration Document; got type=$type"
            )
        }
        val entries = (input["entry"] as? JsonArray)
            ?: throw IllegalArgumentException("Bundle has no entries")
        if (entries.isEmpty()) {
            throw IllegalArgumentException("Bundle has no entries")
        }

        // Index entries by Resource/id and by fullUrl for cross-reference resolution.
        val byRef = HashMap<String, JsonObject>()
        for (e in entries) {
            val entry = e as? JsonObject ?: continue
            val resource = entry["resource"] as? JsonObject ?: continue
            val rtype = (resource["resourceType"] as? JsonPrimitive)?.content ?: continue
            val id = (resource["id"] as? JsonPrimitive)?.content
            if (id != null) {
                byRef["$rtype/$id"] = resource
            }
            val fullUrl = (entry["fullUrl"] as? JsonPrimitive)?.content
            if (fullUrl != null) {
                byRef[fullUrl] = resource
            }
        }

        // entry[0] MUST be Composition.
        val first = (entries[0] as? JsonObject)?.get("resource") as? JsonObject
            ?: throw IllegalArgumentException("Bundle.entry[0] has no resource")
        val firstType = (first["resourceType"] as? JsonPrimitive)?.content
        if (firstType != "Composition") {
            throw IllegalArgumentException(
                "Bundle.entry[0] must be a Composition (CH VACD document profile); " +
                "got entry[0].resource.resourceType=$firstType"
            )
        }
        val composition = first

        // Walk Composition.section[*].entry[*].reference to find Immunizations.
        val sections = composition["section"] as? JsonArray
            ?: throw IllegalArgumentException("Composition has no section")
        val sectionRefs = sections
            .filterIsInstance<JsonObject>()
            .flatMap { (it["entry"] as? JsonArray).orEmpty() }
            .filterIsInstance<JsonObject>()
            .mapNotNull { (it["reference"] as? JsonPrimitive)?.content }

        val immunizations = sectionRefs.mapNotNull { ref ->
            byRef[ref]?.takeIf {
                (it["resourceType"] as? JsonPrimitive)?.content == "Immunization"
            }
        }
        if (immunizations.isEmpty()) {
            throw IllegalArgumentException(
                "Composition does not reference any Immunization in its sections; " +
                "saw section references: $sectionRefs"
            )
        }

        // subject MUST point at a Patient in the Bundle.
        val subjectRef = (composition["subject"] as? JsonObject)
            ?.get("reference")?.let { (it as? JsonPrimitive)?.content }
            ?: throw IllegalArgumentException("Composition.subject.reference missing")
        val patient = byRef[subjectRef]?.takeIf {
            (it["resourceType"] as? JsonPrimitive)?.content == "Patient"
        } ?: throw IllegalArgumentException(
            "Composition.subject ($subjectRef) does not resolve to a Patient in the Bundle"
        )

        // Collect supporting resources by type. These are not required by the
        // peel contract but are forwarded to fhir-server-1 if present.
        val practitioners = mutableListOf<JsonObject>()
        val organizations = mutableListOf<JsonObject>()
        val locations = mutableListOf<JsonObject>()
        val practitionerRoles = mutableListOf<JsonObject>()
        for (e in entries) {
            val resource = (e as? JsonObject)?.get("resource") as? JsonObject ?: continue
            when ((resource["resourceType"] as? JsonPrimitive)?.content) {
                "Practitioner" -> practitioners.add(resource)
                "Organization" -> organizations.add(resource)
                "Location" -> locations.add(resource)
                "PractitionerRole" -> practitionerRoles.add(resource)
            }
        }

        return Peeled(
            composition = composition,
            immunizations = immunizations,
            patient = patient,
            practitioners = practitioners,
            organizations = organizations,
            locations = locations,
            practitionerRoles = practitionerRoles,
        )
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}
