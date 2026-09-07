#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { build } from 'esbuild';

const root = path.resolve(import.meta.dirname, '..');
const target = path.join(root, 'target', 'writing-skills');

fs.rmSync(target, { recursive: true, force: true });
fs.mkdirSync(path.join(target, 'scripts'), { recursive: true });
fs.cpSync(path.join(root, 'SKILL.md'), path.join(target, 'SKILL.md'));
fs.cpSync(path.join(root, 'references'), path.join(target, 'references'), {
  recursive: true,
});

await build({
  entryPoints: [path.join(root, 'scripts', 'check.mjs')],
  outfile: path.join(target, 'scripts', 'check.mjs'),
  bundle: true,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  banner: {
    js: "import { createRequire } from 'node:module'; const require = createRequire(import.meta.url);",
  },
  legalComments: 'eof',
});

fs.chmodSync(path.join(target, 'scripts', 'check.mjs'), 0o755);
