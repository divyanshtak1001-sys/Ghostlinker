/** Diagnostic: the server sends Clear-Site-Data on every response. Test whether
 *  that header is what stops scripts from executing on this page. */
export default async function run(page) {
  const out = {};
  out.initial = await page.evaluate(() => ({ probe: window.__probeScriptRan || 0, inv: typeof window.GhostInvitation }));

  // 1. Remove the Clear-Site-Data meta effect test: inline script through DOM.
  out.inlineAfterLoad = await page.evaluate(() => new Promise(resolve => {
    const s = document.createElement('script');
    s.textContent = 'window.__late = 42;';
    document.body.appendChild(s);
    setTimeout(() => resolve(window.__late || 0), 300);
  }));

  // 2. Re-run a classic script whose Clear-Site-Data arrives on a request made
  //    AFTER the page finished loading.
  out.externalAfterLoad = await page.evaluate(() => new Promise(resolve => {
    const s = document.createElement('script');
    s.src = 'js/invitation.js?late=1';
    s.onload = () => setTimeout(() => resolve({ loaded: true, inv: typeof window.GhostInvitation }), 400);
    s.onerror = () => resolve({ loaded: false, inv: typeof window.GhostInvitation });
    document.body.appendChild(s);
  }));

  return out;
}