import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

const ROOT = path.resolve(import.meta.dirname, '..');
const CLI = path.join(ROOT, 'scripts', 'skills.mjs');

function runSkills(args, extraEnv = {}) {
  return spawnSync(process.execPath, [CLI, ...args], {
    cwd: ROOT,
    encoding: 'utf8',
    env: {
      ...process.env,
      ...extraEnv,
    },
  });
}

test('subcommand help prints usage and exits successfully', () => {
  for (const command of ['install', 'uninstall', 'status']) {
    const result = runSkills([command, '--help']);

    assert.equal(result.status, 0);
    assert.match(result.stdout, /usage: node scripts\/skills\.mjs <command>/);
    assert.equal(result.stderr, '');
  }
});

test('status rejects unknown harness with a friendly error', () => {
  const result = runSkills(['status', '--harness', 'nope']);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /error: unknown harness "nope"/);
  assert.doesNotMatch(result.stderr, /TypeError|ERR_INVALID_ARG_TYPE/);
});

test('install rejects unknown skill before checking build output', () => {
  const harnessRoot = mkdtempSync(path.join(tmpdir(), 'skills-harness-'));
  try {
    const result = runSkills(['install', 'nope', '--harness', 'claude'], {
      SKILLS_HARNESS_CLAUDE: path.join(harnessRoot, 'claude'),
    });

    assert.equal(result.status, 1);
    assert.match(result.stderr, /error: unknown skill "nope"/);
    assert.doesNotMatch(result.stderr, /not built/);
  } finally {
    rmSync(harnessRoot, { recursive: true, force: true });
  }
});

test('install recognizes bundled companion skills before build output exists', () => {
  const harnessRoot = mkdtempSync(path.join(tmpdir(), 'skills-harness-'));
  try {
    const result = runSkills(
      ['install', 'fxdriver-instructions', '--harness', 'claude'],
      {
        SKILLS_HARNESS_CLAUDE: path.join(harnessRoot, 'claude'),
      },
    );

    if (result.status === 1) {
      assert.match(
        result.stderr,
        /fxdriver-instructions: not built\. run: mise run \/\/skills\/fxdriver:build/,
      );
    } else {
      assert.equal(result.status, 0);
      assert.match(result.stdout, /fxdriver-instructions/);
    }
    assert.doesNotMatch(result.stderr, /unknown skill/);
  } finally {
    rmSync(harnessRoot, { recursive: true, force: true });
  }
});

test('list exposes coworker-facing kind, build state, harnesses, and description', () => {
  const result = runSkills(['list']);

  assert.equal(result.status, 0);
  assert.match(result.stdout, /skill\s+kind\s+built\s+harnesses\s+description/);
  assert.match(
    result.stdout,
    /experimentation\s+markdown\s+(yes|no)\s+claude,pi,codex/,
  );
  assert.match(
    result.stdout,
    /isp-start-day\s+markdown\+node\s+(yes|no)\s+claude,pi,codex/,
  );
  assert.match(
    result.stdout,
    /uuid-generator\s+markdown\+node\s+(yes|no)\s+claude,pi,codex/,
  );
  assert.match(
    result.stdout,
    /fxdriver\s+maven\/java\s+(yes|no)\s+claude,pi,codex/,
  );
  assert.match(
    result.stdout,
    /fxdriver-instructions\s+maven\/java\s+(yes|no)\s+claude,pi,codex/,
  );
  assert.match(
    result.stdout,
    /security-scan\s+markdown\s+(yes|no)\s+claude,pi,codex/,
  );
  assert.match(result.stdout, /fxdriver\s+.*Drive and inspect JavaFX apps/);
  assert.match(
    result.stdout,
    /fxdriver-instructions\s+.*Clarify and harden human instructions/,
  );
  assert.match(result.stdout, /security-scan\s+.*unknown-vulnerability scans/);
  assert.match(result.stdout, /uuid-generator\s+.*Generate UUIDs\/GUIDs/);
});
