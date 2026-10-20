import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test, { after } from 'node:test';
import { pathToFileURL } from 'node:url';
import { statePath } from './install-ui.mjs';

const ROOT = path.resolve(import.meta.dirname, '..');
const CLI = path.join(ROOT, 'scripts', 'skills.mjs');
const STATE_ROOT = mkdtempSync(path.join(tmpdir(), 'skills-state-'));
after(() => rmSync(STATE_ROOT, { recursive: true, force: true }));

function runCli(cli, args, extraEnv = {}, cwd = ROOT, input) {
  return spawnSync(process.execPath, [cli, ...args], {
    cwd,
    encoding: 'utf8',
    input,
    timeout: 15_000,
    env: {
      ...process.env,
      FORCE_COLOR: '0',
      NO_COLOR: undefined,
      LOCALAPPDATA: STATE_ROOT,
      XDG_STATE_HOME: STATE_ROOT,
      HOME: STATE_ROOT,
      ...extraEnv,
    },
  });
}

function runSkills(args, extraEnv = {}) {
  return runCli(CLI, args, extraEnv);
}

function createCliFixture() {
  const root = mkdtempSync(path.join(tmpdir(), 'skills-cli-'));
  const cli = path.join(root, 'scripts', 'skills.mjs');
  const source = path.join(root, 'skills', 'demo');
  const artifact = path.join(source, 'dist', 'demo');
  const markdown = `---\nname: demo\ndescription: "Demo skill for installer tests."\n---\n\n# Demo\n`;
  const natural = path.join(root, 'skills', 'family', 'natural-demo');
  const naturalMarkdown = `---\nname: natural-demo\ndescription: "Natural markdown skill."\n---\n\n# Natural demo\n`;
  mkdirSync(path.dirname(cli), { recursive: true });
  mkdirSync(artifact, { recursive: true });
  mkdirSync(natural, { recursive: true });
  cpSync(CLI, cli);
  cpSync(
    path.join(ROOT, 'scripts', 'output.mjs'),
    path.join(root, 'scripts', 'output.mjs'),
  );
  cpSync(
    path.join(ROOT, 'scripts', 'install-ui.mjs'),
    path.join(root, 'scripts', 'install-ui.mjs'),
  );
  writeFileSync(
    path.join(source, 'mise.toml'),
    '[tasks.build]\nrun = "noop"\n',
  );
  writeFileSync(path.join(source, 'SKILL.md'), markdown);
  writeFileSync(path.join(artifact, 'SKILL.md'), markdown);
  writeFileSync(path.join(natural, 'SKILL.md'), naturalMarkdown);
  return { root, cli, source, natural };
}

test('subcommand help prints usage and exits successfully', () => {
  for (const command of ['add', 'remove', 'status', 'update']) {
    const result = runSkills([command, '--help']);

    assert.equal(result.status, 0);
    assert.match(
      result.stdout,
      /usage: mise run sk -- \[command\] \[options\]/,
    );
    assert.equal(result.stderr, '');
  }
});

test('status rejects unknown harness with a friendly error', () => {
  const result = runSkills(['status', '--harness', 'nope']);

  assert.equal(result.status, 1);
  assert.match(result.stderr, /error: unknown harness "nope"/);
  assert.doesNotMatch(result.stderr, /TypeError|ERR_INVALID_ARG_TYPE/);
});

test('install rejects unknown units before checking build output', () => {
  const harnessRoot = mkdtempSync(path.join(tmpdir(), 'skills-harness-'));
  try {
    const result = runSkills(['add', 'nope', '--harness', 'claude'], {
      SKILLS_HARNESS_CLAUDE: path.join(harnessRoot, 'claude'),
    });

    assert.equal(result.status, 1);
    assert.match(result.stderr, /error: unknown install unit "nope"/);
    assert.doesNotMatch(result.stderr, /not built/);
  } finally {
    rmSync(harnessRoot, { recursive: true, force: true });
  }
});

test('install treats a complex multi-skill owner as one family', () => {
  const harnessRoot = mkdtempSync(path.join(tmpdir(), 'skills-harness-'));
  try {
    const env = {
      SKILLS_HARNESS_CLAUDE: path.join(harnessRoot, 'claude'),
    };
    const member = runSkills(
      ['add', 'fxdriver-instructions', '--harness', 'claude'],
      env,
    );
    assert.equal(member.status, 1);
    assert.match(member.stderr, /unknown install unit "fxdriver-instructions"/);

    const family = runSkills(['add', 'fxdriver', '--harness', 'claude'], env);
    if (family.status === 1) {
      assert.match(
        family.stderr,
        /fxdriver: not built\. run: mise run \/\/skills\/fxdriver:build/,
      );
    } else {
      assert.equal(family.status, 0);
      assert.ok(
        existsSync(
          path.join(env.SKILLS_HARNESS_CLAUDE, 'fxdriver', 'fxdriver'),
        ),
      );
      assert.ok(
        existsSync(
          path.join(
            env.SKILLS_HARNESS_CLAUDE,
            'fxdriver',
            'fxdriver-instructions',
          ),
        ),
      );
    }
  } finally {
    rmSync(harnessRoot, { recursive: true, force: true });
  }
});

test('list prints one bold, width-bounded line per skill', () => {
  const result = runSkills(['list'], { COLUMNS: '72', FORCE_COLOR: '1' });

  assert.equal(result.status, 0);
  assert.equal(result.stderr, '');
  const lines = result.stdout.trimEnd().split(/\r?\n/);
  const bold = '\u001b[1m';
  const reset = '\u001b[22m';
  assert.ok(lines.length > 1);
  const names = [];
  for (const line of lines) {
    assert.ok(line.startsWith(bold));
    assert.ok(line.includes(reset));
    names.push(line.slice(bold.length, line.indexOf(reset)).trim());
    const plain = line.replaceAll(bold, '').replaceAll(reset, '');
    assert.match(plain, /^[a-z0-9-]+(?: {2}|\s*$)/);
    assert.ok(plain.length <= 72, `line exceeds terminal width: ${line}`);
  }
  assert.ok(names.includes('coding-guidelines'));
  assert.ok(names.includes('java-virtual-threads'));
  assert.ok(names.includes('writing-skills'));
  assert.equal(names.includes('java'), false);
  assert.equal(names.includes('uuid-generator'), false);
  assert.doesNotMatch(result.stdout, /skill\s+kind\s+built/);
  const experimentation = lines.find((line) =>
    line.startsWith(`${bold}experimentation`),
  );
  assert.ok(experimentation?.includes(`${reset}  Use for`));
  assert.match(result.stdout, /…\r?\n/);
});

test('install copies a built skill into an arbitrary target and records ownership', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'custom target');
  try {
    const result = runCli(
      fixture.cli,
      ['add', 'demo', '--target', target],
      {},
      fixture.root,
    );

    assert.equal(result.status, 0);
    assert.equal(result.stderr, '');
    assert.ok(existsSync(path.join(target, 'demo', 'SKILL.md')));
    const ledger = JSON.parse(
      readFileSync(path.join(target, '.isp-skills.json'), 'utf8'),
    );
    assert.match(ledger.demo, /^[a-f0-9]{64}$/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('install preserves a markdown family as one nested unit', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  try {
    const member = runCli(
      fixture.cli,
      ['add', 'natural-demo', '--target', target],
      {},
      fixture.root,
    );
    assert.equal(member.status, 1);
    assert.match(member.stderr, /unknown install unit "natural-demo"/);

    const family = runCli(
      fixture.cli,
      ['add', 'family', '--target', target],
      {},
      fixture.root,
    );
    assert.equal(family.status, 0);
    assert.equal(family.stderr, '');
    assert.equal(
      readFileSync(
        path.join(target, 'family', 'natural-demo', 'SKILL.md'),
        'utf8',
      ),
      readFileSync(path.join(fixture.natural, 'SKILL.md'), 'utf8'),
    );
    const ledger = JSON.parse(
      readFileSync(path.join(target, '.isp-skills.json'), 'utf8'),
    );
    assert.deepEqual(Object.keys(ledger), ['family']);
    assert.equal(existsSync(path.join(fixture.natural, 'dist')), false);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('markdown installs and status ignore source-only directories', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  const ignored = ['.git', 'dist', 'node_modules', 'target'];
  try {
    for (const name of ignored) {
      const junk = path.join(
        fixture.root,
        'skills',
        'family',
        name,
        'junk.txt',
      );
      mkdirSync(path.dirname(junk), { recursive: true });
      writeFileSync(junk, 'source-only\n');
    }

    const installed = runCli(
      fixture.cli,
      ['add', 'family', '--target', target],
      {},
      fixture.root,
    );
    assert.equal(installed.status, 0, installed.stderr);
    for (const name of ignored)
      assert.equal(existsSync(path.join(target, 'family', name)), false);

    writeFileSync(
      path.join(fixture.root, 'skills', 'family', 'dist', 'junk.txt'),
      'changed source-only content\n',
    );
    const status = runCli(
      fixture.cli,
      ['status', '--harness', 'pi'],
      { SKILLS_HARNESS_PI: target },
      fixture.root,
    );
    assert.match(status.stdout, /^family\s+pi\s+current$/m);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('status and uninstall operate on the complete family', () => {
  const fixture = createCliFixture();
  const harness = path.join(fixture.root, 'harness');
  const env = { SKILLS_HARNESS_PI: harness };
  try {
    const installed = runCli(
      fixture.cli,
      ['add', 'family', '--harness', 'pi'],
      env,
      fixture.root,
    );
    assert.equal(installed.status, 0);

    const current = runCli(
      fixture.cli,
      ['status', '--harness', 'pi'],
      env,
      fixture.root,
    );
    assert.match(current.stdout, /^family\s+pi\s+current$/m);
    assert.doesNotMatch(current.stdout, /^natural-demo\s/m);

    writeFileSync(
      path.join(harness, 'family', 'natural-demo', 'SKILL.md'),
      'changed\n',
    );
    const stale = runCli(
      fixture.cli,
      ['status', '--harness', 'pi'],
      env,
      fixture.root,
    );
    assert.match(stale.stdout, /^family\s+pi\s+STALE$/m);

    const removed = runCli(
      fixture.cli,
      ['remove', 'family', '--harness', 'pi'],
      env,
      fixture.root,
    );
    assert.equal(removed.status, 0);
    assert.equal(existsSync(path.join(harness, 'family')), false);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('install preserves a foreign skill in an arbitrary target', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  const foreign = path.join(target, 'demo', 'SKILL.md');
  try {
    mkdirSync(path.dirname(foreign), { recursive: true });
    writeFileSync(foreign, 'foreign\n');
    const result = runCli(
      fixture.cli,
      ['add', 'demo', '--target', target],
      {},
      fixture.root,
    );

    assert.equal(result.status, 1);
    assert.match(result.stdout, /refusing to overwrite/);
    assert.equal(readFileSync(foreign, 'utf8'), 'foreign\n');
    assert.equal(existsSync(path.join(target, '.isp-skills.json')), false);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('install rejects overlapping and ambiguous targets', () => {
  const fixture = createCliFixture();
  try {
    const overlap = runCli(
      fixture.cli,
      ['add', 'demo', '--target', path.join(fixture.root, 'skills')],
      {},
      fixture.root,
    );
    assert.equal(overlap.status, 1);
    assert.match(overlap.stderr, /install target overlaps source artifact/);

    const naturalOverlap = runCli(
      fixture.cli,
      ['add', 'family', '--target', path.join(fixture.root, 'skills')],
      {},
      fixture.root,
    );
    assert.equal(naturalOverlap.status, 1);
    assert.match(
      naturalOverlap.stderr,
      /install target overlaps source artifact/,
    );

    const linkedTarget = path.join(fixture.root, 'linked-target');
    symlinkSync(
      path.join(fixture.root, 'skills'),
      linkedTarget,
      process.platform === 'win32' ? 'junction' : 'dir',
    );
    const linkedOverlap = runCli(
      fixture.cli,
      ['add', 'demo', '--target', linkedTarget],
      {},
      fixture.root,
    );
    assert.equal(linkedOverlap.status, 1);
    assert.match(
      linkedOverlap.stderr,
      /install target overlaps source artifact/,
    );

    const ambiguous = runCli(
      fixture.cli,
      ['add', 'demo', '--harness', 'pi', '--target', 'somewhere'],
      {},
      fixture.root,
    );
    assert.equal(ambiguous.status, 1);
    assert.match(ambiguous.stderr, /either --harness or --target/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('update refreshes only inventoried installs and is idempotent', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'project skills');
  const args = ['update', '--target', target];
  try {
    assert.equal(
      runCli(fixture.cli, ['add', 'family', '--target', target]).status,
      0,
    );
    const sourceMd = path.join(fixture.natural, 'SKILL.md');
    writeFileSync(
      sourceMd,
      `${readFileSync(sourceMd, 'utf8')}\nUpdated upstream.\n`,
    );
    const result = runCli(fixture.cli, args);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(
      readFileSync(
        path.join(target, 'family', 'natural-demo', 'SKILL.md'),
        'utf8',
      ),
      readFileSync(sourceMd, 'utf8'),
    );
    assert.equal(existsSync(path.join(target, 'demo')), false);
    const again = runCli(fixture.cli, args);
    assert.equal(again.status, 0, again.stderr);
    assert.match(again.stdout, /family: unchanged/);
    assert.match(
      result.stdout,
      /Update summary: 1 updated \| 0 unchanged \| 0 deleted/,
    );
    assert.match(
      again.stdout,
      /Update summary: 0 updated \| 1 unchanged \| 0 deleted/,
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('bare update scans global overrides and preserves local edits and orphans', () => {
  const fixture = createCliFixture();
  const claude = path.join(fixture.root, 'claude');
  const pi = path.join(fixture.root, 'pi');
  const codex = path.join(fixture.root, 'codex');
  const env = {
    SKILLS_HARNESS_CLAUDE: claude,
    SKILLS_HARNESS_PI: pi,
    SKILLS_HARNESS_CODEX: codex,
  };
  try {
    for (const target of [claude, pi])
      assert.equal(
        runCli(fixture.cli, ['add', '--all', '--target', target]).status,
        0,
      );
    const localMd = path.join(claude, 'family', 'natural-demo', 'SKILL.md');
    writeFileSync(localMd, 'local edits');
    const ledgerBefore = readFileSync(
      path.join(claude, '.isp-skills.json'),
      'utf8',
    );
    rmSync(path.join(pi, 'family'), { recursive: true });
    rmSync(path.join(fixture.root, 'skills', 'demo'), { recursive: true });
    const result = runCli(fixture.cli, ['update'], env);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /family: locally modified/);
    assert.match(result.stdout, /demo: ORPHAN/);
    assert.match(result.stdout, /family: not installed; skipped/);
    assert.match(result.stdout, /no inventoried units; skipped/);
    assert.equal(readFileSync(localMd, 'utf8'), 'local edits');
    assert.equal(
      readFileSync(path.join(claude, '.isp-skills.json'), 'utf8'),
      ledgerBefore,
    );
    assert.ok(existsSync(path.join(claude, 'demo', 'SKILL.md')));
    assert.equal(existsSync(path.join(pi, 'family')), false);
    assert.equal(existsSync(codex), false);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('update rejects invalid inventories before writing any target', () => {
  const fixture = createCliFixture();
  const claude = path.join(fixture.root, 'claude');
  const pi = path.join(fixture.root, 'pi');
  try {
    assert.equal(
      runCli(fixture.cli, ['add', 'family', '--target', claude]).status,
      0,
    );
    const installedMd = path.join(claude, 'family', 'natural-demo', 'SKILL.md');
    const before = readFileSync(installedMd, 'utf8');
    writeFileSync(path.join(fixture.natural, 'SKILL.md'), 'upstream change');
    mkdirSync(pi);
    for (const invalid of [
      '{',
      'null',
      '[]',
      '{"family":"bad hash"}',
      JSON.stringify({ '../escape': 'a'.repeat(64) }),
    ]) {
      writeFileSync(path.join(pi, '.isp-skills.json'), invalid);
      const result = runCli(fixture.cli, ['update'], {
        SKILLS_HARNESS_CLAUDE: claude,
        SKILLS_HARNESS_PI: pi,
        SKILLS_HARNESS_CODEX: path.join(fixture.root, 'absent'),
      });
      assert.equal(result.status, 1);
      assert.match(result.stderr, /invalid inventory/);
      assert.equal(readFileSync(installedMd, 'utf8'), before);
    }
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('update supports one harness and reports unbuilt units without replacing them', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'pi');
  try {
    assert.equal(
      runCli(fixture.cli, ['add', 'demo', '--target', target]).status,
      0,
    );
    rmSync(path.join(fixture.source, 'dist'), { recursive: true });
    const result = runCli(fixture.cli, ['update', '--harness', 'pi'], {
      SKILLS_HARNESS_PI: target,
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /demo: not built/);
    assert.match(result.stdout, /0 skipped \| 1 errors/);
    assert.match(result.stdout, /Exit status 1: conflicts or errors remain/);
    assert.ok(existsSync(path.join(target, 'demo', 'SKILL.md')));
    const forced = runCli(
      fixture.cli,
      ['update', '--harness', 'pi', '--force'],
      { SKILLS_HARNESS_PI: target },
    );
    assert.equal(forced.status, 1);
    assert.match(forced.stderr, /demo: not built/);
    assert.ok(existsSync(path.join(target, 'demo', 'SKILL.md')));
    for (const args of [
      ['--target', target, '--harness', 'pi'],
      ['demo'],
      ['--force'],
      ['--orphans'],
      ['--unknown'],
      ['--harness', 'nope'],
    ]) {
      const rejected = runCli(fixture.cli, ['update', ...args]);
      assert.equal(rejected.status, 1);
    }
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('update skips absent or empty inventories without adopting untracked skills', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  try {
    mkdirSync(path.join(target, 'family'), { recursive: true });
    const foreign = path.join(target, 'family', 'SKILL.md');
    writeFileSync(foreign, 'untracked content');
    for (const inventory of [null, '{}']) {
      if (inventory !== null)
        writeFileSync(path.join(target, '.isp-skills.json'), inventory);
      const result = runCli(fixture.cli, ['update', '--target', target]);
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /no inventoried units; skipped/);
      assert.equal(readFileSync(foreign, 'utf8'), 'untracked content');
      assert.equal(existsSync(path.join(target, 'demo')), false);
    }
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

function runInteractive(fixture, args, answers, extraEnv = {}) {
  const preload = path.join(fixture.root, 'prompt-test.mjs');
  writeFileSync(
    preload,
    `
    import readline from 'node:readline/promises';
    import { syncBuiltinESMExports } from 'node:module';
    process.stdin.isTTY = true;
    process.stdout.isTTY = true;
    const answers = ${JSON.stringify(answers)};
    readline.createInterface = () => ({
      on() {}, close() {},
      async question(question) {
        console.log(question);
        if (!answers.length) throw new Error('Unexpected prompt');
        return answers.shift();
      }
    });
    syncBuiltinESMExports();
  `,
  );
  return runCli(fixture.cli, args, {
    NO_COLOR: '1',
    ...extraEnv,
    NODE_OPTIONS: `--import=${JSON.stringify(pathToFileURL(preload).href)}`,
  });
}

test('first install requires selection; existing inventory means protected update', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  try {
    const empty = runCli(fixture.cli, ['--target', target]);
    assert.equal(empty.status, 1);
    assert.match(empty.stderr, /first install needs unit names or --all/);
    assert.equal(existsSync(target), false);
    const picked = runInteractive(
      fixture,
      ['add', '--target', target],
      ['2', 'y'],
    );
    assert.equal(picked.status, 0, picked.stderr);
    assert.ok(existsSync(path.join(target, 'family')));
    assert.equal(existsSync(path.join(target, 'demo')), false);
    const sourceMd = path.join(fixture.natural, 'SKILL.md');
    writeFileSync(sourceMd, 'new upstream');
    const updated = runCli(fixture.cli, ['--target', target]);
    assert.equal(updated.status, 0, updated.stderr);
    assert.match(
      updated.stdout,
      /Update summary: 1 updated \| 0 unchanged \| 0 deleted/,
    );
    const installedMd = path.join(target, 'family', 'natural-demo', 'SKILL.md');
    assert.equal(readFileSync(installedMd, 'utf8'), 'new upstream');
    writeFileSync(installedMd, 'local edit');
    for (const names of [[], ['family']]) {
      const blocked = runCli(fixture.cli, [
        'add',
        ...names,
        '--target',
        target,
      ]);
      assert.equal(blocked.status, 1);
      assert.match(blocked.stderr, /locally modified/);
      assert.equal(readFileSync(installedMd, 'utf8'), 'local edit');
    }
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('interactive destination selection remembers projects and --pick adds without removal', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'project skills');
  const other = path.join(fixture.root, 'other project');
  const env = {
    LOCALAPPDATA: fixture.root,
    XDG_STATE_HOME: fixture.root,
    HOME: fixture.root,
  };
  const historyFile = statePath(process.platform, env, fixture.root);
  try {
    const fresh = runInteractive(fixture, [], ['4', target, '2', 'y'], env);
    assert.equal(fresh.status, 0, fresh.stderr);
    assert.match(fresh.stdout, /1\. claude \(global\):/);
    assert.match(fresh.stdout, /2\. pi \(global\):/);
    assert.match(fresh.stdout, /3\. codex \(global\):/);
    assert.match(fresh.stdout, /4\. New project directory/);
    assert.doesNotMatch(fresh.stdout, /Harness number/);
    assert.deepEqual(
      JSON.parse(readFileSync(historyFile, 'utf8')).recentProjectTargets,
      [target],
    );
    const add = runInteractive(
      fixture,
      ['add', '--pick'],
      ['4', '1', 'y'],
      env,
    );
    assert.equal(add.status, 0, add.stderr);
    assert.ok(existsSync(path.join(target, 'family')));
    assert.ok(existsSync(path.join(target, 'demo')));
    assert.equal(
      runCli(fixture.cli, ['add', 'family', '--target', other], env).status,
      0,
    );
    assert.equal(
      runCli(fixture.cli, ['update', '--target', target], env).status,
      0,
    );
    assert.deepEqual(
      JSON.parse(readFileSync(historyFile, 'utf8')).recentProjectTargets,
      [target, other],
    );
    const before = readFileSync(historyFile, 'utf8');
    const global = runInteractive(fixture, [], ['2', '2', 'y'], {
      ...env,
      SKILLS_HARNESS_PI: path.join(fixture.root, 'global'),
    });
    assert.equal(global.status, 0, global.stderr);
    assert.equal(readFileSync(historyFile, 'utf8'), before);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('picker cancellation never writes targets or history', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'cancelled');
  const env = {
    LOCALAPPDATA: fixture.root,
    XDG_STATE_HOME: fixture.root,
    HOME: fixture.root,
  };
  try {
    for (const answers of [
      ['q'],
      ['4', 'q'],
      ['4', target, 'q'],
      ['4', target, '2', 'n'],
    ]) {
      const cancelled = runInteractive(fixture, [], answers, env);
      assert.equal(cancelled.status, 0, cancelled.stderr);
      assert.match(cancelled.stdout, /Cancelled/);
      assert.equal(existsSync(target), false);
      assert.equal(
        existsSync(statePath(process.platform, env, fixture.root)),
        false,
      );
    }
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('uninspectable installed content is preserved while other units update', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  try {
    assert.equal(
      runCli(fixture.cli, ['add', '--all', '--target', target]).status,
      0,
    );
    rmSync(path.join(target, 'demo'), { recursive: true });
    writeFileSync(path.join(target, 'demo'), 'local replacement file');
    writeFileSync(path.join(fixture.natural, 'SKILL.md'), 'new upstream');
    const result = runCli(fixture.cli, ['update', '--target', target]);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /demo: cannot inspect installed content/);
    assert.doesNotMatch(result.stderr, /at hashDir/);
    assert.equal(
      readFileSync(path.join(target, 'demo'), 'utf8'),
      'local replacement file',
    );
    assert.equal(
      readFileSync(
        path.join(target, 'family', 'natural-demo', 'SKILL.md'),
        'utf8',
      ),
      'new upstream',
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('real readline cancellation handles q, EOF, and Ctrl+C without saving history', () => {
  const fixture = createCliFixture();
  const preload = path.join(fixture.root, 'tty-test.mjs');
  writeFileSync(
    preload,
    'process.stdin.isTTY = true; process.stdout.isTTY = true;',
  );
  const env = {
    LOCALAPPDATA: fixture.root,
    XDG_STATE_HOME: fixture.root,
    HOME: fixture.root,
    NODE_OPTIONS: `--import=${JSON.stringify(pathToFileURL(preload).href)}`,
  };
  try {
    for (const input of ['q\n', '', '\u0003']) {
      const result = runCli(fixture.cli, [], env, fixture.root, input);
      assert.equal(result.status, 0, `${result.stderr} ${result.error || ''}`);
      assert.match(result.stdout, /Cancelled; no changes/);
      assert.equal(
        existsSync(statePath(process.platform, env, fixture.root)),
        false,
      );
    }
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('orphan prompts show installed descriptions and remove only confirmed units', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  const env = { SKILLS_HARNESS_PI: target };
  try {
    assert.equal(
      runCli(fixture.cli, ['add', '--all', '--target', target]).status,
      0,
    );
    rmSync(fixture.source, { recursive: true });
    rmSync(path.join(fixture.root, 'skills', 'family'), { recursive: true });
    const result = runInteractive(
      fixture,
      ['update', '--target', target],
      ['n', 'y'],
    );
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /Demo skill for installer tests/);
    assert.match(result.stdout, /Natural markdown skill/);
    assert.ok(result.stdout.includes(target));
    assert.ok(existsSync(path.join(target, 'demo')));
    assert.equal(existsSync(path.join(target, 'family')), false);
    assert.deepEqual(
      Object.keys(
        JSON.parse(readFileSync(path.join(target, '.isp-skills.json'), 'utf8')),
      ),
      ['demo'],
    );
    const demoMd = path.join(target, 'demo', 'SKILL.md');
    writeFileSync(demoMd, `${readFileSync(demoMd, 'utf8')}\nLocal change.\n`);
    const keep = runInteractive(fixture, ['--target', target], ['y', 'n']);
    assert.equal(keep.status, 0, keep.stderr);
    assert.match(
      keep.stdout,
      /WARNING: installed content has local modifications/,
    );
    assert.ok(existsSync(demoMd));
    const status = runInteractive(
      fixture,
      ['status', '--harness', 'pi'],
      [],
      env,
    );
    assert.equal(status.status, 0, status.stderr);
    assert.match(status.stdout, /Demo skill for installer tests/);
    assert.ok(existsSync(demoMd));
    const noninteractive = runCli(
      fixture.cli,
      ['remove', '--orphans', '--harness', 'pi'],
      env,
    );
    assert.equal(noninteractive.status, 0, noninteractive.stderr);
    assert.match(
      noninteractive.stdout,
      /deletion requires interactive confirmation/,
    );
    assert.ok(existsSync(demoMd));
    const removed = runInteractive(
      fixture,
      ['remove', '--orphans', '--harness', 'pi'],
      ['y'],
      env,
    );
    assert.equal(removed.status, 0, removed.stderr);
    assert.match(
      removed.stdout,
      /WARNING: installed content has local modifications/,
    );
    assert.equal(existsSync(path.join(target, 'demo')), false);
    assert.deepEqual(
      JSON.parse(readFileSync(path.join(target, '.isp-skills.json'), 'utf8')),
      {},
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('orphan deletion defaults to keep and can clear missing installs explicitly', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  try {
    assert.equal(
      runCli(fixture.cli, ['add', 'demo', '--target', target]).status,
      0,
    );
    rmSync(fixture.source, { recursive: true });
    for (const answer of ['', 'q', 'n']) {
      const kept = runInteractive(
        fixture,
        ['update', '--target', target],
        [answer],
      );
      assert.equal(kept.status, 0, kept.stderr);
      assert.ok(existsSync(path.join(target, 'demo')));
      assert.ok(
        JSON.parse(readFileSync(path.join(target, '.isp-skills.json'), 'utf8'))
          .demo,
      );
    }
    rmSync(path.join(target, 'demo'), { recursive: true });
    const removed = runInteractive(
      fixture,
      ['update', '--target', target],
      ['y'],
    );
    assert.equal(removed.status, 0, removed.stderr);
    assert.match(removed.stdout, /Description unavailable/);
    assert.match(removed.stdout, /only inventory entry will be removed/);
    assert.deepEqual(
      JSON.parse(readFileSync(path.join(target, '.isp-skills.json'), 'utf8')),
      {},
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('update summary aggregates partial success, conflicts, and orphan decisions across harnesses', () => {
  const fixture = createCliFixture();
  const claude = path.join(fixture.root, 'claude');
  const pi = path.join(fixture.root, 'pi');
  const retired = path.join(fixture.root, 'skills', 'retired');
  const env = {
    SKILLS_HARNESS_CLAUDE: claude,
    SKILLS_HARNESS_PI: pi,
    SKILLS_HARNESS_CODEX: path.join(fixture.root, 'absent'),
  };
  try {
    mkdirSync(retired);
    writeFileSync(
      path.join(retired, 'SKILL.md'),
      '---\nname: retired\ndescription: Retired skill.\n---\n',
    );
    for (const target of [claude, pi])
      assert.equal(
        runCli(fixture.cli, ['add', '--all', '--target', target]).status,
        0,
      );
    rmSync(retired, { recursive: true });
    writeFileSync(
      path.join(fixture.source, 'dist', 'demo', 'SKILL.md'),
      'updated upstream',
    );
    const local = path.join(claude, 'family', 'natural-demo', 'SKILL.md');
    writeFileSync(local, 'local changes');
    const result = runInteractive(fixture, ['update'], ['y', 'n'], env);
    assert.equal(result.status, 1);
    assert.match(
      result.stdout,
      /Update summary: 2 updated \| 1 unchanged \| 1 deleted/,
    );
    assert.match(
      result.stdout,
      /1 preserved conflicts \| 1 kept orphans \| 0 skipped \| 0 errors/,
    );
    assert.ok(
      result.stdout.includes(
        `Preserved conflict: family\n    ${path.join(claude, 'family')}: locally modified`,
      ),
    );
    assert.match(result.stdout, /Exit status 1: conflicts or errors remain/);
    assert.match(
      result.stdout,
      /Successful changes remain applied; no rollback/,
    );
    for (const target of [claude, pi])
      assert.equal(
        readFileSync(path.join(target, 'demo', 'SKILL.md'), 'utf8'),
        'updated upstream',
      );
    assert.equal(readFileSync(local, 'utf8'), 'local changes');
    assert.equal(existsSync(path.join(claude, 'retired')), false);
    assert.ok(existsSync(path.join(pi, 'retired')));
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('installer emphasizes names when enabled and keeps redirected output plain', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  try {
    const colored = runCli(fixture.cli, ['add', 'family', '--target', target], {
      FORCE_COLOR: '1',
      NO_COLOR: undefined,
    });
    assert.equal(colored.status, 0, colored.stderr);
    assert.ok(colored.stdout.includes('\u001b[1mfamily\u001b[22m'));
    const plain = runCli(fixture.cli, ['update', '--target', target], {
      FORCE_COLOR: '0',
      NO_COLOR: undefined,
    });
    assert.equal(plain.status, 0, plain.stderr);
    assert.equal(plain.stdout.includes('\u001b['), false);
    const disabled = runCli(fixture.cli, ['update', '--target', target], {
      FORCE_COLOR: '1',
      NO_COLOR: '1',
    });
    assert.equal(disabled.status, 0, disabled.stderr);
    assert.equal(disabled.stdout.includes('\u001b['), false);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('forced update requires an explicit destination before touching inventories', () => {
  const fixture = createCliFixture();
  try {
    const result = runCli(fixture.cli, ['update', '--force'], {
      SKILLS_HARNESS_CLAUDE: fixture.root,
      SKILLS_HARNESS_PI: fixture.root,
      SKILLS_HARNESS_CODEX: fixture.root,
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /--force needs an explicit --harness/);
    assert.doesNotMatch(result.stdout, /Update summary/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('forced project update overwrites only inventoried live installs and refreshes their hashes', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'target');
  const retired = path.join(fixture.root, 'skills', 'retired');
  try {
    mkdirSync(retired);
    writeFileSync(
      path.join(retired, 'SKILL.md'),
      '---\nname: retired\ndescription: Retired.\n---\n',
    );
    assert.equal(
      runCli(fixture.cli, ['add', '--all', '--target', target]).status,
      0,
    );
    const inventory = path.join(target, '.isp-skills.json');
    const before = JSON.parse(readFileSync(inventory, 'utf8'));
    const installed = path.join(target, 'family', 'natural-demo', 'SKILL.md');
    writeFileSync(installed, 'local edits');
    writeFileSync(
      path.join(target, 'family', 'local-only.txt'),
      'local extra file',
    );
    writeFileSync(path.join(fixture.natural, 'SKILL.md'), 'new upstream');
    rmSync(path.join(target, 'demo'), { recursive: true });
    rmSync(retired, { recursive: true });
    mkdirSync(path.join(target, 'foreign'));
    writeFileSync(path.join(target, 'foreign', 'SKILL.md'), 'untracked');
    const result = runCli(fixture.cli, [
      'update',
      '--target',
      target,
      '--force',
    ]);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /WARNING: --force discards local edits/);
    assert.match(result.stderr, /No backup is created/);
    assert.match(result.stderr, /family: --force replaces local edits/);
    assert.equal(readFileSync(installed, 'utf8'), 'new upstream');
    assert.equal(
      existsSync(path.join(target, 'family', 'local-only.txt')),
      false,
    );
    assert.equal(existsSync(path.join(target, 'demo')), false);
    assert.ok(existsSync(path.join(target, 'retired')));
    assert.equal(
      readFileSync(path.join(target, 'foreign', 'SKILL.md'), 'utf8'),
      'untracked',
    );
    const after = JSON.parse(readFileSync(inventory, 'utf8'));
    assert.notEqual(after.family, before.family);
    assert.equal(after.foreign, undefined);
    const again = runCli(fixture.cli, ['update', '--target', target]);
    assert.equal(again.status, 0, again.stderr);
    assert.match(again.stdout, /family: unchanged/);
    assert.doesNotMatch(again.stderr, /locally modified/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('interactive forced harness update confirms before discarding local edits', () => {
  const fixture = createCliFixture();
  const target = path.join(fixture.root, 'pi');
  const env = { SKILLS_HARNESS_PI: target };
  try {
    assert.equal(
      runCli(fixture.cli, ['add', 'family', '--harness', 'pi'], env).status,
      0,
    );
    const installed = path.join(target, 'family', 'natural-demo', 'SKILL.md');
    const inventory = path.join(target, '.isp-skills.json');
    writeFileSync(installed, 'local edits');
    const before = readFileSync(inventory, 'utf8');
    for (const answer of ['n', '', 'q']) {
      const cancelled = runInteractive(
        fixture,
        ['update', '--harness', 'pi', '--force'],
        [answer],
        env,
      );
      assert.equal(cancelled.status, 0, cancelled.stderr);
      assert.match(cancelled.stdout, /Cancelled; no changes/);
      assert.equal(readFileSync(installed, 'utf8'), 'local edits');
      assert.equal(readFileSync(inventory, 'utf8'), before);
    }
    const accepted = runInteractive(
      fixture,
      ['update', '--harness', 'pi', '--force'],
      ['y'],
      env,
    );
    assert.equal(accepted.status, 0, accepted.stderr);
    assert.match(accepted.stdout, /Overwrite local edits with this checkout/);
    assert.equal(
      readFileSync(installed, 'utf8'),
      readFileSync(path.join(fixture.natural, 'SKILL.md'), 'utf8'),
    );
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('orphan deletion preserves hashes refreshed earlier in ordinary and forced updates', () => {
  for (const force of [false, true]) {
    const fixture = createCliFixture();
    const target = path.join(fixture.root, 'target');
    const retired = path.join(fixture.root, 'skills', 'retired');
    try {
      mkdirSync(retired);
      writeFileSync(
        path.join(retired, 'SKILL.md'),
        '---\nname: retired\ndescription: Retired.\n---\n',
      );
      assert.equal(
        runCli(fixture.cli, ['add', '--all', '--target', target]).status,
        0,
      );
      const inventory = path.join(target, '.isp-skills.json');
      const before = JSON.parse(readFileSync(inventory, 'utf8'));
      writeFileSync(
        path.join(fixture.source, 'dist', 'demo', 'SKILL.md'),
        'new upstream',
      );
      if (force)
        writeFileSync(path.join(target, 'demo', 'SKILL.md'), 'local edits');
      rmSync(retired, { recursive: true });
      const args = [
        'update',
        '--target',
        target,
        ...(force ? ['--force'] : []),
      ];
      const result = runInteractive(fixture, args, force ? ['y', 'y'] : ['y']);
      assert.equal(result.status, 0, result.stderr);
      const after = JSON.parse(readFileSync(inventory, 'utf8'));
      assert.notEqual(after.demo, before.demo);
      assert.equal(after.retired, undefined);
      const again = runCli(fixture.cli, ['update', '--target', target]);
      assert.equal(again.status, 0, again.stderr);
      assert.match(again.stdout, /demo: unchanged/);
      assert.doesNotMatch(again.stderr, /locally modified/);
    } finally {
      rmSync(fixture.root, { recursive: true, force: true });
    }
  }
});

test('commands that do not support --target reject it', () => {
  for (const command of ['status', 'remove']) {
    const result = runSkills([command, '--target', 'somewhere']);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /--target is only supported by add and update/);
  }
});
