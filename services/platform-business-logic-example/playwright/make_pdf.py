#!/usr/bin/env python3
"""
Compile the Playwright screenshots into a landscape PDF presentation.
"""
from pathlib import Path
from reportlab.lib.pagesizes import landscape, A4
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor, white
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader

HERE = Path(__file__).parent
DEMO_DIR = HERE.parents[2] / "docs" / "demo"
SHOTS = DEMO_DIR / "shots"
OUT = DEMO_DIR / "ch-vacd-pattern-a-tracer-bullet.pdf"

PAGE_W, PAGE_H = landscape(A4)
MARGIN_X = 18 * mm
MARGIN_Y = 14 * mm
BG = HexColor("#0f1218")
PANEL = HexColor("#161a22")
TEXT = HexColor("#e8ecf3")
MUTED = HexColor("#8b94a8")
ACCENT = HexColor("#4f8cff")
ACCENT2 = HexColor("#3fb98f")


def fill_bg(c):
    c.setFillColor(BG)
    c.rect(0, 0, PAGE_W, PAGE_H, stroke=0, fill=1)


def header(c, title, subtitle=None):
    c.setFillColor(ACCENT2)
    c.setFont("Helvetica-Bold", 9)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_Y + 6, "●  CH VACD")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 8)
    c.drawRightString(PAGE_W - MARGIN_X, PAGE_H - MARGIN_Y + 6,
                      "Platform Business Logic Example")
    c.setFillColor(TEXT)
    c.setFont("Helvetica-Bold", 22)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_Y - 18, title)
    if subtitle:
        c.setFillColor(MUTED)
        c.setFont("Helvetica", 11)
        c.drawString(MARGIN_X, PAGE_H - MARGIN_Y - 36, subtitle)


def footer(c, page_no, total):
    from datetime import date
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7.5)
    c.drawString(MARGIN_X, MARGIN_Y - 10,
                 f"Built {date.today().isoformat()} — Swiss GovTech Hackathon 2026 / DigiSanté")
    c.drawRightString(PAGE_W - MARGIN_X, MARGIN_Y - 10, f"{page_no} / {total}")


def fit_image(c, img_path, box):
    x, y, w, h = box
    img = ImageReader(img_path)
    iw, ih = img.getSize()
    scale = min(w / iw, h / ih)
    dw, dh = iw * scale, ih * scale
    dx = x + (w - dw) / 2
    dy = y + (h - dh) / 2
    c.drawImage(img, dx, dy, dw, dh, mask='auto', preserveAspectRatio=False)


def divider(c, y):
    c.setStrokeColor(HexColor("#2a3142"))
    c.setLineWidth(0.6)
    c.line(MARGIN_X, y, PAGE_W - MARGIN_X, y)


def slide_text(c, lines, x, y, font="Helvetica", size=11, color=TEXT, leading=None):
    if leading is None:
        leading = size * 1.45
    c.setFillColor(color)
    c.setFont(font, size)
    for line in lines:
        c.drawString(x, y, line)
        y -= leading
    return y


def slide_title(c, title, subtitle=None):
    fill_bg(c)
    header(c, title, subtitle)


def add_caption(c, text):
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 9)
    c.drawString(MARGIN_X, MARGIN_Y + 6, text)


def screenshot_page(title, subtitle, image_path, caption):
    def renderer(c):
        slide_title(c, title, subtitle)
        img_top_y = PAGE_H - MARGIN_Y - 62
        img_bottom_y = MARGIN_Y + 22
        img_box = (MARGIN_X, img_bottom_y, PAGE_W - 2 * MARGIN_X, img_top_y - img_bottom_y)
        c.setFillColor(PANEL)
        c.setStrokeColor(HexColor("#2a3142"))
        c.setLineWidth(0.7)
        c.roundRect(img_box[0] - 4, img_box[1] - 4, img_box[2] + 8, img_box[3] + 8,
                    6, stroke=1, fill=1)
        fit_image(c, image_path, img_box)
        add_caption(c, caption)
    return renderer


def cover_page(c):
    fill_bg(c)
    c.setFillColor(ACCENT2)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_Y - 10, "●  CH VACD — Swiss Vaccination Showcase")
    c.setFillColor(TEXT)
    c.setFont("Helvetica-Bold", 36)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_Y - 60, "Platform Example")
    c.setFillColor(TEXT)
    c.setFont("Helvetica-Bold", 28)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_Y - 92, "Immunization Write Path — V2 FHIRconnect")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 13)
    c.drawString(MARGIN_X, PAGE_H - MARGIN_Y - 118,
                 "Full FHIR Bundle → COMPOSITION-level openFHIR mapping → openEHR → EHRbase, with ~20 mapped fields.")
    y = PAGE_H - MARGIN_Y - 158
    bullets = [
        "• Platform example (Kotlin + Ktor) — orchestrates the stack",
        "• fhir-server-1 (HAPI 8.8.1) — demographics + directory store (Patient / Practitioner / Organization)",
        "• openFHIR 2.2.3 — bidirectional FHIR ↔ openEHR mapping engine (FHIRconnect V2 YAMLs)",
        "• EHRbase 2.31.0 — openEHR Composition store + AQL",
        "",
        "V2 mapping: COMPOSITION-level, ~20 ACTION fields, multi-immunization Bundles,",
        "  vaccination protocol, adverse reactions, performer, verification status, feeder audit.",
        "",
        "Bidirectional mapping loaded (admin + vaccination-record); write path tested end-to-end.",
    ]
    slide_text(c, bullets, MARGIN_X, y, font="Helvetica", size=12, color=TEXT, leading=18)
    c.setFillColor(MUTED)
    c.setFont("Helvetica-Oblique", 10)
    c.drawString(MARGIN_X, MARGIN_Y + 16,
                 "Hackathon harness — services/platform-business-logic-example/")


def flow_page(c):
    slide_title(c, "The Write Flow (V2)",
                "POST /Immunization — full Bundle in, COMPOSITION-level mapping, multi-immunization support")
    steps = [
        ("1.", "Client", "POSTs a CH VACD Immunization Administration Document Bundle", "to platform /Immunization"),
        ("2.", "Demographics fan-out", "Patient + Practitioner + Organization → fhir-server-1 (HAPI)",
         "Platform captures the HAPI-assigned ids"),
        ("3.", "EHR resolution", "Find-or-create openEHR EHR in EHRbase",
         "Linked via EHR_STATUS.subject.external_ref (namespace=ch-vacd, id=<Patient.id>)"),
        ("4.", "V2 Conversion", "Full Bundle → openFHIR /openfhir/toopenehr (COMPOSITION-level mapping)",
         "openFHIR resolves urn:uuid refs, navigates sections, maps ~20 fields per Immunization"),
        ("5.", "Enrich", "Platform adds ctx/ metadata (language, territory, composer) for EHRbase",
         "feederAudit handled by V2 mapping — putIfAbsent fallback for Konkretisierung §13"),
        ("6.", "Persist", "POST /ehr/{ehrId}/composition?format=FLAT&templateId=…",
         "Returns 201 + compositionUid; one Composition may contain multiple ACTION entries"),
    ]
    y = PAGE_H - MARGIN_Y - 76
    for n, who, what, detail in steps:
        c.setFillColor(ACCENT)
        c.setFont("Helvetica-Bold", 16)
        c.drawString(MARGIN_X, y, n)
        c.setFillColor(TEXT)
        c.setFont("Helvetica-Bold", 12)
        c.drawString(MARGIN_X + 30, y, who)
        c.setFillColor(TEXT)
        c.setFont("Helvetica", 11)
        c.drawString(MARGIN_X + 30, y - 14, what)
        c.setFillColor(MUTED)
        c.setFont("Helvetica-Oblique", 9.5)
        c.drawString(MARGIN_X + 30, y - 26, detail)
        y -= 50
    add_caption(c, "Pseudocode: services/platform-business-logic-example/src/main/kotlin/ch/vacd/platform/ingestion/ImmunizationIngest.kt")


def summary_page(c):
    slide_title(c, "What's Proven",
                "All validated end-to-end against the live stack with integration tests + AQL queries.")
    y = PAGE_H - MARGIN_Y - 70
    c.setFillColor(ACCENT2)
    c.setFont("Helvetica-Bold", 12)
    c.drawString(MARGIN_X, y, "Proven (all green)")
    y -= 18
    proven = [
        "✓ V2 FHIRconnect mapping loaded as bidirectional unit (2 OPTs, 2 contexts, 4 models)",
        "✓ openFHIR 2.2.3 COMPOSITION-level mapping: full Bundle in, ~20 fields per Immunization",
        "✓ Multi-immunization Bundles (2-dose, 3-dose series) → single Composition, multiple ACTIONs",
        "✓ Mixed vaccines (Boostrix Polio + FSME-Immun) in one Composition — different codes per ACTION",
        "✓ AQL roundtrip: vaccineCode, time, ISM transition, doseNumber verified via queries",
        "✓ feeder_audit: V2 mapping embeds original FHIR at COMPOSITION + ACTION level",
        "✓ Performer participation: openFHIR maps actor ref, platform fills required RM fields",
        "✓ V1 single-immunization Bundles still work (backward compatible)",
        "✓ Demographics (Patient/Practitioner/Organization) landed in HAPI from document Bundles",
        "✓ 12/12 tests pass (8 unit + 4 integration)",
    ]
    c.setFillColor(TEXT)
    c.setFont("Helvetica", 10.5)
    for line in proven:
        c.drawString(MARGIN_X + 8, y, line)
        y -= 16

    y -= 12
    c.setFillColor(HexColor("#ffa657"))
    c.setFont("Helvetica-Bold", 12)
    c.drawString(MARGIN_X, y, "Next steps")
    y -= 18
    next_up = [
        "Read path — vaccination-record template + tofhir mapping (loaded, not yet tested)",
        "Profile validation — HAPI FhirValidator + CH VACD StructureDefinitions",
        "Terminology lookups — live tx.fhir.ch/r4 for code system validation",
    ]
    c.setFillColor(TEXT)
    c.setFont("Helvetica", 10.5)
    for line in next_up:
        c.drawString(MARGIN_X + 8, y, line)
        y -= 16
    add_caption(c, "Reference: services/platform-business-logic-example/")


PAGES = [cover_page, flow_page]

# Initial screenshot
initial = SHOTS / "00-initial.png"
if initial.exists():
    PAGES.append(screenshot_page(
        "Demo UI — initial state",
        "/demo loads with the first example pre-populated.",
        initial, "screenshot: 00-initial.png"))

V2_EXAMPLES = [
    ("chvacd-immunizationadministration-6b3a054b-8cb4-44b4-bde8-f79a544f5b00",
     "Mixed vaccines — Boostrix Polio + FSME-Immun",
     "3 immunizations: 2× Boostrix Polio (681) + 1× FSME-Immun Junior (683). Patient: Monika Wegmueller."),
    ("chvacd-immunizationadministration-a16565ea-fdde-495a-9e3b-3634ac7bb304",
     "2-dose Comirnaty series",
     "2 immunizations: Comirnaty COVID-19 (68225). Patient: Max Muster."),
    ("chvacd-immunizationadministration-b6d3e7c8-58ad-4d17-b375-34390a08faec",
     "2-dose Comirnaty series (variant)",
     "2 immunizations: Comirnaty COVID-19 (68225). Patient: Max Muster."),
    ("chvacd-immunizationadministration-ec1548de-55c5-4081-9a96-a638f7d78a77",
     "3-dose Comirnaty 30μg series",
     "3 immunizations: Comirnaty 30μg (68710-01). Patient: Max Muster."),
]

for n, (slug, title, subtitle) in enumerate(V2_EXAMPLES, start=1):
    done_n = n * 2
    img = SHOTS / f"{done_n:02d}-{slug}-done.png"
    if img.exists():
        PAGES.append(screenshot_page(title, subtitle, img, f"screenshot: {img.name}"))

PAGES.append(summary_page)

c = canvas.Canvas(str(OUT), pagesize=landscape(A4))
total = len(PAGES)
for idx, renderer in enumerate(PAGES, start=1):
    renderer(c)
    footer(c, idx, total)
    c.showPage()
c.save()
print(f"wrote {OUT} ({total} pages)")
