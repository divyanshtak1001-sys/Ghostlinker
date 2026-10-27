/** Diagnostic: run invitation.js inside the real page and report the outcome. */
export default async function run(page) {
  return page.evaluate(async () => {
    const text = await (await fetch('js/invitation.js', { cache: 'no-store' })).text();
    const result = { globalBefore: typeof window.GhostInvitation, error: null };
    window.addEventListener('error', e => { result.error = String(e.message); }, { once: true });
    try {
      new Function(text).call(window);
      result.globalAfter = typeof window.GhostInvitation;
    } catch (e) {
      result.error = e.name + ': ' + e.message;
    }
    return result;
  });
}