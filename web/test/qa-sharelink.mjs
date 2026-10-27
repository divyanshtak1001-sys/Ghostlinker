// Verify the invitation share link resolves after register_invitation.
export default async function run(page, ui) {
  // Trigger the invitation flow directly through the app's own function.
  await page.evaluate(() => {
    if (typeof window.generateAndRenderInvitation === 'function') {
      window.generateAndRenderInvitation();
    } else if (typeof generateAndRenderInvitation === 'function') {
      generateAndRenderInvitation();
    } else {
      // Fall back to the screen-switcher path.
      const sel = document.getElementById('screen-select');
      if (sel) { sel.value = 'my-invitation'; sel.dispatchEvent(new Event('change')); }
    }
  });
  // Give the server round-trip time to return inviteId.
  await page.waitForTimeout(2500);

  const out = await page.evaluate(() => ({
    shareLink: document.getElementById('inv-share-link-text')?.textContent ?? null,
    code: document.getElementById('inv-code-text')?.textContent ?? null,
    qrSvg: !!document.querySelector('#my-invitation-qr-svg svg'),
  }));
  return out;
}
