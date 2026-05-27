package ch.vacd.platform.bootstrap

import ch.vacd.platform.clients.EhrbaseClient
import ch.vacd.platform.clients.OpenFhirClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

enum class BootstrapState { PENDING, OPT_EHRBASE_DONE, OPT_OPENFHIR_DONE, CONTEXT_DONE, MODEL_DONE, READY, FAILED }

private data class OptSpec(val templateId: String, val classpathFile: String)
private data class YamlSpec(val metadataName: String, val classpathFile: String)

/**
 * One-shot, idempotent bootstrap.
 *
 * Uploads both OPTs (immunization-administration + vaccination-record),
 * both FHIRconnect contexts, and all 4 model YAMLs. The mappings are
 * loaded as a bidirectional unit; only the write path is tested for now.
 */
class Bootstrap(
    private val openFhir: OpenFhirClient,
    private val cdr: EhrbaseClient,
    private val templateId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val state: AtomicReference<BootstrapState> = AtomicReference(BootstrapState.PENDING)
    val lastError: AtomicReference<String?> = AtomicReference(null)

    private val opts = listOf(
        OptSpec("ch-vacd-immunization administration.v1-alpha", "bootstrap/ch-vacd-immunization-administration.v1-alpha.opt"),
        OptSpec("ch-vacd-vaccination-record.v1-alpha", "bootstrap/ch-vacd-vaccination-record.v1-alpha.opt"),
    )

    private val contextFiles = listOf(
        "bootstrap/swiss.vacd.context.yml",
        "bootstrap/swiss.vacd-list.context.yml",
    )

    private val models = listOf(
        YamlSpec("COMPOSITION.encounter.v1", "bootstrap/COMPOSITION.encounter.v1.model.yml"),
        YamlSpec("COMPOSITION.vaccination_list.v0", "bootstrap/COMPOSITION.vaccination_list.v0.model.yml"),
        YamlSpec("ACTION.medication.v1", "bootstrap/ACTION.medication.v1.model.yml"),
        YamlSpec("CLUSTER.medication.v2", "bootstrap/CLUSTER.medication.v2.model.yml"),
    )

    private fun read(path: String): String =
        Bootstrap::class.java.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: throw RuntimeException("classpath resource not found: $path")

    suspend fun run() {
        try {
            waitFor()
            cleanupOldMappings()
            uploadOptsToEhrbase()
            state.set(BootstrapState.OPT_EHRBASE_DONE)
            uploadOptsToOpenFhir()
            state.set(BootstrapState.OPT_OPENFHIR_DONE)
            uploadContexts()
            state.set(BootstrapState.CONTEXT_DONE)
            uploadModels()
            state.set(BootstrapState.MODEL_DONE)
            state.set(BootstrapState.READY)
            log.info("Bootstrap READY")
        } catch (t: Throwable) {
            log.error("Bootstrap failed", t)
            lastError.set(t.message ?: t::class.simpleName)
            state.set(BootstrapState.FAILED)
        }
    }

    private suspend fun waitFor() {
        repeat(60) { attempt ->
            try {
                val (s, body) = openFhir.health()
                if (s.value == 200 && body.contains("UP", ignoreCase = true)) {
                    cdr.listTemplates()
                    return
                }
            } catch (_: Throwable) { /* retry */ }
            log.info("waiting for openFHIR + EHRbase (attempt {})", attempt + 1)
            delay(2_000)
        }
        throw RuntimeException("openFHIR / EHRbase not reachable after 120s")
    }

    private suspend fun cleanupOldMappings() {
        if (openFhir.deleteContext("ch-vacd-immunization.context")) {
            log.info("openFHIR: deleted old V1 context 'ch-vacd-immunization.context'")
        }
        if (openFhir.deleteModel("ACTION.medication.v1")) {
            log.info("openFHIR: deleted old model 'ACTION.medication.v1' (will re-upload V2)")
        }
    }

    private suspend fun uploadOptsToEhrbase() {
        val existing = cdr.listTemplates()
        val presentIds = existing.mapNotNull { entry ->
            (entry as? JsonObject)?.let { (it["template_id"] as? JsonPrimitive)?.content }
        }.toSet()

        for (opt in opts) {
            if (opt.templateId in presentIds) {
                log.info("EHRbase: template '{}' already present, skipping", opt.templateId)
                continue
            }
            val xml = read(opt.classpathFile)
            cdr.uploadOpt(xml)
            log.info("EHRbase: uploaded OPT '{}'", opt.templateId)
        }
    }

    private suspend fun uploadOptsToOpenFhir() {
        val existing = openFhir.listOpts()
        val presentIds = existing.flatMap { entry ->
            val obj = entry as? JsonObject ?: return@flatMap emptyList()
            listOfNotNull(
                (obj["templateId"] as? JsonPrimitive)?.content,
                (obj["originalTemplateId"] as? JsonPrimitive)?.content,
                (obj["displayTemplateId"] as? JsonPrimitive)?.content,
                (obj["template_id"] as? JsonPrimitive)?.content,
                ((obj["metadata"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content,
            )
        }.toSet()

        for (opt in opts) {
            val normalized = opt.templateId.replace(' ', '_')
            if (opt.templateId in presentIds || normalized in presentIds) {
                log.info("openFHIR: template '{}' already present, skipping", opt.templateId)
                continue
            }
            val xml = read(opt.classpathFile)
            openFhir.postOpt(xml)
            log.info("openFHIR: uploaded OPT '{}'", opt.templateId)
        }
    }

    private fun yamlName(entry: JsonObject): String? =
        ((entry["metadata"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content
            ?: (entry["name"] as? JsonPrimitive)?.content

    private suspend fun uploadContexts() {
        val existingCount = openFhir.listContexts().size
        if (existingCount >= contextFiles.size) {
            log.info("openFHIR: {} context(s) already present, skipping", existingCount)
            return
        }
        for (file in contextFiles) {
            val yaml = read(file)
            openFhir.postContextYaml(yaml)
            log.info("openFHIR: uploaded context from {}", file)
        }
    }

    private suspend fun uploadModels() {
        val existing = openFhir.listModels()
        val presentNames = existing.mapNotNull { (it as? JsonObject)?.let(::yamlName) }.toSet()

        for (model in models) {
            if (model.metadataName in presentNames) {
                log.info("openFHIR: model '{}' already present, skipping", model.metadataName)
                continue
            }
            val yaml = read(model.classpathFile)
            openFhir.postModelYaml(yaml)
            log.info("openFHIR: uploaded model '{}'", model.metadataName)
        }
    }
}
