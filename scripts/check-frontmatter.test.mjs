import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

const ROOT = path.resolve(import.meta.dirname, '..');
const CLI = path.join(ROOT, 'scripts', 'check-frontmatter.mjs');

function run(args, cwd = ROOT) {
  return spawnSync(process.execPath, [CLI, ...args], {
    cwd,
    encoding: 'utf8',
  });
}

function tempSkill(name, text) {
  const root = mkdtempSync(path.join(tmpdir(), 'skill-frontmatter-'));
  const dir = path.join(root, name);
  mkdirSync(dir);
  const file = path.join(dir, 'SKILL.md');
  writeFileSync(file, text);
  return { dir: root, file };
}

test('accepts multiline YAML frontmatter with template tokens', () => {
  const { dir, file } = tempSkill(
    'fxdriver',
    `---
name: fxdriver
description:
  "Drive JavaFX desktop apps through fxdriver JSON-RPC: attach to a running JVM,
  inspect snapshots, and use @project.version@ safely."
compatibility:
  "fxdriver @project.version@. Requires JDK @maven.compiler.release@+."
metadata:
  version: "@javafx.version@"
---

# fxdriver
`,
  );
  try {
    const result = run(['--paths', file]);

    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.match(result.stdout, /SKILL\.md frontmatter OK/);
    assert.equal(result.stderr, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('rejects malformed YAML frontmatter', () => {
  const { dir, file } = tempSkill(
    'bad',
    `---
name: bad
metadata:
  a: [
---

# bad
`,
  );
  try {
    const result = run(['--paths', file]);

    assert.equal(result.status, 1);
    assert.match(result.stdout, /invalid YAML/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('discovers source skill artifacts but skips dist and target', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'skill-root-'));
  try {
    mkdirSync(path.join(root, 'skills', 'alpha'), { recursive: true });
    mkdirSync(
      path.join(root, 'skills', 'owner', 'src', 'main', 'markdown', 'beta'),
      {
        recursive: true,
      },
    );
    mkdirSync(path.join(root, 'skills', 'alpha', 'dist', 'broken'), {
      recursive: true,
    });
    writeFileSync(
      path.join(root, 'skills', 'alpha', 'SKILL.md'),
      '---\nname: alpha\ndescription: Alpha skill.\n---\n\n# alpha\n',
    );
    writeFileSync(
      path.join(
        root,
        'skills',
        'owner',
        'src',
        'main',
        'markdown',
        'beta',
        'SKILL.md',
      ),
      '---\nname: beta\ndescription: Beta skill.\n---\n\n# beta\n',
    );
    writeFileSync(
      path.join(root, 'skills', 'alpha', 'dist', 'broken', 'SKILL.md'),
      '---\nname: broken\nmetadata:\n  a: [\n---\n',
    );

    const result = run(['--source'], root);

    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.match(result.stdout, /alpha/);
    assert.match(result.stdout, /beta/);
    assert.doesNotMatch(result.stdout, /broken/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
