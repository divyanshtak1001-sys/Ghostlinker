// End-to-end invitation flow using in-page event dispatch (avoids actionability waits).
export default async function run(page) {
  const steps = [];

  const click = sel => page.evaluate(s => {
    const el = document.querySelector(s);
    if (!el) return 'missing';
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    return 'ok';
  }, sel);

  steps.push('invitations tile: ' + await click('#home-to-invitations-btn'));
  await page.waitForTimeout(500);
  steps.push('show-my-invitation: ' + await click('#pairing-to-myinv-btn'));

  // Wait for register_invitation -> invitation_registered round-trip.
  await page.waitForTimeout(3500);

  const out = await page.evaluate(() => {
    const active = Array.from(document.querySelectorAll("[id^='screen-']"))
      .find(e => e.offsetParent !== null || getComputedStyle(e).display !== 'none');
    return {
      activeScreen: active?.id ?? null,
      code: document.getElementById('inv-code-text')?.textContent?.trim() ?? null,
      shareLink: document.getElementById('inv-share-link-text')?.textContent?.trim() ?? null,
      qrSvgPresent: !!document.querySelector('#my-invitation-qr-svg svg'),
    };
  });

  return { steps, ...out };
}
