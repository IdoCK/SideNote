import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, writeFileSync, realpathSync, existsSync } from 'node:fs';
import { resolve, relative, isAbsolute, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
function inside(root, name) {
  if (typeof name !== 'string' || !name || isAbsolute(name) || name.includes('\\')) throw Error(`Unsafe path: ${name}`);
  const path = resolve(root, name);
  const rel = relative(realpathSync(root), realpathSync(path));
  if (rel.startsWith('..') || isAbsolute(rel)) throw Error(`Path escapes repository: ${name}`);
  return path;
}
export function sourceFingerprint(root) {
  const paths = ['app/build.gradle.kts'];
  for (const name of ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties']) {
    if (existsSync(resolve(root, name))) paths.push(name);
  }
  function walk(dir) {
    for (const entry of readdirSync(resolve(root, dir), { withFileTypes: true })) {
      const name = `${dir}/${entry.name}`;
      if (entry.isDirectory()) walk(name);
      else if (entry.isFile()) paths.push(name);
      else throw Error(`Unsupported source entry: ${name}`);
    }
  }
  for (const entry of readdirSync(resolve(root, 'app/src'), { withFileTypes: true })) {
    if (['test', 'androidTest', 'testFixtures'].includes(entry.name)) continue;
    if (entry.isDirectory()) walk(`app/src/${entry.name}`);
    else if (entry.isFile()) paths.push(`app/src/${entry.name}`);
    else throw Error(`Unsupported source entry: app/src/${entry.name}`);
  }
  const hash = createHash('sha256');
  for (const name of paths.sort()) {
    let bytes = readFileSync(inside(root, name));
    if (/\.(kt|kts|xml|java|json|properties|txt|md|pro|toml)$/i.test(name)) {
      bytes = Buffer.from(bytes.toString('utf8').replace(/\r\n/g, '\n'), 'utf8');
    }
    hash.update(`${name}\0${bytes.length}\0`).update(bytes).update('\0');
  }
  return hash.digest('hex');
}
export function checkPortfolio(root, { seal = false } = {}) {
  const manifestPath = resolve(root, 'portfolio/manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const gradle = readFileSync(resolve(root, 'app/build.gradle.kts'), 'utf8');
  const version = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
  const code = Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1]);
  const errors = [];
  if (manifest.schemaVersion !== 1 || manifest.status !== 'ready') errors.push('Manifest must use schemaVersion 1 and status ready.');
  if (manifest.appVersion !== version || manifest.versionCode !== code) errors.push('Manifest version must match Gradle versionName and versionCode.');
  const date = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value));
  if (!date(manifest.reviewedOn)) errors.push('Manifest reviewedOn date required.');
  const plan = readFileSync(inside(root, manifest.plan), 'utf8');
  if (plan.trim().length < 80 || !/^Status:\s*complete\s*$/im.test(plan) || /- \[ \]/.test(plan)) errors.push('Impact plan must be substantive, Status: complete, with no unfinished checklist items.');
  if (!Array.isArray(manifest.files) || !manifest.files.length) errors.push('Bundle files required.');
  const targets = new Set();
  const references = [];
  let images = 0;
  for (const file of manifest.files ?? []) {
    if (typeof file.target !== 'string' || !file.target || file.target.startsWith('/') || file.target.includes(':') || file.target.includes('\\') || file.target.split('/').some(part => part === '..' || part === '.' || part === '')) errors.push(`Unsafe target: ${file.target}`);
    if (targets.has(file.target)) errors.push(`Duplicate target: ${file.target}`);
    targets.add(file.target);
    const bytes = readFileSync(inside(root, file.source));
    if (['assets/sidenote-demo.html', 'assets/sidenote/page.html'].includes(file.target)) {
      const html = bytes.toString('utf8');
      // The page is inserted into index.html; the demo is served from assets/.
      const base = file.target === 'assets/sidenote/page.html' ? 'https://bundle.invalid/' : 'https://bundle.invalid/assets/sidenote-demo.html';
      const add = ref => {
        ref = ref.trim();
        if (!ref || ref.startsWith('#') || /^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(ref)) return;
        references.push({ source: file.target, target: decodeURIComponent(new URL(ref, base).pathname).replace(/^\//, '') });
      };
      for (const match of html.matchAll(/<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']/gi)) add(match[1]);
      for (const match of html.matchAll(/url\(\s*["']?([^\s)"']+)["']?\s*\)/gi)) add(match[1]);
      for (const match of html.matchAll(/<a\b[^>]*\bhref\s*=\s*["']([^"']+)["']/gi)) add(match[1]);
    }
    if (file.target === 'assets/sidenote-demo.html') {
      const html = bytes.toString('utf8');
      const represented = html.match(/<meta\s+name=["']sidenote-version["']\s+content=["']([^"']+)["']/i)?.[1];
      const visible = html.match(/Android v([\d.]+)/)?.[1];
      if (represented !== version || (visible && visible !== version)) errors.push('Demo version metadata or visible label differs from Gradle.');
    }
    if (file.target === 'assets/sidenote/page.html') {
      const represented = bytes.toString('utf8').match(/<dt>\s*Version\s*<\/dt>\s*<dd>\s*([^<\s]+)\s*<\/dd>/i)?.[1];
      if (represented !== version) errors.push('Page visible Version must match Gradle.');
    }
    const digest = sha(bytes);
    if (seal) file.sha256 = digest;
    else if (file.sha256 !== digest) errors.push(`Stale checksum: ${file.source}`);
    if (/\.(png|jpe?g|webp|gif|avif|svg)$/i.test(file.source) || /\.(png|jpe?g|webp|gif|avif|svg)$/i.test(file.target)) {
      images++;
      if (!['native', 'browser', 'illustration', 'staged'].includes(file.kind)) errors.push(`Image kind required: ${file.source}`);
      if (file.appVersion !== version || !date(file.reviewedOn)) errors.push(`Image version/review date required: ${file.source}`);
      if (typeof file.provenance !== 'string' || file.provenance.trim().length < 12) errors.push(`Image provenance required: ${file.source}`);
    }
  }
  for (const target of ['assets/sidenote-demo.html', 'assets/sidenote/page.html']) {
    if (!targets.has(target)) errors.push(`Required bundle target missing: ${target}`);
  }
  for (const ref of references) {
    if (!targets.has(ref.target)) errors.push(`Undeclared bundle reference: ${ref.source} -> ${ref.target}`);
  }
  if (!images) errors.push('At least one reviewed image is required.');
  const fingerprint = sourceFingerprint(root);
  if (seal) manifest.appSourceSha256 = fingerprint;
  else if (manifest.appSourceSha256 !== fingerprint) errors.push('App source changed: review descriptions, demo, and screenshots even if the version is unchanged.');
  if (errors.length) throw Error(errors.join('\n'));
  if (seal) writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  return manifest;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
    checkPortfolio(root, { seal: process.argv.includes('--seal') });
    console.log('Portfolio bundle verified.');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
