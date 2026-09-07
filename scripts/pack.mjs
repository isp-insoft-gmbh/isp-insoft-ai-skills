#!/usr/bin/env node
// Pack a skill's deliverable into ./dist/<name>/. Run from the skill dir (mise
// sets the task cwd to the skill dir). The default artifact name is its dir name.
//
//   pack.mjs                copy the skill source (pruned)  -> ./dist/<name>/
//   pack.mjs --from <dir>   copy a built output dir verbatim -> ./dist/<name>/
//   pack.mjs --name <name>  write to ./dist/<name>/ (for bundled companions)
//   pack.mjs --clean-root   remove ./dist before writing the first family member
//
// Every artifact must contain SKILL.md or packing fails.
import fs from 'node:fs';
import path from 'node:path';

const CWD = process.cwd();
const argValue = (flag) => {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : null;
};
const name = argValue('--name') || path.basename(CWD);

// Never ship build junk when copying source.
const DENY = new Set(['mise.toml', 'target', 'node_modules', '.git', 'dist']);

const builtFrom = argValue('--from');
const src = builtFrom ? path.resolve(CWD, builtFrom) : CWD;
const distRoot = path.join(CWD, 'dist');
const dest = path.join(distRoot, name);

if (process.argv.includes('--clean-root'))
  fs.rmSync(distRoot, { recursive: true, force: true });

if (!fs.existsSync(path.join(src, 'SKILL.md'))) {
  process.stderr.write(
    `pack: no SKILL.md in ${path.relative(CWD, src) || '.'}\n`,
  );
  process.exit(1);
}
fs.rmSync(dest, { recursive: true, force: true }); // clean-then-copy
fs.mkdirSync(dest, { recursive: true });
if (builtFrom) {
  fs.cpSync(src, dest, { recursive: true }); // built output is already clean
} else {
  // dest (./dist/<name>) is a child of src (the skill dir), so copy entry-by-entry
  // — cpSync refuses to copy a directory into its own subtree.
  for (const entry of fs.readdirSync(src)) {
    if (DENY.has(entry)) continue;
    fs.cpSync(path.join(src, entry), path.join(dest, entry), {
      recursive: true,
    });
  }
}
if (!fs.existsSync(path.join(dest, 'SKILL.md'))) {
  process.stderr.write('pack: dist missing SKILL.md after copy\n');
  process.exit(1);
}
process.stdout.write(`  ✓ dist/${name}/\n`);
