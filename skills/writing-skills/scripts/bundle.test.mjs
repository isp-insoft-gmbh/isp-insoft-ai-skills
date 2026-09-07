import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

const root = path.resolve(import.meta.dirname, '..');

test('built checker runs without node_modules', (t) => {
  const build = spawnSync(process.execPath, ['scripts/build.mjs'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(build.status, 0, build.stderr || build.stdout);

  const built = path.join(root, 'target', 'writing-skills');
  assert.equal(existsSync(path.join(built, 'package.json')), false);
  assert.equal(existsSync(path.join(built, 'node_modules')), false);

  const temp = mkdtempSync(path.join(tmpdir(), 'writing-skills-bundle-'));
  t.after(() => rmSync(temp, { recursive: true, force: true }));
  const checker = path.join(temp, 'tool', 'check.mjs');
  const target = path.join(temp, 'example');
  mkdirSync(path.dirname(checker), { recursive: true });
  mkdirSync(target);
  cpSync(path.join(built, 'scripts', 'check.mjs'), checker);
  writeFileSync(
    path.join(target, 'SKILL.md'),
    '---\nname: example\ndescription: "Use when testing."\n---\n',
  );

  const result = spawnSync(process.execPath, [checker, target], {
    cwd: temp,
    encoding: 'utf8',
    env: { ...process.env, NODE_PATH: '' },
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.equal(JSON.parse(result.stdout).ok, true);
});
