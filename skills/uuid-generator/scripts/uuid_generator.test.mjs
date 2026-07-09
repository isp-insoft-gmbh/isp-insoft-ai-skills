import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

const CLI = path.join(import.meta.dirname, 'uuid_generator.mjs');
const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function run(args) {
  return spawnSync(process.execPath, [CLI, ...args], {
    encoding: 'utf8',
  });
}

test('generates a UUID4 by default', () => {
  const result = run([]);

  assert.equal(result.status, 0);
  assert.match(result.stdout.trim(), UUID_RE);
  assert.equal(result.stdout.trim()[14], '4');
  assert.equal(result.stderr, '');
});

test('generates deterministic UUID5 values for named namespaces', () => {
  const result = run([
    '--version',
    '5',
    '--namespace',
    'dns',
    '--name',
    'www.example.com',
  ]);

  assert.equal(result.status, 0);
  assert.equal(result.stdout.trim(), '2ed6657d-e927-568b-95e1-2665a8aea6a2');
  assert.equal(result.stderr, '');
});

test('formats UUID output as compact, URN, and uppercase', () => {
  const compact = run(['--format', 'compact']);
  const urn = run(['--format', 'urn']);
  const uppercase = run(['--format', 'uppercase']);

  assert.equal(compact.status, 0);
  assert.match(compact.stdout.trim(), /^[0-9a-f]{32}$/);
  assert.equal(urn.status, 0);
  assert.match(urn.stdout.trim(), /^urn:uuid:[0-9a-f-]{36}$/);
  assert.equal(uppercase.status, 0);
  assert.match(uppercase.stdout.trim(), /^[0-9A-F-]{36}$/);
});

test('validates UUID strings with success and failure exit codes', () => {
  const valid = run(['--validate', 'a1b2c3d4-e5f6-4789-abcd-ef0123456789']);
  const invalid = run(['--validate', 'not-a-uuid']);

  assert.equal(valid.status, 0);
  assert.match(valid.stdout, /valid$/m);
  assert.equal(invalid.status, 1);
  assert.match(invalid.stdout, /invalid$/m);
});

test('writes bulk JSON exports to a requested file', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'uuid-generator-'));
  try {
    const out = path.join(dir, 'uuids.json');
    const result = run(['--count', '3', '--export', 'json', '--output', out]);

    assert.equal(result.status, 0);
    assert.match(result.stdout, /Generated 3 UUIDs/);
    const rows = JSON.parse(readFileSync(out, 'utf8'));
    assert.equal(rows.length, 3);
    assert.ok(rows.every((row) => UUID_RE.test(row)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
