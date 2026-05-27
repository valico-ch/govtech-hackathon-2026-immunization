// Drives /demo end-to-end via Chromium; captures screenshots for the PDF.
import { chromium } from 'playwright';
import { existsSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Screenshots are a repo deliverable consumed by docs/demo/. Resolve against
// this file's location so the output dir is stable regardless of cwd.
const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(HERE, '../../../docs/demo/shots');
if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true });

const BASE = process.env.BASE_URL || 'http://localhost:8888';
const EXAMPLES = [
  'chvacd-immunizationadministration-6b3a054b-8cb4-44b4-bde8-f79a544f5b00',
  'chvacd-immunizationadministration-a16565ea-fdde-495a-9e3b-3634ac7bb304',
  'chvacd-immunizationadministration-b6d3e7c8-58ad-4d17-b375-34390a08faec',
  'chvacd-immunizationadministration-ec1548de-55c5-4081-9a96-a638f7d78a77',
];

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(`pageerror: ${e.message}`));
  page.on('console', m => { if (m.type() === 'error') errors.push(`console: ${m.text()}`); });

  console.log(`Visiting ${BASE}/demo`);
  await page.goto(`${BASE}/demo`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#fhir-input', { state: 'attached', timeout: 10_000 });
  await page.waitForFunction(
    () => document.querySelector('#fhir-input')?.value?.startsWith('{'),
    null,
    { timeout: 5_000 },
  );

  await page.screenshot({ path: `${OUT}/00-initial.png`, fullPage: true });
  console.log('Captured 00-initial.png');

  let step = 1;
  for (const slug of EXAMPLES) {
    console.log(`-- ${slug} --`);
    await page.selectOption('#exampleSelect', slug);
    // wait for hx-get to populate the textarea
    await page.waitForFunction(
      () => (document.querySelector('#fhir-input')?.value || '').includes('"Bundle"'),
      null,
      { timeout: 10_000 },
    );
    await page.screenshot({ path: `${OUT}/${String(step).padStart(2,'0')}-${slug}-loaded.png`, fullPage: true });
    step++;

    // Click Convert & Store
    await Promise.all([
      page.waitForResponse(r => r.url().includes('/demo/convert') && r.status() === 200, { timeout: 30_000 }),
      page.click('button.primary'),
    ]);
    // wait for FLAT panel to update with code-flat <pre>
    await page.waitForFunction(
      () => document.querySelector('#panel-flat pre.code-flat') !== null,
      null,
      { timeout: 15_000 },
    );
    // Wait for kv-compositionUid to appear (rendered into panel-result)
    await page.waitForSelector('#panel-result #kv-compositionUid', { timeout: 5_000 });
    await page.screenshot({ path: `${OUT}/${String(step).padStart(2,'0')}-${slug}-done.png`, fullPage: true });
    const uid = await page.locator('#panel-result #kv-compositionUid').innerText();
    const ehr = await page.locator('#panel-result #kv-ehrId').innerText();
    console.log(`  compositionUid=${uid}`);
    console.log(`  ehrId=${ehr}`);
    step++;
  }

  // Final summary view
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: `${OUT}/zz-final.png`, fullPage: true });

  await browser.close();
  if (errors.length) {
    console.error(`Browser had errors:\n${errors.join('\n')}`);
    process.exit(1);
  }
  console.log(`OK — screenshots in ${OUT}`);
})().catch(e => { console.error(e); process.exit(1); });
