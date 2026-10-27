/** Diagnostic: is script execution simply frozen at `interactive` in this page? */
export default async function run(page) {
  const before = await page.evaluate(() => ({ readyState: document.readyState }));
  await page.waitForTimeout(3000);
  const after = await page.evaluate(() => ({ readyState: document.readyState, hasBody: !!document.body }));
  return { before, after };
}