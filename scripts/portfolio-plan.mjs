import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const version = readFileSync(resolve(root, 'app/build.gradle.kts'), 'utf8').match(/versionName\s*=\s*"([^"]+)"/)?.[1];
if (!version || !/^[\w.-]+$/.test(version)) throw Error('Valid Gradle versionName required.');
const directory = resolve(root, 'portfolio/plans');
mkdirSync(directory, { recursive: true });
const path = resolve(directory, `${version}.md`);
const text = `# Personal site update — ${version}\n\nStatus: draft\n\nImpact: Describe the app change and what visitors should see.\n\n- [ ] Descriptions: update claims or record why existing copy remains accurate.\n- [ ] Demo: update affected behavior or record verified no impact.\n- [ ] Screenshots: capture current native screens or document reviewed reuse; identify each image and label simulations honestly.\n- [ ] Verify: native version, demo interactions, desktop/mobile page, image provenance, and generated site sync.\n\nEvidence: Record screenshots, checks, and any limitations.\n`;
try { writeFileSync(path, text, { flag: 'wx' }); console.log(`Created ${path}`); }
catch (error) { if (error.code === 'EEXIST') console.log(`Plan already exists; update it for this change: ${path}`); else throw error; }
