#!/usr/bin/env node
// Lightweight skill verify: SKILL.md has YAML frontmatter with a `name` that
// matches the directory and a `description`. This is the real `verify` for
// markdown/script skills (skills with a build step verify via their toolchain).
import fs from 'node:fs';
import path from 'node:path';

const CWD = process.cwd();
const name = path.basename(CWD); // a skill's name is its dir name
const md = fs.readFileSync(path.join(CWD, 'SKILL.md'), 'utf8');

const fm = md.match(/^---\r?\n([\s\S]*?)\r?\n---/);
const failExit = (msg) => {
  process.stderr.write(`  ✗ ${name}: ${msg}\n`);
  process.exit(1);
};

if (!fm) failExit('SKILL.md missing frontmatter');
if (!new RegExp(`^name:\\s*${name}\\s*$`, 'm').test(fm[1]))
  failExit(`frontmatter name must equal "${name}"`);
if (!/^description:/m.test(fm[1])) failExit('frontmatter missing description');
process.stdout.write(`  ✓ ${name}: SKILL.md frontmatter OK\n`);
