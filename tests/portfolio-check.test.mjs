import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { checkPortfolio, sourceFingerprint } from '../scripts/portfolio-check.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'sidenote-portfolio-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const path of ['app/src/main', 'portfolio/plans']) mkdirSync(join(root, path), { recursive: true });
  const write = (path, text) => writeFileSync(join(root, path), text);
  write('app/build.gradle.kts', 'versionName = "1.0.12"\nversionCode = 13');
  write('app/src/main/Main.kt', 'current app');
  write('portfolio/plans/1.0.12.md', '# Review\nStatus: complete\n\n- [x] Reviewed descriptions, demo, screenshots, and desktop/mobile rendering with fictional notes.');
  write('portfolio/screen.png', 'fixture image bytes');
  write('portfolio/demo.html', '<meta name="sidenote-version" content="1.0.12"><title>SideNote demo</title>');
  write('portfolio/page.html', '<main><dt>Version</dt><dd>1.0.12</dd></main>');
  const manifest = { schemaVersion: 1, status: 'ready', appVersion: '1.0.12', versionCode: 13, reviewedOn: '2026-09-17', plan: 'portfolio/plans/1.0.12.md', files: [{ source: 'portfolio/screen.png', target: 'assets/screen.png', kind: 'browser', appVersion: '1.0.12', reviewedOn: '2026-09-17', provenance: 'Fictional browser simulation fixture.' }] };
  manifest.files.push({ source: 'portfolio/demo.html', target: 'assets/sidenote-demo.html' }, { source: 'portfolio/page.html', target: 'assets/sidenote/page.html' });
  write('portfolio/manifest.json', JSON.stringify(manifest));
  checkPortfolio(root, { seal: true });
  const mutate = change => { const m = JSON.parse(readFileSync(join(root, 'portfolio/manifest.json'))); change(m); write('portfolio/manifest.json', JSON.stringify(m)); };
  return { root, write, mutate };
}
test('sealed reviewed bundle passes', t => { const { root } = fixture(t); assert.equal(checkPortfolio(root).appVersion, '1.0.12'); });
test('rejects stale version', t => { const { root, mutate } = fixture(t); mutate(m => m.versionCode = 12); assert.throws(() => checkPortfolio(root), /version must match/); });
test('rejects changed image bytes', t => { const { root, write } = fixture(t); write('portfolio/screen.png', 'changed image'); assert.throws(() => checkPortfolio(root), /Stale checksum/); });
test('rejects missing image provenance even when sealing', t => { const { root, mutate } = fixture(t); mutate(m => delete m.files[0].provenance); assert.throws(() => checkPortfolio(root, { seal: true }), /provenance required/); });
test('rejects same-version source changes', t => { const { root, write } = fixture(t); write('app/src/main/Main.kt', 'changed app without bump'); assert.throws(() => checkPortfolio(root), /App source changed/); });
test('rejects unfinished impact plan', t => { const { root, write } = fixture(t); write('portfolio/plans/1.0.12.md', 'Status: draft\n- [ ] Screenshots must be reviewed and current descriptions and browser behavior checked before completion.'); assert.throws(() => checkPortfolio(root, { seal: true }), /Impact plan/); });
test('source fingerprint normalizes text CRLF but preserves binary bytes', t => {
  const { root, write } = fixture(t);
  write('app/src/main/Main.kt', 'first\nsecond\n');
  const lf = sourceFingerprint(root);
  write('app/src/main/Main.kt', 'first\r\nsecond\r\n');
  assert.equal(sourceFingerprint(root), lf);
  write('app/src/main/icon.png', Buffer.from('binary\r\n'));
  const binary = sourceFingerprint(root);
  write('app/src/main/icon.png', Buffer.from('binary\n'));
  assert.notEqual(sourceFingerprint(root), binary);
});
test('requires page and demo bundle targets', t => { const { root, mutate } = fixture(t); mutate(m => m.files = m.files.filter(f => f.target !== 'assets/sidenote/page.html')); assert.throws(() => checkPortfolio(root, { seal: true }), /Required bundle target missing/); });
test('rejects stale visible page version while sealing', t => { const { root, write } = fixture(t); write('portfolio/page.html', '<dt>Version</dt><dd>1.0.11</dd>'); assert.throws(() => checkPortfolio(root, { seal: true }), /Page visible Version/); });
test('rejects stale visible demo version while sealing', t => { const { root, write } = fixture(t); write('portfolio/demo.html', '<meta name="sidenote-version" content="1.0.12">Android v1.0.11'); assert.throws(() => checkPortfolio(root, { seal: true }), /Demo version/); });
test('dependency configuration changes invalidate same version', t => {
  const { root, write } = fixture(t);
  mkdirSync(join(root, 'gradle'));
  write('gradle/libs.versions.toml', '[versions]\ncompose = "1.0"\n');
  checkPortfolio(root, { seal: true });
  write('gradle/libs.versions.toml', '[versions]\ncompose = "2.0"\n');
  assert.throws(() => checkPortfolio(root), /App source changed/);
});
test('production source sets are fingerprinted while test sets are excluded', t => {
  const { root, write } = fixture(t);
  mkdirSync(join(root, 'app/src/release'));
  write('app/src/release/Release.kt', 'production');
  checkPortfolio(root, { seal: true });
  mkdirSync(join(root, 'app/src/androidTest'));
  write('app/src/androidTest/Test.kt', 'test only');
  assert.doesNotThrow(() => checkPortfolio(root));
  write('app/src/release/Release.kt', 'changed production');
  assert.throws(() => checkPortfolio(root), /App source changed/);
});
test('rejects undeclared screenshot reference', t => {
  const { root, write } = fixture(t);
  write('portfolio/page.html', '<dt>Version</dt><dd>1.0.12</dd><img src="assets/missing.png">');
  assert.throws(() => checkPortfolio(root, { seal: true }), /Undeclared bundle reference.*missing.png/);
});
test('resolves demo fonts against assets and accepts declared page links', t => {
  const { root, write, mutate } = fixture(t);
  write('portfolio/font.woff2', 'font fixture');
  mutate(m => m.files.push({ source: 'portfolio/font.woff2', target: 'assets/font.woff2' }));
  write('portfolio/demo.html', '<meta name="sidenote-version" content="1.0.12"><style>@font-face{src:url(./font.woff2)}</style>');
  write('portfolio/page.html', '<dt>Version</dt><dd>1.0.12</dd><img src="assets/screen.png"><a href="assets/sidenote-demo.html">Demo</a><a href="#work">Back</a><img src="data:image/png;base64,AA"><a href="https://example.com">External</a>');
  assert.doesNotThrow(() => checkPortfolio(root, { seal: true }));
  mutate(m => m.files = m.files.filter(f => f.target !== 'assets/font.woff2'));
  assert.throws(() => checkPortfolio(root, { seal: true }), /Undeclared bundle reference.*font.woff2/);
});
