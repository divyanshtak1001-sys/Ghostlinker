/** Diagnostic: the harness's own README pattern — is the body really the app, or
 *  is another document (about:blank / error page) the one being evaluated? */
export default async function run(page) {
  const info = await page.evaluate(() => ({
    url: location.href,
    origin: location.origin,
    readyState: document.readyState,
    bodyTag: document.body && document.body.tagName,
    bodyChildCount: document.body ? document.body.children.length : -1,
    firstChildId: document.body && document.body.firstElementChild ? document.body.firstElementChild.id : null,
    hasToolbar: !!document.querySelector('.preview-toolbar'),
    hasViewport: !!document.querySelector('.viewport-container'),
    hasJumper: !!document.getElementById('screen-jumper'),
    scriptCount: document.querySelectorAll('script').length,
    headScriptCount: document.querySelectorAll('head script').length,
    // Prove the main world runs JS at all.
    mainWorldAlive: (() => { window.__mw = 123; return window.__mw; })(),
    // Prove a classic script cannot: dispatch a real inline script node.
    iframeTest: (() => {
      const f = document.createElement('iframe');
      f.srcdoc = '<scr' + 'ipt>parent.__iframeRan = 1</scr' + 'ipt>';
      document.body.appendChild(f);
      return 'appended';
    })(),
  }));
  await page.waitForTimeout(700);
  info.iframeRan = await page.evaluate(() => window.__iframeRan || 0);
  return info;
}