// Render an <agent-embed> HTML file the way AgentEmbedView does (same host JS + head tags, pulled from the Swift source)
// and write a page for headless Chromium.   node tools/embed-preview.mjs <widget.html> <out.html> [dark|light]
import fs from 'node:fs';
const [inFile, outFile, mode = 'dark'] = process.argv.slice(2);
const swift = fs.readFileSync(new URL('../ios/Leash/Views/AgentEmbedView.swift', import.meta.url), 'utf8');
const js = swift.match(/static let script = #"""\n([\s\S]*?)\n"""#/)[1];
const headTags = swift.match(/static let headTags = """\n([\s\S]*?)\n"""/)[1].replace(/\\\n/g, '');
const themesSrc = swift.match(/static let themes[^=]*= \[([\s\S]*?)\n    \]\n/)[1];
const themes = {};
for (const m of themesSrc.matchAll(/"(dark|light)": \[([\s\S]*?)\],/g)) themes[m[1]] = Object.fromEntries([...m[2].matchAll(/"([\w-]+)": "([^"]+)"/g)].map(x => [x[1], x[2]]));
const boot = js.replace('__AG_THEMES__', JSON.stringify(themes)).replace('__AG_MODE__', JSON.stringify(mode));
let html = fs.readFileSync(inFile, 'utf8');
const r = html.match(/<head\b[^>]*>/i) || html.match(/<!doctype[^>]*>/i);
// Chromium has no WKUserScript: run the bootstrap as the first head script; stub the message handler and show the height.
const stub = '<script>window.webkit={messageHandlers:{agHost:{postMessage:function(m){document.title="h="+m.height;}}}};<\/script><script>' + boot + '<\/script>';
html = r ? html.replace(r[0], r[0] + '\n' + headTags + stub) : headTags + stub + html;
fs.writeFileSync(outFile, html);
console.log('themes', Object.keys(themes), Object.keys(themes.dark || {}).length, 'vars; wrote', outFile);
