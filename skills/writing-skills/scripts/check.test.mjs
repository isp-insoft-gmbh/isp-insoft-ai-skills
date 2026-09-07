import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkSkill } from './check.mjs';

const cli = fileURLToPath(new URL('./check.mjs', import.meta.url));
const header = '---\nname: example\ndescription: "Use when testing."\n---\n';

function fixture(t, files = {}) {
  const temp = mkdtempSync(join(tmpdir(), 'writing skills-'));
  t.after(() => rmSync(temp, { recursive: true, force: true }));
  const root = join(temp, 'example');
  for (const [path, content] of Object.entries({
    'SKILL.md': header,
    ...files,
  })) {
    const dest = join(root, path);
    mkdirSync(dirname(dest), { recursive: true });
    writeFileSync(dest, content);
  }
  return root;
}

function codes(root) {
  return checkSkill(root).issues.map((issue) => issue.code);
}

test('valid prose-only skill needs neither package nor tests', (t) => {
  const result = checkSkill(fixture(t));
  assert.equal(result.ok, true);
  assert.deepEqual(result.issues, []);
});

for (const [label, content, expected] of [
  ['missing fences', 'name: example', 'frontmatter.fence'],
  ['unclosed fence', '---\nname: example', 'frontmatter.fence'],
  ['invalid YAML', '---\nname: [\n---\n', 'frontmatter.yaml'],
  [
    'duplicate YAML key',
    header.replace('name: example', 'name: example\nname: other'),
    'frontmatter.yaml',
  ],
  ['non-mapping YAML', '---\n- example\n---\n', 'frontmatter.mapping'],
  ['missing name', header.replace('name: example\n', ''), 'name.invalid'],
  ['non-string name', header.replace('example', '123'), 'name.invalid'],
  [
    'consecutive hyphens',
    header.replace('example', 'ex--ample'),
    'name.invalid',
  ],
  ['uppercase name', header.replace('example', 'Example'), 'name.invalid'],
  ['leading hyphen', header.replace('example', '-example'), 'name.invalid'],
  ['trailing hyphen', header.replace('example', 'example-'), 'name.invalid'],
  ['long name', header.replace('example', 'a'.repeat(65)), 'name.invalid'],
  ['directory mismatch', header.replace('example', 'other'), 'name.directory'],
  ['missing description', '---\nname: example\n---\n', 'description.invalid'],
  [
    'empty description',
    header.replace('"Use when testing."', '"  "'),
    'description.invalid',
  ],
  [
    'non-string description',
    header.replace('"Use when testing."', 'false'),
    'description.invalid',
  ],
  [
    'long description',
    header.replace('Use when testing.', 'x'.repeat(1025)),
    'description.invalid',
  ],
  [
    'non-boolean invocation flag',
    header.replace(
      'name: example',
      'name: example\ndisable-model-invocation: "false"',
    ),
    'invocation.invalid',
  ],
]) {
  test(label, (t) =>
    assert.ok(codes(fixture(t, { 'SKILL.md': content })).includes(expected)),
  );
}

test('YAML folded descriptions, comments, CRLF and unknown metadata are valid', (t) => {
  const content =
    '---\nname: example # comment\ndescription: >-\n  Use when testing:\n  quoted punctuation is not required here.\ndisable-model-invocation: true\nmetadata:\n  topic: checks\n---\n';
  assert.deepEqual(
    codes(
      fixture(t, { 'SKILL.md': `\uFEFF${content.replaceAll('\n', '\r\n')}` }),
    ),
    [],
  );
});

test('YAML scalar aliases resolve', (t) => {
  const root = fixture(t, {
    'SKILL.md': header.replace(
      'description: "Use when testing."',
      'label: &label "Use when testing."\ndescription: *label',
    ),
  });
  assert.deepEqual(codes(root), []);
});

test('unresolved YAML aliases fail even in optional metadata', (t) => {
  const root = fixture(t, {
    'SKILL.md': header.replace(
      'name: example',
      'name: example\nmetadata: *missing',
    ),
  });
  assert.ok(codes(root).includes('frontmatter.yaml'));
});

test('local inline, image, reference and nested Markdown links resolve per document', (t) => {
  const root = fixture(t, {
    'SKILL.md': `${header}\n[ref](references/guide.md#part)\n![image](assets/pic.png)\n[space][s]\n\n[s]: <references/a b.md>\n`,
    'references/guide.md':
      '[back](../SKILL.md)\n[encoded](a%20b.md?view=1#part)\n[parenthesized](a(b).md)',
    'references/a b.md': 'OK',
    'references/a(b).md': 'OK',
    'assets/pic.png': 'fixture',
  });
  assert.deepEqual(codes(root), []);
});

test('missing local links report their source file', (t) => {
  const root = fixture(t, {
    'references/guide.md': '[missing](gone.md)\n![missing](gone.png)',
  });
  const result = checkSkill(root);
  assert.equal(result.ok, false);
  assert.deepEqual(
    result.issues.map(({ path, code }) => [path, code]),
    [
      ['references/guide.md', 'link.missing'],
      ['references/guide.md', 'link.missing'],
    ],
  );
});

test('external URLs, anchors, absolute links and code examples are not local-link checks', (t) => {
  const root = fixture(t, {
    'SKILL.md': `${header}
[web](https://example.test/missing)
[mail](mailto:test@example.test)
[anchor](#missing)
[absolute](/missing)
[windows](C:/missing)
[network](//example.test/missing)
\`[code](missing.md)\`

\`\`\`md
[example](missing.md)
\`\`\`
`,
  });
  assert.deepEqual(codes(root), []);
});

test('bad URL encoding is an actionable issue', (t) => {
  assert.ok(
    codes(fixture(t, { 'SKILL.md': `${header}[bad](missing%ZZ.md)` })).includes(
      'link.encoding',
    ),
  );
});

test('dependencies and VCS internals are not traversed', (t) => {
  assert.deepEqual(
    codes(
      fixture(t, {
        'node_modules/pkg/SKILL.md': '[bad](gone)',
        '.git/internal.md': '[bad](gone)',
      }),
    ),
    [],
  );
});

test('directory symlinks are not traversed', (t) => {
  const root = fixture(t);
  const external = join(dirname(root), 'outside');
  mkdirSync(external);
  writeFileSync(join(external, 'bad.md'), '[bad](gone)');
  symlinkSync(
    external,
    join(root, 'references'),
    process.platform === 'win32' ? 'junction' : 'dir',
  );
  assert.deepEqual(codes(root), []);
});

const helper = {
  'scripts/helper.mjs': 'throw new Error("must never execute");',
};
const testFile = {
  'scripts/helper.test.mjs': 'throw new Error("must never execute");',
};
const manifest = (command) => JSON.stringify({ scripts: { test: command } });

for (const [label, files, expected] of [
  ['missing package and tests', helper, ['package.missing', 'tests.missing']],
  [
    'bad JSON',
    { ...helper, ...testFile, 'package.json': '{' },
    ['package.json'],
  ],
  [
    'non-object manifest',
    { ...helper, ...testFile, 'package.json': 'null' },
    ['package.json'],
  ],
  [
    'missing test command',
    { ...helper, ...testFile, 'package.json': '{}' },
    ['tests.command'],
  ],
  [
    'non-string test command',
    { ...helper, ...testFile, 'package.json': manifest(42) },
    ['tests.command'],
  ],
  [
    'fake test command',
    { ...helper, ...testFile, 'package.json': manifest('echo node --test') },
    ['tests.command'],
  ],
  [
    'missing tests',
    { ...helper, 'package.json': manifest('node --test') },
    ['tests.missing'],
  ],
  [
    'valid scripted skill',
    {
      ...helper,
      ...testFile,
      'package.json': manifest('node --test scripts/*.test.mjs'),
    },
    [],
  ],
  [
    'nested tests',
    {
      ...helper,
      'test/nested/helper.test.mjs': '',
      'package.json': manifest('node --test'),
    },
    [],
  ],
  [
    'domain-specific helper',
    {
      'scripts/helper.ps1': '',
      ...testFile,
      'package.json': manifest('node --test'),
    },
    [],
  ],
]) {
  test(label, (t) => assert.deepEqual(codes(fixture(t, files)), expected));
}

test('CLI succeeds from unrelated cwd and paths with spaces', (t) => {
  const root = fixture(t, { 'references/path with spaces.md': 'OK' });
  const result = spawnSync(process.execPath, [cli, root], {
    cwd: tmpdir(),
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).ok, true);
});

test('CLI returns 1 for findings', (t) => {
  const result = spawnSync(
    process.execPath,
    [cli, fixture(t, { 'SKILL.md': 'bad' })],
    { encoding: 'utf8' },
  );
  assert.equal(result.status, 1);
  assert.equal(JSON.parse(result.stdout).ok, false);
});

test('CLI returns 2 for usage or inaccessible input', (t) => {
  const root = fixture(t);
  for (const args of [[], [root, 'extra'], [join(root, 'missing')]]) {
    const result = spawnSync(process.execPath, [cli, ...args], {
      encoding: 'utf8',
    });
    assert.equal(result.status, 2, result.stderr);
    assert.ok(JSON.parse(result.stdout).error);
  }
});

test('checker audits itself', () => {
  const root = fileURLToPath(new URL('..', import.meta.url));
  assert.deepEqual(codes(root), []);
  assert.ok(readFileSync(join(root, 'SKILL.md'), 'utf8').includes('check.mjs'));
});
