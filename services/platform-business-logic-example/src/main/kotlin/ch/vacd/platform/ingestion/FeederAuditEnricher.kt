package ch.vacd.platform.ingestion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per Konkretisierung §13, the original FHIR JSON MUST be retained
 * verbatim inside the Composition's feeder_audit/original_content.
 *
 * The platform talks to EHRbase in FLAT format; in FLAT, feeder_audit lives under
 * `ctx/feeder_audit/...` (canonical: composition.feeder_audit).
 *
 * We populate:
 *   ctx/feeder_audit/originating_system_audit/system_id
 *   ctx/feeder_audit/originating_system_audit/version_id
 *   ctx/feeder_audit/originating_system_audit/time
 *   ctx/feeder_audit/original_content
 *   ctx/feeder_audit/original_content_media_type
 */
object FeederAuditEnricher {
    fun addOriginal(
        flat: JsonObject,
        originalFhirJson: String,
        systemId: String = "ch-vacd-fhir",
        time: String = java.time.OffsetDateTime.now().toString(),
        composerName: String = "CH VACD Platform",
        language: String = "en",
        territory: String = "CH",
        category: String = "433|event",
    ): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        merged.putAll(flat)
        // Composition-level metadata that EHRbase requires but openFHIR doesn't emit.
        merged.putIfAbsent("ctx/language", JsonPrimitive(language))
        merged.putIfAbsent("ctx/territory", JsonPrimitive(territory))
        merged.putIfAbsent("ctx/composer_name", JsonPrimitive(composerName))
        merged.putIfAbsent("ctx/category", JsonPrimitive(category))
        merged.putIfAbsent("ctx/time", JsonPrimitive(time))
        // Feeder audit — the audit trail required by Konkretisierung §13.
        // EHRbase's flat encoder: `<template>/_feeder_audit/...` lands at
        // composition.feeder_audit.
        val tplKey = (flat.keys.firstOrNull { it.contains("/") } ?: "")
            .substringBefore("/")
        if (tplKey.isNotEmpty()) {
            merged.putIfAbsent("$tplKey/_feeder_audit/originating_system_audit|system_id", JsonPrimitive(systemId))
            merged.putIfAbsent("$tplKey/_feeder_audit/originating_system_audit|version_id", JsonPrimitive("1"))
            merged.putIfAbsent("$tplKey/_feeder_audit/originating_system_audit|time", JsonPrimitive(time))
            merged.putIfAbsent("$tplKey/_feeder_audit/original_content", JsonPrimitive(originalFhirJson))
            merged.putIfAbsent("$tplKey/_feeder_audit/original_content|formalism", JsonPrimitive("application/fhir+json"))
        }
        // openFHIR's performer mapping emits _other_participation but omits
        // required openEHR RM fields (function, id scheme). Fill them in.
        fixParticipations(merged)

        return JsonObject(merged)
    }

    private fun fixParticipations(merged: LinkedHashMap<String, JsonElement>) {
        val participationKeys = merged.keys.filter { it.contains("_other_participation") }
        val prefixes = participationKeys.map { it.substringBeforeLast("|") }.distinct()
        for (prefix in prefixes) {
            merged.putIfAbsent("$prefix|function", JsonPrimitive("performer"))
            if (!merged.containsKey("$prefix|id_scheme")) {
                merged.putIfAbsent("$prefix|id_scheme", JsonPrimitive("FHIR"))
            }
        }
    }

    /** Extract the embedded original FHIR JSON from a stored Composition (canonical form). */
    fun extractOriginal(composition: JsonElement): String? {
        if (composition !is JsonObject) return null
        val fa = composition["feeder_audit"] as? JsonObject ?: return null
        return (fa["original_content"] as? JsonPrimitive)?.content
            ?: ((fa["original_content"] as? JsonObject)?.get("value") as? JsonPrimitive)?.content
    }
}
