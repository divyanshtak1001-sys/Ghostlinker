/** Diagnostic: freeze the page at DOMContentLoaded and report the globals, so we
 *  see the state right after the parser ran the classic scripts. */
export default async function run(page) {
  return page.evaluate(async () => {
    // Force a repaint tick so any queued inline script tasks run.
    await new Promise(r => setTimeout(r, 0));
    return {
      readyState: document.readyState,
      probe: window.__probeScriptRan || 0,
      end: window.__endScriptRan || 0,
      inv: typeof window.GhostInvitation,
      decoder: typeof window.jsQR,
      crypto: typeof window.GhostCrypto,
      alias: (document.getElementById('home-alias-text') || {}).textContent,
      currentScreen: (document.querySelector('.screen.active') || {}).id,
    };
  });
}