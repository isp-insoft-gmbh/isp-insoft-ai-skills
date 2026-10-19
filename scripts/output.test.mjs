import assert from 'node:assert/strict';
import test from 'node:test';
import { bold, descriptionLines } from './output.mjs';

test('emphasis respects terminal detection, FORCE_COLOR, and NO_COLOR', () => {
  const saved = {
    FORCE_COLOR: process.env.FORCE_COLOR,
    NO_COLOR: process.env.NO_COLOR,
  };
  try {
    delete process.env.NO_COLOR;
    delete process.env.FORCE_COLOR;
    assert.equal(bold('java', { isTTY: false }), 'java');
    assert.equal(bold('java', { isTTY: true }), '\u001b[1mjava\u001b[22m');
    process.env.FORCE_COLOR = '1';
    assert.equal(bold('java', { isTTY: false }), '\u001b[1mjava\u001b[22m');
    process.env.NO_COLOR = '1';
    assert.equal(bold('java', { isTTY: true }), 'java');
  } finally {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});

test('descriptions wrap at word boundaries and preserve indentation', () => {
  const saved = process.env.COLUMNS;
  process.env.COLUMNS = '48';
  try {
    const description =
      'A skill description with enough words to span several readable lines without truncation.';
    const lines = descriptionLines(description, '    ').split('\n');
    assert.ok(lines.length > 1);
    for (const line of lines) {
      assert.ok(line.startsWith('    '));
      assert.ok(line.length <= 48);
    }
    assert.equal(lines.map((line) => line.trim()).join(' '), description);
    assert.equal(descriptionLines(''), '');
  } finally {
    if (saved === undefined) delete process.env.COLUMNS;
    else process.env.COLUMNS = saved;
  }
});
