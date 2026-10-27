export default async function run(page, ui) {
  const out = {};
  await page.waitForTimeout(2500);

  // Inject a classic script and see if it executes in THIS run's context.
  out.inject = await page.evaluate(() => new Promise(resolve => {
    window.__injected = 'not-run';
    const s = document.createElement('script');
    s.textContent = 'window.__injected = "ran";';
    document.documentElement.appendChild(s);
    setTimeout(() => resolve(window.__injected), 400);
  }));

  // Read the globals the app should have defined.
  out.globals = await page.evaluate(() => ({
    GhostApp: typeof window.GhostApp,
    GhostCrypto: typeof window.GhostCrypto,
    jsQR: typeof window.jsQR,
    sodium: typeof window.sodium,
  }));

  // Now click Show Invitation and see if the QR appears — the real functional test.
  await page.selectOption('#screen-jumper', 'show-invitation').catch(() => { });
  await page.waitForTimeout(3000);
  out.qr = await page.evaluate(() => {
    const c = document.getElementById('my-invitation-qr-svg');
    const svg = c && c.querySelector('svg');
    return { hasSvg: !!svg, code: document.getElementById('inv-code-text')?.textContent || null };
  });

  // Functional proof the app ran: does clicking "Generate" / the screen render invitation content?
  out.inviteScreenText = await page.evaluate(() => {
    const s = document.getElementById('screen-show-invitation');
    return s ? s.innerText.replace(/\s+/g, ' ').slice(0, 200) : null;
  });

  return out;
}
