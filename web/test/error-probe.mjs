/** Diagnostic: evaluate the page's own inline probe script by hand, then see if
 *  the window error listener it installs fires for a later classic script. */
export default async function run(page) {
  const result = await page.evaluate(() => {
    const headScripts = [...document.querySelectorAll('head script')];
    const probe = headScripts.find(s => (s.textContent || '').includes('GhostBootError'));
    let evalError = null;
    try { eval(probe.textContent); } catch (e) { evalError = String(e); }
    return { evalError, listenerInstalled: typeof window.GhostBootError !== 'undefined' };
  });
  result.injectedInline = await page.evaluate(() => new Promise(resolve => {
    const s = document.createElement('script');
    s.textContent = 'window.__late = 7;';
    document.body.appendChild(s);
    setTimeout(() => resolve({ late: window.__late || 0, bootError: window.GhostBootError }), 300);
  }));
  return result;
}