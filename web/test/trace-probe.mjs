/** Diagnostic: instrument Playwright to see every request/response the page
 *  makes during load, then report what executed. */
export default async function run(page) {
  const log = [];
  page.on('request', r => log.push(`> ${r.method()} ${r.url()} (${r.resourceType()})`));
  page.on('response', r => log.push(`< ${r.status()} ${r.url()} ${r.headers()['content-length'] || ''}`));
  page.on('requestfailed', r => log.push(`! FAILED ${r.url()} ${r.failure() && r.failure().errorText}`));
  page.on('console', m => log.push(`console.${m.type()}: ${m.text().slice(0, 200)}`));
  page.on('pageerror', e => log.push(`pageerror: ${String(e).slice(0, 300)}`));
  page.on('framenavigated', f => log.push(`nav: ${f.url()}`));

  await page.goto('https://10.10.49.9:8443/?trace=1', { waitUntil: 'load' });
  await page.waitForTimeout(2500);

  const state = await page.evaluate(() => ({
    url: location.href,
    readyState: document.readyState,
    probe: window.__probeScriptRan || 0,
    end: window.__endScriptRan || 0,
    inv: typeof window.GhostInvitation,
    decoder: typeof window.jsQR,
  }));
  return { state, log };
}