// Renders design/icon/*.svg into the AppIcon asset (1024px PNGs) with headless Chromium.
// Usage: node tools/make-icon.mjs
import { execFileSync } from 'node:child_process';
import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = path.join(root, 'design/icon');
const out = path.join(root, 'ios/Leash/Assets.xcassets/AppIcon.appiconset');
const browser = process.env.CHROMIUM ?? 'chromium';
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-icon-'));

for (const [svg, png] of [
  ['leash-icon.svg', 'icon-1024.png'],
  ['leash-icon-dark.svg', 'icon-1024-dark.png'],
  ['leash-icon-tinted.svg', 'icon-1024-tinted.png'],
]) {
  const html = path.join(tmp, svg + '.html');
  fs.writeFileSync(html, `<html><body style="margin:0;background:#000">${fs.readFileSync(path.join(src, svg), 'utf8')}</body></html>`);
  execFileSync(browser, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-device-scale-factor=1', '--window-size=1024,1024', `--screenshot=${path.join(out, png)}`, 'file://' + html],
    { stdio: 'ignore' });
  const head = fs.readFileSync(path.join(out, png)).subarray(16, 24);
  const [w, h] = [head.readUInt32BE(0), head.readUInt32BE(4)];
  if (w !== 1024 || h !== 1024) throw new Error(`${png}: ${w}x${h}, expected 1024x1024`);
  console.log(`${png} <- ${svg}`);
}
fs.rmSync(tmp, { recursive: true });
