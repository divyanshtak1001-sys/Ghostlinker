/** Diagnostic: do classic scripts run at all in this page/browser? */
export default async function run(page) {
  const result = {};
  result.readyState = await page.evaluate(() => document.readyState);
  result.headScripts = await page.evaluate(() => document.querySelectorAll('head script').length);
  result.scriptTags = await page.evaluate(() =>
    [...document.querySelectorAll('script')].map(s => ({
      src: s.getAttribute('src'), inline: !s.getAttribute('src'), text: (s.textContent || '').slice(0, 40),
    })));

  // Add an inline script through the DOM and see whether it executes.
  result.injectedInlineRan = await page.evaluate(() => new Promise(resolve => {
    const s = document.createElement('script');
    s.textContent = 'window.__injectedInline = true;';
    document.body.appendChild(s);
    setTimeout(() => resolve(!!window.__injectedInline), 300);
  }));

  // Add an external script (served from the same origin) and see whether it runs.
  result.injectedExternal = await page.evaluate(() => new Promise(resolve => {
    const s = document.createElement('script');
    s.src = 'js/invitation.js';
    s.onload = () => setTimeout(() => resolve({ loaded: true, global: typeof window.GhostInvitation }), 300);
    s.onerror = () => resolve({ loaded: false, global: typeof window.GhostInvitation });
    document.body.appendChild(s);
  }));

  return result;
}