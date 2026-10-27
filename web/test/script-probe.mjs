/** Diagnostic: is the browser running the exact bytes the server serves? */
export default async function run(page) {
  const html = await page.evaluate(async () => (await fetch('/')).text());
  const firstScript = (html.match(/<script src="([^"]+)"/) || [])[1];
  const fetched = await page.evaluate(async () => (await fetch(document.querySelector('script[src]').getAttribute('src'))).text());
  return {
    scriptTags: await page.evaluate(() => [...document.querySelectorAll('script[src]')].map(s => s.getAttribute('src'))),
    htmlHasUnicodeArrow: html.includes('—'),
    htmlLen: html.length,
    fetchedFirstLen: fetched.length,
    fetchedHead: fetched.slice(0, 30),
  };
}