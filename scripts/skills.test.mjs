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
import test from 'node:test';

const ROOT = path.resolve(import.meta.dirname, '..');
const CLI = path.join(ROOT, 'scripts', 'skills.mjs');

function runCli(cli, args, extraEnv = {}, cwd = ROOT) {
  return spawnSync(process.execPath, [cli, ...args], {
    cwd,
    encoding: 'utf8',
    env: {
      ...process.env,
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

test('install rejects unknown units before checking build output', () => {
  const harnessRoot = mkdtempSync(path.join(tmpdir(), 'skills-harness-'));
  try {
    const result = runSkills(['install', 'nope', '--harness', 'claude'], {
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
      ['install', 'fxdriver-instructions', '--harness', 'claude'],
      env,
    );
    assert.equal(member.status, 1);
    assert.match(member.stderr, /unknown install unit "fxdriver-instructions"/);

    const family = runSkills(
      ['install', 'fxdriver', '--harness', 'claude'],
      env,
    );
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
      ['install', 'demo', '--target', target],
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
      ['install', 'natural-demo', '--target', target],
      {},
      fixture.root,
    );
    assert.equal(member.status, 1);
    assert.match(member.stderr, /unknown install unit "natural-demo"/);

    const family = runCli(
      fixture.cli,
      ['install', 'family', '--target', target],
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
      ['install', 'family', '--target', target],
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
      ['install', 'family', '--harness', 'pi'],
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
      ['uninstall', 'family', '--harness', 'pi'],
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
      ['install', 'demo', '--target', target],
      {},
      fixture.root,
    );

    assert.equal(result.status, 0);
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
      ['install', 'demo', '--target', path.join(fixture.root, 'skills')],
      {},
      fixture.root,
    );
    assert.equal(overlap.status, 1);
    assert.match(overlap.stderr, /install target overlaps source artifact/);

    const naturalOverlap = runCli(
      fixture.cli,
      ['install', 'family', '--target', path.join(fixture.root, 'skills')],
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
      ['install', 'demo', '--target', linkedTarget],
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
      ['install', 'demo', '--harness', 'pi', '--target', 'somewhere'],
      {},
      fixture.root,
    );
    assert.equal(ambiguous.status, 1);
    assert.match(ambiguous.stderr, /either --harness or --target/);
  } finally {
    rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('commands that do not support --target reject it', () => {
  for (const command of ['status', 'uninstall']) {
    const result = runSkills([command, '--target', 'somewhere']);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /--target is only supported by install/);
  }
});
