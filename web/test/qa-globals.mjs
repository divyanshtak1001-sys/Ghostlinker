export default async function run(page, ui) {
  const out = {};
  const errs = [];
  page.on('pageerror', e => errs.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errs.push('console.error: ' + m.text()); });

  await page.waitForTimeout(2000);

  out.globals = await page.evaluate(() => ({
    GhostApp: typeof window.GhostApp,
    GhostCrypto: typeof window.GhostCrypto,
    GhostTransport: typeof window.GhostTransport,
    GhostInvitation: typeof window.GhostInvitation,
    jsQR: typeof window.jsQR,
    sodium: typeof window.sodium,
    bootNote: document.getElementById('boot-error-note')?.textContent || '(empty)',
  }));

  // Click "Show My Invitation" via the real screen switcher and watch the QR.
  const snap = await ui.snapshot();
  const hasSwitcher = /select|combobox/i.test(snap);
  out.hasSwitcher = hasSwitcher;

  await page.selectOption('#screen-jumper', 'show-invitation').catch(() => { });
  await page.waitForTimeout(3000);

  out.afterInvite = await page.evaluate(() => {
    const c = document.getElementById('my-invitation-qr-svg');
    const svg = c && c.querySelector('svg');
    return {
      hasSvg: !!svg,
      svgChildCount: svg ? svg.childElementCount : 0,
      viewBox: svg ? svg.getAttribute('viewBox') : null,
      code: document.getElementById('inv-code-text')?.textContent || null,
      containerHTML: c ? c.innerHTML.slice(0, 120) : null,
    };
  });

  out.errors = errs;
  return out;
}
