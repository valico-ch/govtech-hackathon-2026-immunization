package ch.vacd.platform.demo

import ch.vacd.platform.Examples
import ch.vacd.platform.PrettyJson
import ch.vacd.platform.clients.EhrbaseClient
import ch.vacd.platform.ingestion.ImmunizationIngest
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

fun Route.demoRoutes(ingest: ImmunizationIngest, cdr: EhrbaseClient, examples: Examples) {
    get("/demo") {
        val defaultSlug = examples.list().firstOrNull()?.first
            ?: return@get call.respondText("no examples available", status = HttpStatusCode.ServiceUnavailable)
        val initialSlug = call.request.queryParameters["example"] ?: defaultSlug
        val initial = examples.read(initialSlug) ?: examples.read(defaultSlug) ?: "{}"
        call.respondHtml(HttpStatusCode.OK) { renderDemoPage(initial, initialSlug, examples.list()) }
    }

    get("/demo/example/{slug}") {
        val slug = call.parameters["slug"] ?: return@get call.respondText("missing slug", status = HttpStatusCode.BadRequest)
        val body = examples.read(slug) ?: return@get call.respondText("unknown example", status = HttpStatusCode.NotFound)
        call.respondText(body, ContentType.Text.Plain, HttpStatusCode.OK)
    }

    post("/demo/convert") {
        val raw = call.receiveText()
        // htmx hx-include sends form-encoded "fhir=..." by default; accept both.
        val payload = if (raw.startsWith("fhir=")) {
            java.net.URLDecoder.decode(raw.substringAfter("fhir="), Charsets.UTF_8)
        } else raw
        val parsed = try { Json.parseToJsonElement(payload) }
        catch (t: Throwable) {
            return@post call.respondHtml(HttpStatusCode.OK) { renderError("Invalid JSON: ${t.message}") }
        }
        val result = try { ingest.ingest(parsed) }
        catch (t: Throwable) {
            return@post call.respondHtml(HttpStatusCode.OK) { renderError(t.message ?: t::class.simpleName ?: "error") }
        }
        val aql = try {
            cdr.aql("SELECT c/uid/value AS uid, c/archetype_details/template_id/value AS template FROM EHR e CONTAINS COMPOSITION c WHERE e/ehr_id/value = '${result.ehrId}'")
        } catch (t: Throwable) { """{"error":"${t.message}"}""" }
        call.respondHtml(HttpStatusCode.OK) {
            renderResultPanels(
                originalFhir = result.originalFhirJson,
                intermediateFlat = PrettyJson.encodeToString(JsonObject.serializer(), result.intermediateFlat),
                enrichedFlat = PrettyJson.encodeToString(JsonObject.serializer(), result.enrichedFlat),
                compositionUid = result.compositionUid,
                ehrId = result.ehrId,
                patientId = result.patientId,
                practitionerIds = result.practitionerIds,
                organizationIds = result.organizationIds,
                aqlResult = aql,
            )
        }
    }
}

private fun HTML.renderDemoPage(initialJson: String, initialSlug: String, exampleList: List<Pair<String, String>>) {
    head {
        meta(charset = "utf-8")
        title("CH VACD — Pattern A Tracer Bullet")
        link(rel = "preconnect", href = "https://fonts.googleapis.com")
        script(src = "https://unpkg.com/htmx.org@1.9.12") { defer = true }
        styleBlock()
    }
    body {
        header {
            div("brand") {
                span("dot") { +"●" }
                strong { +"CH VACD" }
                span("muted") { +"Pattern A — Platform Business Logic Layer (tracer bullet)" }
            }
            nav {
                a(href = "/healthz") { +"healthz" }
                a(href = "/metadata") { +"metadata" }
                a(href = "https://fhir.ch/ig/ch-vacd/", target = "_blank") { +"CH VACD IG ↗" }
            }
        }

        main {
            section("toolbar") {
                label { htmlFor = "exampleSelect"; +"Example:" }
                select {
                    id = "exampleSelect"
                    attributes["onchange"] = "loadExample(this.value)"
                    exampleList.forEach { (slug, label) ->
                        option {
                            value = slug
                            if (slug == initialSlug) selected = true
                            +label
                        }
                    }
                }
                button(classes = "primary") {
                    attributes["hx-post"] = "/demo/convert"
                    attributes["hx-include"] = "#fhir-input"
                    attributes["hx-target"] = "#result-area"
                    attributes["hx-indicator"] = "#busy"
                    attributes["hx-vals"] = "js:{ }"
                    attributes["hx-headers"] = """{"Content-Type":"text/plain"}"""
                    +"Convert & Store ▶"
                }
                span("indicator") { id = "busy"; +"working…" }
            }

            div("grid") {
                div("panel") {
                    div("panel-head") {
                        h3 { +"1 · FHIR Immunization (input)" }
                        span("muted") { +"application/fhir+json" }
                    }
                    textArea {
                        id = "fhir-input"
                        name = "fhir"
                        attributes["spellcheck"] = "false"
                        +initialJson
                    }
                }
                div("panel") {
                    div("panel-head") {
                        h3 { +"2 · openEHR FLAT Composition (after openFHIR)" }
                        span("muted") { +"intermediate" }
                    }
                    div("panel-body placeholder") { id = "panel-flat"; +"FLAT JSON will appear here." }
                }
                div("panel") {
                    div("panel-head") {
                        h3 { +"3 · EHRbase confirmation" }
                        span("muted") { +"AQL roundtrip" }
                    }
                    div("panel-body placeholder") { id = "panel-result"; +"Composition UID, EHR ID, AQL result." }
                }
            }

            div("explain") {
                h4 { +"How this works" }
                ol {
                    li { +"Client POSTs a CH VACD Immunization Administration Document Bundle to "; code { +"POST /Immunization" }; +"." }
                    li { +"The platform fans Patient/Practitioner/Organization to "; code { +"fhir-server-1" }; +" (HAPI). The FHIR server is the demographics + directory store." }
                    li { +"The platform ensures an openEHR EHR exists in "; code { +"EHRbase" }; +", linked to the Patient via "; code { +"EHR_STATUS.subject.external_ref" }; +"." }
                    li { +"The platform sends the full Bundle to "; code { +"openFHIR /openfhir/toopenehr" }; +" — the V2 FHIRconnect mapping converts it to a FLAT openEHR Composition (COMPOSITION-level mapping with ~20 fields)." }
                    li { +"The platform enriches FLAT with "; code { +"ctx/" }; +" metadata (language, territory, composer) that EHRbase requires." }
                    li { +"The platform persists the Composition to EHRbase and returns "; code { +"201 Created" }; +" with "; code { +"compositionUid" }; +"." }
                }
            }

            div(classes = "hidden") { id = "result-area" }
        }

        footer {
            +"Hackathon harness — services: HAPI 8.8.1 · openFHIR 2.2.3 · EHRbase 2.31.0"
        }

        script {
            unsafe {
                +"""
                async function loadExample(slug) {
                  try {
                    const r = await fetch('/demo/example/' + slug);
                    if (!r.ok) return;
                    const txt = await r.text();
                    document.getElementById('fhir-input').value = txt;
                  } catch (e) { console.error('loadExample', e); }
                }
                document.addEventListener('htmx:afterSwap', function(e) {
                  if (e.detail.target && e.detail.target.id === 'result-area') {
                    var area = document.getElementById('result-area');
                    var flat = area.querySelector('[data-slot="flat"]');
                    var result = area.querySelector('[data-slot="result"]');
                    if (flat) {
                      document.getElementById('panel-flat').classList.remove('placeholder');
                      document.getElementById('panel-flat').innerHTML = flat.innerHTML;
                    }
                    if (result) {
                      document.getElementById('panel-result').classList.remove('placeholder');
                      document.getElementById('panel-result').innerHTML = result.innerHTML;
                    }
                    area.innerHTML = '';
                  }
                });
                """.trimIndent()
            }
        }
    }
}

private fun HTML.renderResultPanels(
    originalFhir: String,
    intermediateFlat: String,
    enrichedFlat: String,
    compositionUid: String,
    ehrId: String,
    patientId: String,
    practitionerIds: List<String>,
    organizationIds: List<String>,
    aqlResult: String,
) {
    head { title("result fragment") }
    body {
        div {
            attributes["data-slot"] = "flat"
            pre("code-flat") { +enrichedFlat }
        }
        div {
            attributes["data-slot"] = "result"
            div("kv") {
                div("kv-row") { span("k") { +"compositionUid" }; span("v mono") { id = "kv-compositionUid"; +compositionUid } }
                div("kv-row") { span("k") { +"ehrId" }; span("v mono") { id = "kv-ehrId"; +ehrId } }
                div("kv-row") { span("k") { +"patientId (HAPI)" }; span("v mono") { id = "kv-patientId"; +patientId } }
                if (practitionerIds.isNotEmpty()) {
                    div("kv-row") { span("k") { +"practitionerIds" }; span("v mono") { +practitionerIds.joinToString(", ") } }
                }
                if (organizationIds.isNotEmpty()) {
                    div("kv-row") { span("k") { +"organizationIds" }; span("v mono") { +organizationIds.joinToString(", ") } }
                }
            }
            h4 { +"AQL roundtrip" }
            pre("code-flat small") { +aqlResult }
        }
    }
}

private fun HTML.renderError(msg: String) {
    head { title("error fragment") }
    body {
        div {
            attributes["data-slot"] = "flat"
            div("error") { +"Error: $msg" }
        }
        div {
            attributes["data-slot"] = "result"
            div("error") { +"No composition stored." }
        }
    }
}

private fun HEAD.styleBlock() {
    style {
        unsafe {
            +"""
            *, *::before, *::after { box-sizing: border-box }
            :root {
              --bg: #0f1218;
              --panel: #161a22;
              --panel-2: #1d222d;
              --border: #2a3142;
              --text: #e8ecf3;
              --muted: #8b94a8;
              --accent: #4f8cff;
              --accent-2: #3fb98f;
              --warn: #ffa657;
              --err: #ff6b6b;
              --code: #d6deeb;
            }
            html, body { margin:0; padding:0; background:var(--bg); color:var(--text);
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, sans-serif; }
            header { display:flex; justify-content:space-between; align-items:center;
              padding: 14px 24px; background:var(--panel); border-bottom:1px solid var(--border); }
            header .brand { display:flex; gap:10px; align-items:baseline; }
            header .brand .dot { color: var(--accent-2); font-size: 14px; }
            header nav a { color: var(--muted); margin-left: 16px; text-decoration:none; font-size: 14px; }
            header nav a:hover { color: var(--text); }
            .muted { color: var(--muted); font-size: 13px; }
            main { padding: 20px 24px 48px; max-width: 1400px; margin: 0 auto; }
            .toolbar { display:flex; gap:12px; align-items:center; padding: 8px 0 18px; }
            .toolbar label { color: var(--muted); font-size: 13px; }
            select { background: var(--panel); color: var(--text); border: 1px solid var(--border);
              padding: 8px 12px; border-radius: 6px; font-size: 14px; min-width: 360px; }
            button.primary { background: var(--accent); color: white; border: 0; padding: 9px 18px;
              border-radius: 6px; font-weight: 600; cursor: pointer; font-size: 14px; }
            button.primary:hover { background: #6aa1ff; }
            .indicator { color: var(--muted); font-size: 13px; opacity: 0; transition: opacity .2s; }
            .htmx-request .indicator { opacity: 1; }
            .grid { display:grid; grid-template-columns: 1fr 1fr 1fr; gap: 16px; align-items: stretch; }
            .panel { background: var(--panel); border: 1px solid var(--border); border-radius: 8px;
              display: flex; flex-direction: column; min-height: 520px; }
            .panel-head { display:flex; justify-content:space-between; align-items:baseline;
              padding: 12px 16px; border-bottom: 1px solid var(--border); }
            .panel-head h3 { margin: 0; font-size: 14px; font-weight: 600; }
            .panel-body { padding: 12px 16px; flex: 1; }
            .panel-body.placeholder { color: var(--muted); font-style: italic; display:flex;
              align-items: center; justify-content: center; text-align: center; }
            textarea {
              flex: 1; width: 100%; resize: vertical; background: var(--panel-2); color: var(--code);
              border: 0; padding: 14px 16px; font-family: ui-monospace, "SF Mono", Menlo, monospace;
              font-size: 12.5px; line-height: 1.5; min-height: 460px;
            }
            textarea:focus { outline: 1px solid var(--accent); }
            pre.code-flat { margin: 0; padding: 14px 16px; background: var(--panel-2); color: var(--code);
              font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 12px; line-height: 1.45;
              max-height: 460px; overflow: auto; border-radius: 0 0 8px 8px;
              white-space: pre-wrap; word-break: break-all;
              max-width: 100%; min-width: 0; box-sizing: border-box; }
            pre.code-flat.small { max-height: 200px; font-size: 11.5px; }
            .panel-body, #panel-flat, #panel-result { overflow: hidden; min-width: 0; }
            .panel { min-width: 0; overflow: hidden; }
            .kv { padding: 12px 16px; display:flex; flex-direction: column; gap: 6px; border-bottom: 1px solid var(--border); }
            .kv-row { display:flex; gap: 12px; align-items: baseline; }
            .kv-row .k { color: var(--muted); font-size: 12px; min-width: 130px; }
            .kv-row .v.mono { font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 12.5px; word-break: break-all; }
            h4 { margin: 14px 16px 6px; font-size: 13px; font-weight: 600; color: var(--muted); }
            .error { color: var(--err); padding: 14px 16px; font-family: ui-monospace, monospace; font-size: 12.5px; }
            .explain { margin-top: 22px; background: var(--panel); border: 1px solid var(--border);
              border-radius: 8px; padding: 16px 20px; }
            .explain h4 { margin: 0 0 8px 0; color: var(--text); font-size: 14px; }
            .explain ol { margin: 0; padding-left: 22px; color: var(--muted); font-size: 13.5px; line-height: 1.7; }
            .explain code { background: var(--panel-2); padding: 1px 6px; border-radius: 4px;
              font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 12px; color: var(--code); }
            .hidden { display: none; }
            footer { text-align: center; padding: 24px; color: var(--muted); font-size: 12px; }
            @media (max-width: 1100px) { .grid { grid-template-columns: 1fr; } }
            """.trimIndent()
        }
    }
}
