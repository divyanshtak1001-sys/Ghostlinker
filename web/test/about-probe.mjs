/** Diagnostic: with the harness's route interception bypassed, does a script run?
 *  A local script tag that is NOT part of the harness network layer. */
export default async function run(page) {
  const out = {};
  out.blankPage = await page.evaluate(() => {
    const w = window.open('about:blank', '_blank');
    if (!w) return { blocked: true };
    w.document.write('<script>window.__aboutRan = 1;<\\/script>');
    return { aboutRan: w.__aboutRan || 0 };
  });
  return out;
}