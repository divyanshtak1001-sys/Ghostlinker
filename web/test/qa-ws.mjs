export default async function run(page, ui) {
  const out = {};
  const wsFrames = [];
  page.on('websocket', ws => {
    out.wsOpened = ws.url();
    ws.on('framereceived', f => wsFrames.push(String(f.payload).slice(0, 200)));
    ws.on('framesent', f => wsFrames.push('SENT: ' + String(f.payload).slice(0, 200)));
  });

  await page.waitForTimeout(4000);
  out.frames = wsFrames.slice(0, 12);
  out.wsUrl = out.wsOpened || null;

  // Trigger an invitation and watch for the server's registered reply.
  await page.selectOption('#screen-jumper', 'show-invitation').catch(() => { });
  await page.waitForTimeout(4000);

  out.afterInvite = await page.evaluate(() => ({
    code: document.getElementById('inv-code-text')?.textContent || null,
    shareLink: document.getElementById('inv-share-link-text')?.textContent || null,
    hasQr: !!(document.getElementById('my-invitation-qr-svg')?.querySelector('svg')),
    expiry: document.getElementById('inv-expiry-sec')?.textContent || null,
  }));

  out.framesAfter = wsFrames.slice(-8);
  return out;
}
