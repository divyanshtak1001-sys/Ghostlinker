/** Diagnostic: is a *fresh page* (new tab in the same browser) able to run its
 *  classic scripts, or is this whole browser instance refusing to execute JS? */
export default async function run(page) {
  const out = {};
  const context = page.context();
  const page2 = await context.newPage();
  try {
    await page2.goto('https://10.10.49.9:8443/?fresh=1', { waitUntil: 'load' });
    out.freshPage = await page2.evaluate(() => ({
      readyState: document.readyState,
      probe: window.__probeScriptRan || 0,
      end: window.__endScriptRan || 0,
      inv: typeof window.GhostInvitation,
      decoder: typeof window.jsQR,
      alias: (document.getElementById('home-alias-text') || {}).textContent,
    }));
  } catch (e) {
    out.freshPageError = String(e);
  }
  return out;
}