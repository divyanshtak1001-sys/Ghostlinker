/** Diagnostic: capture frame/context for the main document to see whether a
 *  navigation is in flight, and read the served HTML headers. */
export default async function run(page) {
  const frames = page.frames().map(f => ({ url: f.url(), name: f.name() }));
  const html = await page.evaluate(async () => (await fetch('/', { cache: 'no-store' })).text());
  return {
    frames,
    htmlHead: html.slice(0, 120),
    htmlTail: html.slice(-160),
    hasInlineProbe: html.includes('__probeScriptRan'),
    htmlLen: html.length,
  };
}