/** Diagnostic: at DOMContentLoaded, which script globals exist and are they the
 *  ones from the served files (identity check against a fresh evaluation)? */
export default async function run(page) {
  return page.evaluate(async () => {
    const atDCL = {
      jsQR: typeof window.jsQR,
      GhostInvitation: typeof window.GhostInvitation,
      GhostCrypto: typeof window.GhostCrypto,
      GhostTransport: typeof window.GhostTransport,
    };
    const invite = window.GhostInvitation;
    const src = await (await fetch('js/invitation.js', { cache: 'no-store' })).text();
    const probe = {};
    new Function('probe', src + '\n;probe.exports = (typeof module === "object" && module.exports) || null;')
      .call(window, probe);
    return {
      atDCL,
      inviteIsObject: !!invite,
      normalizeWorks: invite ? invite.normalize('7k9m4q2x') : null,
      freshModuleExports: probe.exports ? probe.exports.normalize('7k9m4q2x') : null,
      readyState: document.readyState,
      scriptsInDom: [...document.querySelectorAll('script[src]')].map(s => s.getAttribute('src')),
    };
  });
}