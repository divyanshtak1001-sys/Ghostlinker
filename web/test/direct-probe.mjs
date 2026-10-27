/** Diagnostic: does a same-origin classic script execute on this page at all? */
export default async function run(page) {
  const out = {};
  await page.goto('https://10.10.49.9:8443/js/invitation.js', { waitUntil: 'load' }).catch(e => { out.navError = String(e); });
  await page.waitForTimeout(500);
  out.onDirectFile = await page.evaluate(() => ({
    url: location.href,
    title: document.title,
    bodyStart: document.body ? document.body.textContent.slice(0, 60) : null,
    global: typeof window.GhostInvitation,
  }));
  return out;
}