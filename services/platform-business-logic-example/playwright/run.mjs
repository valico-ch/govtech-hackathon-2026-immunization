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

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const EXAMPLES = [
  '01-immunization-administration-boostrix',
  '02-immunization-administration-comirnaty',
  '03-immunization-administration-priorix',
  '04-immunization-administration-v2-2dose-comirnaty',
  '05-immunization-administration-v2-3dose-comirnaty',
  '06-immunization-administration-v2-3dose-mixed',
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
