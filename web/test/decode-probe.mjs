/** Diagnostic: does jsQR.js execute when injected into the page? */
export default async function run(page) {
  return page.evaluate(() => {
    const result = { afterInject: null, error: null, onload: false };
    window.addEventListener('error', e => { result.error = String(e.message); }, { once: true });
    const script = document.createElement('script');
    script.src = 'js/jsQR.js';
    script.onload = () => { result.onload = true; };
    document.head.appendChild(script);
    return new Promise(resolve => {
      setTimeout(() => {
        result.afterInject = typeof window.jsQR;
        resolve(result);
      }, 1500);
    });
  });
}