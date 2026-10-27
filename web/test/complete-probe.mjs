/** Diagnostic: let the page finish loading naturally, then report the globals. */
export default async function run(page) {
  await page.waitForFunction(() => document.readyState === 'complete', null, { timeout: 30000 }).catch(() => { });
  await page.waitForTimeout(1500);
  return page.evaluate(() => ({
    readyState: document.readyState,
    probe: window.__probeScriptRan || 0,
    end: window.__endScriptRan || 0,
    inv: typeof window.GhostInvitation,
    decoder: typeof window.jsQR,
    crypto: typeof window.GhostCrypto,
    transport: typeof window.GhostTransport,
    bootError: window.GhostBootError,
    title: document.title,
    alias: (document.getElementById('home-alias-text') || {}).textContent,
  }));
}