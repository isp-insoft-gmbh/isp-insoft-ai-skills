#!/usr/bin/env node
// isp-insoft-ai-skills root runner — the installer + drift inspector.
//
// build/verify/fmt/lint are NOT here: they are native mise monorepo tasks
// (//skills/<name>:<task>), which activate each skill's own toolchain. This
// runner owns only what mise has no equivalent for — list/status (content-hash
// drift) and install/uninstall (clean-copy a built dist/ into a harness dir).
//
// Bare node, no deps, no manifest: build owners are skills/<name>/ dirs holding
// mise.toml. Built artifacts live under each owner's dist/ and may include
// companion skills, such as fxdriver-instructions.

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(fileURLToPath(import.meta.url), '..', '..');
const SKILLS_DIR = path.join(ROOT, 'skills');
const HOME = os.homedir();

// Where each harness discovers skills. Override with SKILLS_HARNESS_<NAME>.
const HARNESSES = {
  claude:
    process.env.SKILLS_HARNESS_CLAUDE || path.join(HOME, '.claude', 'skills'),
  pi:
    process.env.SKILLS_HARNESS_PI || path.join(HOME, '.pi', 'agent', 'skills'),
  codex:
    process.env.SKILLS_HARNESS_CODEX || path.join(HOME, '.codex', 'skills'),
};

const die = (msg) => {
  process.stderr.write(`error: ${msg}\n`);
  process.exit(1);
};

function listSourceSkillNames() {
  return fs
    .readdirSync(SKILLS_DIR, { withFileTypes: true })
    .filter(
      (d) =>
        d.isDirectory() &&
        fs.existsSync(path.join(SKILLS_DIR, d.name, 'mise.toml')),
    )
    .map((d) => d.name)
    .sort();
}

function addArtifact(entries, name, owner, sourceMd = null) {
  const existing = entries.get(name) || {};
  entries.set(name, {
    name,
    owner,
    sourceMd: sourceMd || existing.sourceMd || null,
    dir: path.join(SKILLS_DIR, owner, 'dist', name),
  });
}

function listArtifacts() {
  const entries = new Map();
  for (const owner of listSourceSkillNames()) {
    addArtifact(
      entries,
      owner,
      owner,
      path.join(SKILLS_DIR, owner, 'SKILL.md'),
    );

    const markdownRoot = path.join(
      SKILLS_DIR,
      owner,
      'src',
      'main',
      'markdown',
    );
    if (fs.existsSync(markdownRoot)) {
      for (const d of fs.readdirSync(markdownRoot, { withFileTypes: true })) {
        if (
          d.isDirectory() &&
          fs.existsSync(path.join(markdownRoot, d.name, 'SKILL.md'))
        ) {
          addArtifact(
            entries,
            d.name,
            owner,
            path.join(markdownRoot, d.name, 'SKILL.md'),
          );
        }
      }
    }

    const distRoot = path.join(SKILLS_DIR, owner, 'dist');
    if (fs.existsSync(distRoot)) {
      for (const d of fs.readdirSync(distRoot, { withFileTypes: true })) {
        if (
          d.isDirectory() &&
          fs.existsSync(path.join(distRoot, d.name, 'SKILL.md'))
        ) {
          addArtifact(entries, d.name, owner);
        }
      }
    }
  }
  return [...entries.values()].sort((a, b) => (a.name < b.name ? -1 : 1));
}

function artifact(name) {
  return listArtifacts().find((entry) => entry.name === name) || null;
}

function listSkillNames() {
  return listArtifacts().map((entry) => entry.name);
}

const distDir = (name) =>
  artifact(name)?.dir || path.join(SKILLS_DIR, name, 'dist', name);
const isBuilt = (name) => fs.existsSync(path.join(distDir(name), 'SKILL.md'));

// lstat-based existence: true even for a broken symlink (existsSync follows the
// link and reports false). rmSync on a symlink unlinks the link only — it never
// recurses into the target — so removing by lexists is safe for linked dests.
const lexists = (p) => {
  try {
    fs.lstatSync(p);
    return true;
  } catch {
    return false;
  }
};

// Content digest of a directory: sha256 over every file's relpath + bytes, in a
// stable order. null if the dir is absent. Drift is detected by comparing a
// skill's dist hash to its installed copy — artifact vs artifact, so it is
// correct even when a build is non-reproducible (fxdriver's jar embeds
// timestamps): a rebuild changes dist, which is exactly when it IS stale.
// Stateless: no marker file, no version field — the bytes are the identity.
function hashDir(dir) {
  if (!fs.existsSync(dir)) return null;
  const files = [];
  const walk = (d, rel) => {
    const entries = fs
      .readdirSync(d, { withFileTypes: true })
      .sort((a, b) => (a.name < b.name ? -1 : 1));
    for (const e of entries) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(path.join(d, e.name), r);
      else files.push([r, fs.readFileSync(path.join(d, e.name))]);
    }
  };
  walk(dir, '');
  const h = createHash('sha256');
  for (const [r, buf] of files) {
    h.update(r);
    h.update('\0');
    h.update(buf);
  }
  return h.digest('hex');
}

// Install ledger: <harness>/.isp-skills.json maps each name we installed to the
// dist hash at install time. It is the runner's MEMORY of what it owns — the one
// thing byte-hashing can't supply. Used to (a) refuse clobbering a same-named
// skill we did not install, and (b) `uninstall --orphans` whose source we deleted.
// It lives at the harness root, outside skill dirs, so per-skill drift hashing
// (status) is unaffected. This is install-time runtime state, NOT skill source.
const ledgerPath = (base) => path.join(base, '.isp-skills.json');
function readLedger(base) {
  try {
    return JSON.parse(fs.readFileSync(ledgerPath(base), 'utf8'));
  } catch {
    return {};
  }
}
function writeLedger(base, ledger) {
  fs.mkdirSync(base, { recursive: true });
  fs.writeFileSync(ledgerPath(base), `${JSON.stringify(ledger, null, 2)}\n`);
}

function usage() {
  return `usage: node scripts/skills.mjs <command>

  list                              skills (name, kind, build state, harnesses, description)
  status [--harness <h>]            per skill+harness: current | STALE | not-installed | ORPHAN
  install <name...> --harness <h>   copy built skill(s) into harness <h>
  install --harness <h>             install every skill
  install ... --force               take over a same-named skill we did not install
  uninstall <name...> --harness <h> remove skill(s) we installed (refuses un-owned dirs)
  uninstall ... --force             remove even a dir not in our ledger
  uninstall --orphans [--harness h] remove installs whose source skill was deleted here

build/verify/fmt/lint are native mise tasks: mise run build | //skills/<name>:build

harnesses: ${Object.keys(HARNESSES).join(', ')}`;
}

function printUsage() {
  console.log(usage());
}

function harnessPath(harness) {
  return HARNESSES[harness] || die(`unknown harness "${harness}"`);
}

function requireSkill(name) {
  if (!artifact(name)) die(`unknown skill "${name}"`);
}

function skillDescription(name) {
  const entry = artifact(name);
  const candidates = [
    entry?.sourceMd,
    path.join(distDir(name), 'SKILL.md'),
    path.join(SKILLS_DIR, name, 'SKILL.md'),
    entry
      ? path.join(
          SKILLS_DIR,
          entry.owner,
          'src',
          'main',
          'markdown',
          name,
          'SKILL.md',
        )
      : null,
  ];
  const skillMd = candidates.find(
    (candidate) => candidate && fs.existsSync(candidate),
  );
  if (!skillMd) return '';
  const md = fs.readFileSync(skillMd, 'utf8');
  const fm = md.match(/^---\r?\n([\s\S]*?)\r?\n---/);
  if (!fm) return '';
  const lines = fm[1].split(/\r?\n/);
  const descriptionLine = lines.findIndex((line) =>
    line.startsWith('description:'),
  );
  if (descriptionLine < 0) return '';
  const first = lines[descriptionLine].replace(/^description:\s*/, '').trim();
  const parts = first ? [first] : [];
  for (const line of lines.slice(descriptionLine + 1)) {
    if (/^[A-Za-z][\w-]*:/.test(line)) break;
    if (line.trim()) parts.push(line.trim());
  }
  return parts.join(' ').replace(/^["']|["']$/g, '');
}

function skillKind(name) {
  const owner = artifact(name)?.owner || name;
  const dir = path.join(SKILLS_DIR, owner);
  if (fs.existsSync(path.join(dir, 'pom.xml'))) return 'maven/java';
  if (fs.existsSync(path.join(dir, 'scripts'))) return 'markdown+node';
  return 'markdown';
}

function printList() {
  const rows = listSkillNames().map((name) => ({
    name,
    kind: skillKind(name),
    built: isBuilt(name) ? 'yes' : 'no',
    harnesses: Object.keys(HARNESSES).join(','),
    description: skillDescription(name),
  }));
  const columns = [
    ['skill', 'name'],
    ['kind', 'kind'],
    ['built', 'built'],
    ['harnesses', 'harnesses'],
  ];
  const widths = columns.map(([label, key]) =>
    Math.max(label.length, ...rows.map((row) => row[key].length)),
  );
  console.log(
    columns.map(([label], index) => label.padEnd(widths[index])).join('  ') +
      '  description',
  );
  for (const row of rows) {
    console.log(
      `${columns
        .map(([, key], index) => row[key].padEnd(widths[index]))
        .join('  ')}  ${row.description}`,
    );
  }
}

function installOne(name, harness, force = false) {
  requireSkill(name);
  if (!isBuilt(name))
    die(
      `${name}: not built. run: mise run //skills/${artifact(name).owner}:build`,
    );
  const base = harnessPath(harness);
  const dest = path.join(base, name);
  const ledger = readLedger(base);
  const distHash = hashDir(distDir(name));
  const destHash = hashDir(dest); // null if not installed
  const owned = name in ledger;

  // A foreign skill of the same name we never installed, whose bytes differ from
  // ours: refuse rather than silently destroy it. --force takes it over.
  if (destHash !== null && !owned && destHash !== distHash && !force) {
    process.stdout.write(
      `  ✗ ${name}: a different skill is already installed here and not in our ledger — refusing to overwrite. Use --force to take it over.\n`,
    );
    return;
  }
  // Already our exact content (incl. a pre-ledger install byte-identical to
  // ours): adopt into the ledger, no copy. Idempotent + quiet on reruns.
  if (destHash === distHash) {
    ledger[name] = distHash;
    writeLedger(base, ledger);
    process.stdout.write(`  = ${name}: unchanged\n`);
    return;
  }
  fs.mkdirSync(base, { recursive: true });
  fs.rmSync(dest, { recursive: true, force: true }); // clean-then-copy: no stale files
  fs.cpSync(distDir(name), dest, { recursive: true });
  ledger[name] = distHash;
  writeLedger(base, ledger);
  process.stdout.write(`  ✓ ${name} -> ${dest}\n`);
}

function uninstallOne(name, harness, force = false) {
  requireSkill(name);
  const base = harnessPath(harness);
  const dest = path.join(base, name);
  const ledger = readLedger(base);
  const known = name in ledger;
  const exists = lexists(dest); // catches a broken/dangling symlink too

  if (!exists) {
    // Dir already gone; clear a stale ledger entry if we had one.
    if (known) {
      delete ledger[name];
      writeLedger(base, ledger);
    }
    process.stdout.write(`  - ${name} not installed in ${harness}\n`);
    return;
  }
  // A dir we did not install: don't remove it on a bare name. --force overrides.
  if (!known && !force) {
    process.stdout.write(
      `  ✗ ${name}: installed here but not in our ledger — refusing to remove. Use --force if you really mean it.\n`,
    );
    return;
  }
  fs.rmSync(dest, { recursive: true, force: true });
  if (known) {
    delete ledger[name];
    writeLedger(base, ledger);
  }
  process.stdout.write(`  ✓ removed ${dest}\n`);
}

// `uninstall --orphans`: remove installs we own (in the ledger) whose source
// skill no longer exists. The discovery half of uninstall — when you no longer
// have the deleted skill's name to type.
function uninstallOrphans(harness) {
  const base = harnessPath(harness);
  const ledger = readLedger(base);
  const live = new Set(listSkillNames());
  const orphans = Object.keys(ledger).filter((n) => !live.has(n));
  if (!orphans.length) {
    process.stdout.write(`  - ${harness}: no orphans\n`);
    return;
  }
  for (const n of orphans) {
    fs.rmSync(path.join(base, n), { recursive: true, force: true });
    delete ledger[n];
    process.stdout.write(
      `  ✓ removed orphan ${n} (source deleted) from ${harness}\n`,
    );
  }
  writeLedger(base, ledger);
}

function parseArgs(args) {
  const i = args.indexOf('--harness');
  const harness = i >= 0 ? args[i + 1] : null;
  const force = args.includes('--force');
  const orphans = args.includes('--orphans');
  const help = args.includes('--help') || args.includes('-h');
  const names = args.filter((a) => !a.startsWith('--') && a !== harness);
  return { harness, names, force, orphans, help };
}

const [cmd, ...rest] = process.argv.slice(2);
switch (cmd) {
  case undefined:
  case 'help':
  case '--help':
  case '-h': {
    printUsage();
    break;
  }
  case 'list': {
    const { help } = parseArgs(rest);
    if (help) printUsage();
    else printList();
    break;
  }
  case 'status': {
    // Drift report: for each skill x harness, is the installed copy current?
    // Per-skill state is byte-hash (dist vs installed). The ledger adds one
    // thing hashing can't: ORPHAN — we installed it, but its source is gone.
    const { harness, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    const harnesses = harness ? [harness] : Object.keys(HARNESSES);
    const live = listSkillNames();
    const liveSet = new Set(live);
    for (const h of harnesses) {
      const base = harnessPath(h);
      const names = [
        ...new Set([...live, ...Object.keys(readLedger(base))]),
      ].sort();
      for (const n of names) {
        let state;
        if (!liveSet.has(n)) {
          state = 'ORPHAN'; // we installed it; source deleted -> `uninstall --orphans`
        } else {
          const dh = hashDir(distDir(n));
          const ih = hashDir(path.join(base, n));
          state =
            dh === null
              ? 'not-built'
              : ih === null
                ? 'not-installed'
                : ih === dh
                  ? 'current'
                  : 'STALE';
        }
        console.log(`${n.padEnd(18)} ${h.padEnd(7)} ${state}`);
      }
    }
    break;
  }
  case 'install': {
    const { harness, names, force, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    if (!harness) die('install needs --harness <claude|pi|codex>');
    const base = harnessPath(harness);
    const order = names.length ? names : listSkillNames();
    for (const n of order) requireSkill(n);
    console.log(`install -> ${harness} (${base})`);
    for (const n of order) installOne(n, harness, force);
    break;
  }
  case 'uninstall': {
    const { harness, names, force, orphans, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    if (orphans) {
      const harnesses = harness ? [harness] : Object.keys(HARNESSES);
      for (const h of harnesses) uninstallOrphans(h);
      break;
    }
    if (!harness) die('uninstall needs --harness <claude|pi|codex>');
    if (!names.length) die('uninstall needs a skill name (or --orphans)');
    for (const n of names) uninstallOne(n, harness, force);
    break;
  }
  default:
    printUsage();
    process.exitCode = 1;
}
