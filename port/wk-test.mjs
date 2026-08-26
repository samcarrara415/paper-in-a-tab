// Boots the site in Playwright WebKit (Safari's engine) with full console
// capture — used to debug iOS-only failures that the simulator can't explain.
// Usage: node wk-test.mjs [urlQuery]
import { webkit } from 'playwright';

const query = process.argv[2] || '?v=262&autoboot=1';
const wk = await webkit.launch();
const ctx = await wk.newContext({ viewport: { width: 390, height: 844 } });
const page = await ctx.newPage();

const interesting = [];
page.on('console', m => {
  const t = m.text();
  if (m.type() === 'error' || /Error|error|exited|Exception|exception|abort|Abort|memory|Memory/.test(t)) {
    interesting.push(m.type().toUpperCase() + ': ' + t.slice(0, 300));
  }
});
page.on('pageerror', e => interesting.push('PAGEERROR: ' + String(e).slice(0, 400)));
page.on('crash', () => interesting.push('PAGE CRASHED'));

await page.goto('https://samcarrara415.github.io/paper-in-a-tab/' + query + '&wk=' + Date.now());

const result = await page.evaluate(() => new Promise(r => {
  const t = Date.now();
  const iv = setInterval(() => {
    const st = document.getElementById('status-console').textContent;
    if (st === 'running' || st === 'failed' || st === 'powered off' || Date.now() - t > 420000) {
      clearInterval(iv);
      r({ state: st, secs: Math.round((Date.now() - t) / 1000),
          last: Array.from(document.querySelectorAll('.console__line')).slice(-6).map(e => e.textContent.slice(0, 260)) });
    }
  }, 5000);
})).catch(e => ({ evalError: String(e).slice(0, 300) }));

console.log(JSON.stringify({ result, interesting: interesting.slice(-12) }, null, 1));
await ctx.close();
await wk.close();
