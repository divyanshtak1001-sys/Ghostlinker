// Live QA: verify the GhostLink simulator renders and responds to interaction.
export default async function run(page, ui) {
  const results = { steps: [] };

  // 0. Capture console/network health via the page itself.
  results.title = await page.title();
  results.url = page.url();

  // 1. The screen switcher exists and lists screens.
  const snap = await ui.snapshot({ full: false });
  results.hasSelect = /combobox|listbox|select/i.test(snap);
  results.initialBody = await page.evaluate(() => document.body.innerText.length);

  const selectRef = snap.match(/@(e\d+)\s+(combobox|listbox|select)/i)?.[1];
  if (selectRef) results.steps.push("found screen selector");

  // 2. Assert the app's JS state API is mounted.
  results.globals = await page.evaluate(() => ({
    hasGhostApp: typeof window.ghostApp !== "undefined" || typeof window.showScreen === "function" || typeof window.APP !== "undefined",
    hasCrypto: typeof window.GhostCrypto !== "undefined",
    hasTransport: typeof window.GhostTransport !== "undefined",
    hasJsQR: typeof window.jsQR !== "undefined",
    hasSodium: typeof window.sodium !== "undefined",
    keys: Object.keys(window).filter(k => /ghost|screen|app/i.test(k)).slice(0, 20),
  }));

  // 3. Count screen containers present in the DOM.
  results.screenCount = await page.evaluate(() =>
    document.querySelectorAll("[id^='screen-']").length
  );
  results.screenIds = await page.evaluate(() =>
    Array.from(document.querySelectorAll("[id^='screen-']")).map(e => e.id)
  );

  // 4. Live QR check: hit the server QR endpoint the app actually uses.
  results.qrEndpoint = await page.evaluate(async () => {
    const payload = JSON.stringify({ v: 2, pk: "a".repeat(64), s: "b".repeat(32), c: "ABCD-EFGH", h: "localhost", p: "8088", exp: Date.now() + 300000 });
    try {
      const res = await fetch(`/api/qr?text=${encodeURIComponent(payload)}`);
      const txt = await res.text();
      return { status: res.status, isSvg: txt.trim().startsWith("<svg"), len: txt.length };
    } catch (e) { return { error: String(e) }; }
  });

  // 5. Drive the "Show My Invitation" screen and confirm a QR renders in the DOM.
  results.invitation = await page.evaluate(async () => {
    const sel = document.getElementById('screen-jumper');
    if (!sel) return { error: 'no screen-jumper' };
    sel.value = 'show-invitation';
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    await new Promise(r => setTimeout(r, 2500));
    const container = document.getElementById('my-invitation-qr-svg');
    const svg = container && container.querySelector('svg');
    return {
      screenVisible: !!document.querySelector('#screen-show-invitation') || document.body.innerText.includes('Invitation'),
      containerPresent: !!container,
      svgRendered: !!svg,
      svgPaths: svg ? svg.querySelectorAll('path').length : 0,
      containerText: container ? container.innerText.slice(0, 60) : null,
      codeText: (document.getElementById('inv-code-text') || {}).textContent || null,
    };
  });

  return results;
}
