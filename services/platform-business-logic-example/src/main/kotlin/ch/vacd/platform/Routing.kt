package ch.vacd.platform

import ch.vacd.platform.bootstrap.Bootstrap
import ch.vacd.platform.bootstrap.BootstrapState
import ch.vacd.platform.clients.EhrbaseClient
import ch.vacd.platform.clients.HapiClient
import ch.vacd.platform.clients.OpenFhirClient
import ch.vacd.platform.demo.demoRoutes
import ch.vacd.platform.ingestion.ImmunizationIngest
import ch.vacd.platform.ingestion.UnprocessableException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.vacd.platform.Routing")
private val fhirJson = ContentType.parse("application/fhir+json")

fun Application.routes(
    cfg: PlatformConfig,
    hapi: HapiClient,
    openFhir: OpenFhirClient,
    cdr: EhrbaseClient,
    ingest: ImmunizationIngest,
    bootstrap: Bootstrap,
    examples: Examples,
) {
    routing {
        get("/healthz") {
            val errors = mutableListOf<String>()
            val hapiOk = try { hapi.metadata().contains("CapabilityStatement"); true }
                catch (t: Throwable) { errors.add("hapi:${t.message}"); false }
            val openFhirOk = try { openFhir.health().first.value == 200 }
                catch (t: Throwable) { errors.add("openfhir:${t.message}"); false }
            val ehrbaseOk = try { cdr.listTemplates(); true }
                catch (t: Throwable) { errors.add("ehrbase:${t.message}"); false }
            val bs = bootstrap.state.get()
            val ok = hapiOk && openFhirOk && ehrbaseOk && bs == BootstrapState.READY
            val payload = buildJsonObject {
                put("ok", ok)
                put("services", buildJsonObject {
                    put("hapi", hapiOk)
                    put("openfhir", openFhirOk)
                    put("ehrbase", ehrbaseOk)
                })
                put("bootstrap", bs.name)
                bootstrap.lastError.get()?.let { put("bootstrap_error", it) }
                if (errors.isNotEmpty()) put("errors", buildJsonArray { errors.forEach { add(JsonPrimitive(it)) } })
            }
            call.respondText(
                PrettyJson.encodeToString(JsonObject.serializer(), payload),
                ContentType.Application.Json,
                if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }

        get("/metadata") {
            val cap = capabilityStatement(cfg)
            call.respondText(
                PrettyJson.encodeToString(JsonObject.serializer(), cap),
                fhirJson,
                HttpStatusCode.OK,
            )
        }

        get("/examples") {
            val names = examples.list()
            val payload = buildJsonObject {
                put("examples", buildJsonArray {
                    names.forEach { (slug, label) ->
                        add(buildJsonObject {
                            put("slug", slug)
                            put("label", label)
                        })
                    }
                })
            }
            call.respondText(
                PrettyJson.encodeToString(JsonObject.serializer(), payload),
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }

        get("/examples/{slug}") {
            val slug = call.parameters["slug"] ?: return@get call.respondText(
                """{"error":"missing slug"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            val body = examples.read(slug)
            if (body == null) {
                call.respondText("""{"error":"unknown example: $slug"}""",
                    ContentType.Application.Json, HttpStatusCode.NotFound)
            } else {
                call.respondText(body, fhirJson, HttpStatusCode.OK)
            }
        }

        post("/Immunization") {
            val raw = call.receiveText()
            val parsed = try { Json.parseToJsonElement(raw) }
            catch (t: Throwable) {
                return@post call.respondText(
                    operationOutcome("error", "invalid", t.message ?: "invalid JSON"),
                    fhirJson, HttpStatusCode.BadRequest)
            }
            try {
                val result = ingest.ingest(parsed)
                call.response.header(HttpHeaders.Location, "/Immunization/${result.compositionUid}")
                val payload = buildJsonObject {
                    put("compositionUid", result.compositionUid)
                    put("ehrId", result.ehrId)
                    put("patientId", result.patientId)
                    put("immunizationCount", result.immunizationCount)
                    put("practitionerIds", buildJsonArray { result.practitionerIds.forEach { add(JsonPrimitive(it)) } })
                    put("organizationIds", buildJsonArray { result.organizationIds.forEach { add(JsonPrimitive(it)) } })
                }
                call.respondText(
                    PrettyJson.encodeToString(JsonObject.serializer(), payload),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            } catch (e: UnprocessableException) {
                call.respondText(
                    operationOutcome("error", "business-rule", e.message ?: "unprocessable"),
                    fhirJson, HttpStatusCode.UnprocessableEntity)
            } catch (e: IllegalArgumentException) {
                call.respondText(
                    operationOutcome("error", "invalid", e.message ?: "invalid input"),
                    fhirJson, HttpStatusCode.BadRequest)
            } catch (e: Throwable) {
                log.error("ingest failed", e)
                call.respondText(
                    operationOutcome("error", "exception", e.message ?: e::class.simpleName ?: "error"),
                    fhirJson, HttpStatusCode.BadGateway)
            }
        }

        demoRoutes(ingest, cdr, examples)
    }
}

private fun capabilityStatement(cfg: PlatformConfig): JsonObject = buildJsonObject {
    put("resourceType", "CapabilityStatement")
    put("status", "active")
    put("date", "2026-05-16")
    put("kind", "instance")
    put("software", buildJsonObject {
        put("name", "CH VACD Platform Business Logic Example")
        put("version", "0.1.0")
    })
    put("implementation", buildJsonObject {
        put("description", "Pattern A Platform Business Logic Example (tracer bullet)")
    })
    put("fhirVersion", "4.0.1")
    put("format", buildJsonArray { add(JsonPrimitive("application/fhir+json")) })
    put("rest", buildJsonArray {
        add(buildJsonObject {
            put("mode", "server")
            put("resource", buildJsonArray {
                add(buildJsonObject {
                    put("type", "Immunization")
                    put("profile", "http://fhir.ch/ig/ch-vacd/StructureDefinition/ch-vacd-immunization")
                    put("interaction", buildJsonArray {
                        add(buildJsonObject { put("code", "create") })
                    })
                })
            })
        })
    })
}

private fun operationOutcome(severity: String, code: String, diagnostics: String): String {
    val obj = buildJsonObject {
        put("resourceType", "OperationOutcome")
        put("issue", buildJsonArray {
            add(buildJsonObject {
                put("severity", severity)
                put("code", code)
                put("diagnostics", diagnostics)
            })
        })
    }
    return PrettyJson.encodeToString(JsonObject.serializer(), obj)
}
