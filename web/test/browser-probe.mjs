/**
 * Live HTTPS browser probe for GhostLink (headless Chromium via the
 * browser-automation skill). Drives the real UI over https://10.10.49.9:8443
 * and reports what actually happened. Not part of `npm test`.
 *
 * Usage:
 *   node <skill>/browser.mjs https://10.10.49.9:8443/ --script web/test/browser-probe.mjs
 */

const INVALID_CODE = 'ZZZZ-9999';

export default async function run(page, ui) {
  const report = {};
  const errors = [];
  page.on('pageerror', err => errors.push(String(err)));

  const secure = await page.evaluate(() => ({
    isSecureContext: window.isSecureContext,
    getUserMedia: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
    decoder: typeof window.jsQR,
    invitation: window.GhostInvitation ? window.GhostInvitation.normalize('7K9M-4Q2X') : null,
    normalizesBareCode: window.GhostInvitation ? window.GhostInvitation.normalize('7k9m4q2x') : null,
  }));
  report.secureContext = secure;

  const goto = async screenId => {
    await page.evaluate(id => document.getElementById('screen-jumper').value = id, screenId);
    await page.selectOption('#screen-jumper', screenId);
    await page.waitForTimeout(400);
  };

  const visible = async screenId => page.evaluate(id => {
    const el = document.getElementById('screen-' + id);
    return !!el && el.classList.contains('active');
  }, screenId);

  // ── BUG 1: invitation screen must show a real, server-registered code ──────
  await goto('show-invitation');
  await page.waitForTimeout(900);
  report.invitationScreen = {
    codeShown: await page.evaluate(() => document.getElementById('inv-code-text').textContent),
    shareLink: await page.evaluate(() => document.getElementById('inv-share-link-text').textContent),
    qrHasSvg: await page.evaluate(() => !!document.querySelector('#my-invitation-qr-svg svg')),
  };

  const shown = await page.evaluate(() => document.getElementById('inv-code-text').textContent.trim());
  report.displayedCodeIsValidFormat = !!shown && /^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(shown);

  // ── BUG 1: the displayed code must pass entry validation, hyphen and bare ──
  const enterAndSubmit = async value => {
    await goto('enter-code');
    await page.fill('#invitation-code-input', value);
    await page.waitForTimeout(150);
    const formatted = await page.evaluate(() => document.getElementById('invitation-code-input').value);
    await page.click('#code-continue-btn');
    await page.waitForTimeout(600);
    const banner = await page.evaluate(() => {
      const el = document.getElementById('ghost-error-banner');
      return el && el.style.display !== 'none' ? el.textContent : null;
    });
    return { formatted, banner, leftEnterScreen: !(await visible('enter-code')) };
  };

  report.typedWithHyphen = await enterAndSubmit(shown);
  await goto('home');
  report.typedWithoutHyphen = await enterAndSubmit(shown.replace('-', ''));

  // ── BUG 1: format validation still rejects malformed input ─────────────────
  await goto('enter-code');
  await page.fill('#invitation-code-input', 'ABC');
  await page.click('#code-continue-btn');
  await page.waitForTimeout(300);
  report.shortCode = {
    banner: await page.evaluate(() => document.getElementById('ghost-error-banner')?.textContent || null),
    stillOnScreen: await visible('enter-code'),
  };

  // ── BUG 1: unknown but well-formed code must be resolved by the server ─────
  report.unknownCode = await enterAndSubmit(INVALID_CODE);

  // ── BUG 2: decoder + camera failure path (headless has no camera) ──────────
  await goto('scan-qr');
  await page.click('#start-camera-btn');
  await page.waitForTimeout(2500);
  report.scanner = {
    status: await page.evaluate(() => document.getElementById('camera-status-text').textContent),
    videoVisible: await page.evaluate(() => getComputedStyle(document.getElementById('camera-video-stream')).display),
    buttonLabel: await page.evaluate(() => document.getElementById('start-camera-btn').textContent.trim()),
  };
  await page.click('#scan-qr-cancel-btn');
  await page.waitForTimeout(300);
  report.cameraStoppedAfterCancel = await page.evaluate(() => {
    const v = document.getElementById('camera-video-stream');
    return { display: getComputedStyle(v).display, hasStream: !!v.srcObject };
  });

  // ── BUG 3: the exact failure text the user saw must not appear ─────────────
  report.oldFailureMessageSeen = errors.concat([
    report.typedWithHyphen.banner, report.typedWithoutHyphen.banner, report.shortCode.banner,
  ]).some(t => typeof t === 'string' && /full 8-character code/i.test(t));

  report.pageErrors = errors;
  return report;
}