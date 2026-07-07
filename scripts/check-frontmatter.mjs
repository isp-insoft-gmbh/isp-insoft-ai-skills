#!/usr/bin/env node
// Validate Agent Skill YAML frontmatter without npm deps.
// yq does YAML parsing; this script owns frontmatter extraction + spec checks.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.cwd();
const SKIP_DIRS = new Set(['.git', 'node_modules', 'dist', 'target']);
const NAME_RE = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/;

function usage() {
  return `usage: node scripts/check-frontmatter.mjs [--source|--dist|--paths <file...>]\n`;
}

function failUsage(message) {
  process.stderr.write(`error: ${message}\n${usage()}`);
  process.exit(2);
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), out);
    } else if (entry.name === 'SKILL.md') {
      out.push(path.join(dir, entry.name));
    }
  }
  return out;
}

function sourceFiles() {
  return walk(path.join(ROOT, 'skills')).sort();
}

function distFiles() {
  const skills = path.join(ROOT, 'skills');
  if (!fs.existsSync(skills)) return [];
  const files = [];
  for (const owner of fs.readdirSync(skills, { withFileTypes: true })) {
    if (!owner.isDirectory()) continue;
    const dist = path.join(skills, owner.name, 'dist');
    if (!fs.existsSync(dist)) continue;
    for (const artifact of fs.readdirSync(dist, { withFileTypes: true })) {
      if (!artifact.isDirectory()) continue;
      const file = path.join(dist, artifact.name, 'SKILL.md');
      if (fs.existsSync(file)) files.push(file);
    }
  }
  return files.sort();
}

function frontmatter(file) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  if (lines[0] !== '---') throw new Error('missing opening frontmatter fence');
  const end = lines.indexOf('---', 1);
  if (end < 0) throw new Error('missing closing frontmatter fence');
  return `${lines.slice(1, end).join('\n')}\n`;
}

function parseYaml(yaml) {
  const result = spawnSync('yq', ['-o=json', '.', '-'], {
    input: yaml,
    encoding: 'utf8',
    windowsHide: true,
  });
  if (result.error) {
    throw new Error(`cannot run yq: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout).trim();
    throw new Error(`invalid YAML${detail ? `: ${detail}` : ''}`);
  }
  try {
    return JSON.parse(result.stdout);
  } catch (error) {
    throw new Error(`invalid yq JSON output: ${error.message}`);
  }
}

function validateData(file, data) {
  const errors = [];
  const dir = path.basename(path.dirname(file));
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    return ['frontmatter must be a YAML mapping'];
  }
  if (typeof data.name !== 'string' || !data.name.trim()) {
    errors.push('missing string field: name');
  } else if (!NAME_RE.test(data.name)) {
    errors.push(`invalid skill name: ${JSON.stringify(data.name)}`);
  } else if (dir !== data.name) {
    errors.push(
      `directory/name mismatch: ${JSON.stringify(dir)} != ${JSON.stringify(data.name)}`,
    );
  }
  if (typeof data.description !== 'string' || !data.description.trim()) {
    errors.push('missing string field: description');
  } else if (data.description.length > 1024) {
    errors.push(`description too long: ${data.description.length} > 1024`);
  }
  if ('compatibility' in data) {
    if (typeof data.compatibility !== 'string' || !data.compatibility.trim()) {
      errors.push('compatibility must be a non-empty string');
    } else if (data.compatibility.length > 500) {
      errors.push(`compatibility too long: ${data.compatibility.length} > 500`);
    }
  }
  if (
    'metadata' in data &&
    (typeof data.metadata !== 'object' ||
      data.metadata === null ||
      Array.isArray(data.metadata))
  ) {
    errors.push('metadata must be a mapping');
  }
  return errors;
}

function validateFile(file) {
  try {
    const yaml = frontmatter(file);
    return validateData(file, parseYaml(yaml));
  } catch (error) {
    return [error.message];
  }
}

function parseArgs(args) {
  if (args.includes('--help') || args.includes('-h')) {
    process.stdout.write(usage());
    process.exit(0);
  }
  if (!args.length || args.includes('--source')) return sourceFiles();
  if (args.includes('--dist')) return distFiles();
  const paths = args.indexOf('--paths');
  if (paths >= 0) {
    const files = args.slice(paths + 1);
    if (!files.length) failUsage('--paths needs at least one file');
    return files.map((file) => path.resolve(ROOT, file));
  }
  failUsage(`unknown args: ${args.join(' ')}`);
}

const files = parseArgs(process.argv.slice(2));
let failed = false;
if (!files.length) {
  process.stdout.write('  - no SKILL.md files found\n');
}
for (const file of files) {
  const errors = validateFile(file);
  const rel = path.relative(ROOT, file) || file;
  if (errors.length) {
    failed = true;
    for (const error of errors) process.stdout.write(`  ✗ ${rel}: ${error}\n`);
  } else {
    process.stdout.write(`  ✓ ${rel}: SKILL.md frontmatter OK\n`);
  }
}
process.exit(failed ? 1 : 0);
