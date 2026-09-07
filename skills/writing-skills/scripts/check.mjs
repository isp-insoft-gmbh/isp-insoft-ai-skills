#!/usr/bin/env node
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import {
  basename,
  dirname,
  isAbsolute,
  join,
  relative,
  resolve,
} from 'node:path';
import { fileURLToPath } from 'node:url';
import { load as parse } from 'js-yaml';
import { marked } from 'marked';

const frontmatterFence = /^\uFEFF?---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/;
const namePattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const scriptPattern = /\.(?:mjs|cjs|js|ts|sh|bash|nu|py|ps1|cmd|bat)$/i;

function filesUnder(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    if (entry.name === 'node_modules' || entry.name === '.git') return [];
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return filesUnder(path);
    return entry.isFile() ? [path] : [];
  });
}

export function checkSkill(directory) {
  const root = resolve(directory);
  if (!statSync(root).isDirectory())
    throw new Error('Expected a skill directory');
  const skill = join(root, 'SKILL.md');
  const text = readFileSync(skill, 'utf8');
  const issues = [];
  const add = (path, code, message) =>
    issues.push({
      path: relative(root, path).split('\\').join('/'),
      code,
      message,
    });
  const fence = text.match(frontmatterFence);
  if (!fence) {
    add(
      skill,
      'frontmatter.fence',
      'Expected opening and closing YAML frontmatter fences',
    );
  } else {
    let data;
    try {
      data = parse(fence[1]);
    } catch {
      add(
        skill,
        'frontmatter.yaml',
        'Invalid YAML frontmatter, keys, or aliases',
      );
    }
    if (data !== undefined) {
      if (!data || typeof data !== 'object' || Array.isArray(data)) {
        add(skill, 'frontmatter.mapping', 'Frontmatter must be a YAML mapping');
      } else {
        const { name, description } = data;
        if (
          typeof name !== 'string' ||
          name.length > 64 ||
          !namePattern.test(name)
        ) {
          add(
            skill,
            'name.invalid',
            'Name must be 1-64 lowercase letters/digits with single internal hyphens',
          );
        } else if (name !== basename(root)) {
          add(
            skill,
            'name.directory',
            'Name must match directory for Agent Skills portability',
          );
        }
        if (
          typeof description !== 'string' ||
          !description.trim() ||
          description.length > 1024
        ) {
          add(
            skill,
            'description.invalid',
            'Description must be a non-empty string of at most 1024 characters',
          );
        }
        const invocation = data['disable-model-invocation'];
        if (invocation !== undefined && typeof invocation !== 'boolean') {
          add(
            skill,
            'invocation.invalid',
            'disable-model-invocation must be a boolean when present',
          );
        }
      }
    }
  }

  const files = filesUnder(root).sort();
  for (const file of files.filter((path) => path.endsWith('.md'))) {
    const body = readFileSync(file, 'utf8').replace(frontmatterFence, '');
    marked.walkTokens(marked.lexer(body), (token) => {
      if (token.type !== 'link' && token.type !== 'image') return;
      const href = token.href;
      if (
        !href ||
        /^[a-z][a-z\d+.-]*:|^[#/?\\]/i.test(href) ||
        isAbsolute(href)
      )
        return;
      let target;
      try {
        target = decodeURIComponent(href.split(/[?#]/, 1)[0]);
      } catch {
        add(file, 'link.encoding', 'Local link has invalid percent encoding');
        return;
      }
      if (!existsSync(resolve(dirname(file), target))) {
        add(file, 'link.missing', `Missing local link target: ${href}`);
      }
    });
  }

  const scripted = files.some((file) => scriptPattern.test(file));
  const packageFile = join(root, 'package.json');
  if (!existsSync(packageFile)) {
    if (scripted)
      add(
        packageFile,
        'package.missing',
        'Scripted skills require package.json',
      );
  } else {
    let manifest;
    try {
      manifest = JSON.parse(readFileSync(packageFile, 'utf8'));
    } catch {
      manifest = null;
    }
    if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
      add(
        packageFile,
        'package.json',
        'Expected a JSON object in package.json',
      );
    } else if (
      scripted &&
      (typeof manifest.scripts?.test !== 'string' ||
        !/^node\s+--test(?:\s|$)/.test(manifest.scripts.test))
    ) {
      add(
        packageFile,
        'tests.command',
        'scripts.test must invoke node --test directly',
      );
    }
  }
  if (scripted && !files.some((file) => file.endsWith('.test.mjs'))) {
    add(
      packageFile,
      'tests.missing',
      'Scripted skills require at least one .test.mjs file',
    );
  }
  return { skill: root, ok: issues.length === 0, issues };
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    if (process.argv.length !== 3)
      throw new Error('Usage: node scripts/check.mjs <skill-dir>');
    const result = checkSkill(process.argv[2]);
    console.log(JSON.stringify(result, null, 2));
    process.exitCode = result.ok ? 0 : 1;
  } catch (error) {
    console.log(JSON.stringify({ error: error.message }));
    process.exitCode = 2;
  }
}
