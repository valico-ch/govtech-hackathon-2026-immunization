# GovTech Hackathon 2026 — Digital Immunization Record (VACD)

Dev harness for the Swiss GovTech Hackathon 2026 **VACD** challenge
(28–29 May 2026, FOITT Zollikofen). Clone, open in VS Code with the
Dev Containers extension, `docker compose up`, and you have the
backing services the challenge revolves around. **Your team writes the
platform layer** that mediates between the CH VACD FHIR API and an
openEHR Clinical Data Repository — that's the challenge, not the harness.

## Quick start

1. Open the repo in VS Code and choose **"Reopen in Container"**. The
   dev container ships Java 25, Maven/Gradle, Node 22, Python 3.13,
   Kotlin 2.3.21, Docker, `gh`, and the Claude/Gemini/Codex CLIs.
2. Inside the container terminal:
   ```sh
   docker compose up --wait
   ```
   This builds the FHIR server from source (multi-stage Docker build, no
   local Maven needed) and waits until all services pass their
   healthchecks before returning.
3. Smoke-test a service:
   ```sh
   curl http://fhir-server-1:9111/ch-vacd-api-reference-server/fhir/metadata
   ```
   Should return a `CapabilityStatement`. The dev container shares the
   `vacd-net` bridge with the compose services, so service-name URLs
   resolve directly on macOS/Windows/Linux.

## What you get

| Service          | Port  | Image / build                                       | Notes |
| ---              | ---   | ---                                                 | --- |
| `fhir-server-1`  | 9111  | `services/ch-vacd-api-reference-server/` (HAPI 8.8.1, Spring Boot, H2) | CH VACD reference server with the 18 CH VACD `ResourceProvider`s and the `$export-document` Operation. |
| `fhir-server-2`  | 9112  | same image, second JVM                              | Optional, commented out in `docker-compose.yml`. Enable for producer/consumer split topologies. |
| `ehrbase`        | 8082  | `ehrbase/ehrbase:2.31.0`                            | openEHR CDR. BASIC auth: `ehrbase-user` / `SuperSecretPassword`. |
| `ehrbase-db`     | —     | `ehrbase/ehrbase-v2-postgres:16.2`                  | Postgres for EHRbase. Internal-only. |
| `openfhir`       | 8083  | `openfhir/openfhir:2.2.3` (`linux/amd64`)           | FHIR ⇄ openEHR mapping engine. |
| `openfhir-mongo` | —     | `mongo:7.0`                                         | openFHIR's config store. Internal-only. |

Connection URLs are exported to the dev container as `FHIR_SERVER_1_URL`,
`FHIR_SERVER_2_URL`, `CDR_URL`, `MAPPER_URL` (see
`.devcontainer/devcontainer.json`). The two FHIR servers are
intentionally generic — the harness does **not** assign them
producer/consumer roles; teams decide their own topology.

## What you build

The challenge has three pieces, all participant-built:

- A **producer** that emits a **CH VACD Immunization Administration Document**.
- The **platform layer** that lands it in openEHR and serves it back out.
- A **consumer** that fetches and renders a **CH VACD Vaccination Record Document**.

The harness reserves host ports **3000–3001, 8000–8001, 8080–8081,
8888–8889** for your platform layer — all forwarded by the dev
container. Run your service inside the container terminal; reach it
from your host browser like any localhost service.

A **Platform Business Logic Example** lives at
[`services/platform-business-logic-example/`](services/platform-business-logic-example/)
— a minimal Kotlin/Ktor smoke-test that was used to validate the harness
end-to-end. It is **not** a reference architecture or starting point;
teams should design their own platform layer from scratch. To see it
run alongside the backing services:
```sh
docker compose --profile example up --wait
# then open http://localhost:8888/demo
```
Canonical test Bundles live under [`examples/`](examples/).

## Repo layout

```
.devcontainer/         dev container config + post-create.sh
docker-compose.yml     the backing services above
services/
  ch-vacd-api-reference-server/          HAPI FHIR + CH VACD ResourceProviders (from https://github.com/ralych)
  platform-business-logic-example/       harness smoke-test (Kotlin/Ktor, not a template)
examples/              canonical CH VACD example Bundles
docs/
  challenge/           original hackathon brief + openEHR template
  demo/                rendered PDF and screenshots of the example platform
progress/              branch-scoped chronological progress notes
```

