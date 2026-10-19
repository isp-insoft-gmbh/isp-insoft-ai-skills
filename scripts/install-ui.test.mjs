import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { chooseDestination, chooseSkills, statePath } from './install-ui.mjs';

function answers(values) {
  return async () => {
    assert.ok(values.length, 'unexpected prompt');
    return values.shift();
  };
}

test('state paths follow platform conventions', () => {
  const home = path.resolve('home');
  const local = path.resolve('local-appdata');
  const xdg = path.resolve('xdg-state');
  assert.equal(
    statePath('win32', { LOCALAPPDATA: local }, home),
    path.join(local, 'isp-insoft-ai-skills', 'state.json'),
  );
  assert.equal(
    statePath('win32', {}, home),
    path.join(home, 'AppData', 'Local', 'isp-insoft-ai-skills', 'state.json'),
  );
  assert.equal(
    statePath('linux', { XDG_STATE_HOME: xdg }, home),
    path.join(xdg, 'isp-insoft-ai-skills', 'state.json'),
  );
  assert.equal(
    statePath('linux', {}, home),
    path.join(home, '.local', 'state', 'isp-insoft-ai-skills', 'state.json'),
  );
  assert.equal(
    statePath('darwin', {}, home),
    path.join(
      home,
      'Library',
      'Application Support',
      'isp-insoft-ai-skills',
      'state.json',
    ),
  );
});

test('destination picker rejects unavailable history and invalid selections', async () => {
  const root = mkdtempSync(path.join(tmpdir(), 'skills-picker-'));
  const missing = path.join(root, 'missing');
  const existing = path.join(root, 'existing');
  mkdirSync(existing);
  try {
    assert.deepEqual(
      await chooseDestination(
        answers(['0', '1', '2']),
        [missing, existing],
        {},
      ),
      { target: existing },
    );
    assert.equal(
      await chooseDestination(answers(['1', null]), [missing], {}),
      null,
    );
    assert.equal(
      await chooseDestination(answers(['2', null]), [], { pi: root }),
      null,
    );
    assert.deepEqual(
      await chooseDestination(answers(['9', '1']), [], { pi: root }),
      { harness: 'pi' },
    );
    assert.equal(await chooseDestination(answers([null]), [], {}), null);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('global harnesses are directly selectable before recent project paths', async () => {
  const harnesses = { claude: '/claude', pi: '/pi', codex: '/codex' };
  for (const [index, harness] of Object.keys(harnesses).entries()) {
    assert.deepEqual(
      await chooseDestination(
        answers([String(index + 1)]),
        ['/missing-project'],
        harnesses,
      ),
      { harness },
    );
  }
});

test('skill picker validates multiple choices, deduplicates, and cancels', async () => {
  const units = [
    { name: 'java', description: 'Java' },
    { name: 'security', description: 'Security' },
  ];
  assert.deepEqual(
    await chooseSkills(
      answers(['0', '1,nope', '1, 2,1']),
      units,
      new Set(['java']),
    ),
    ['java', 'security'],
  );
  assert.equal(await chooseSkills(answers([null]), units, new Set()), null);
});
