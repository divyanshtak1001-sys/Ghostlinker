/** Diagnostic: inject each served script with Playwright's addScriptTag (which
 *  bypasses anything the page itself does) and report which globals appear. */
export default async function run(page) {
  const urls = await page.evaluate(() => [...document.querySelectorAll('script[src]')].map(s => s.getAttribute('src')));
  const results = [];
  for (const u of urls) {
    const err = await page.addScriptTag({ url: 'https://10.10.49.9:8443/' + u + '?tag=1' })
      .then(() => null).catch(e => String(e));
    const after = await page.evaluate(() => ({
      inv: typeof window.GhostInvitation,
      decoder: typeof window.jsQR,
      crypto: typeof window.GhostCrypto,
      transport: typeof window.GhostTransport,
    }));
    results.push({ u, err, after });
  }
  return results;
}