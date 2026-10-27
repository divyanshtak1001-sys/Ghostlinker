export default async function run(page, ui) {
  const out = { console: [], scriptStates: [], netErr: [] };

  page.on('console', m => out.console.push({ type: m.type(), text: m.text() }));
  page.on('requestfailed', r => out.netErr.push({ url: r.url(), err: r.failure() && r.failure().errorText }));
  page.on('pageerror', e => out.console.push({ type: 'pageerror', text: String(e) }));

  // Attach load/error listeners to every script tag and report their state.
  out.scriptStates = await page.evaluate(() => {
    return [...document.querySelectorAll('script[src]')].map(s => ({
      src: s.getAttribute('src'),
      // A script that executed would have removed nothing; we cannot read exec state directly,
      // so report whether the tag is still connected and its readyState via performance entries.
      connected: s.isConnected,
    }));
  });

  // Re-load scripts manually to see the real failure mode.
  out.manualLoad = await page.evaluate(async () => {
    const results = {};
    for (const src of ['js/invitation.js', 'js/crypto.js', 'js/transport.js', 'js/app.js']) {
      try {
        const res = await fetch(src);
        results[src] = { status: res.status, type: res.headers.get('content-type'), len: (await res.text()).length };
      } catch (e) { results[src] = { error: String(e) }; }
    }
    return results;
  });

  // Ask if a dynamically-inserted classic script executes.
  out.dynamicExec = await page.evaluate(() => new Promise(resolve => {
    const s = document.createElement('script');
    s.textContent = 'window.__probeExec = "yes";';
    document.head.appendChild(s);
    setTimeout(() => resolve({ probeExec: window.__probeExec || 'no' }), 300);
  }));

  return out;
}
