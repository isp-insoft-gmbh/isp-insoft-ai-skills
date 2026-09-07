#!/usr/bin/env node
// isp-insoft-ai-skills root runner: installer and drift inspector.
//
// mise owns the task graph and fans out build/verify to complex units. This
// dependency-free runner owns discovery, status, installation, and removal.
// Each top-level skills/<unit>/ directory installs atomically. Nested SKILL.md
// directories are family members and remain nested in the destination.

import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(fileURLToPath(import.meta.url), '..', '..');
const SKILLS_DIR = path.join(ROOT, 'skills');
const HOME = os.homedir();

process.stdout.on('error', (error) => {
  if (error.code === 'EPIPE') process.exit(0);
  throw error;
});

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

const SOURCE_SKIP = new Set(['.git', 'dist', 'node_modules', 'target']);

function findSkillSources(dir, sources = []) {
  if (!fs.existsSync(dir)) return sources;
  const skillMd = path.join(dir, 'SKILL.md');
  if (fs.existsSync(skillMd)) {
    sources.push(skillMd);
    return sources;
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory() && !SOURCE_SKIP.has(entry.name))
      findSkillSources(path.join(dir, entry.name), sources);
  }
  return sources;
}

function memberName(skillMd) {
  return path.basename(path.dirname(skillMd));
}

let unitCache = null;
function listUnits() {
  if (unitCache) return unitCache;

  unitCache = fs
    .readdirSync(SKILLS_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => {
      const name = entry.name;
      const sourceRoot = path.join(SKILLS_DIR, name);
      const sourceMds = findSkillSources(sourceRoot);
      const built = fs.existsSync(path.join(sourceRoot, 'mise.toml'));
      const distRoot = path.join(sourceRoot, 'dist');
      const distMds = built ? findSkillSources(distRoot) : [];
      const sourceNames = new Set(sourceMds.map(memberName));
      const distNames = new Set(distMds.map(memberName));
      const members = new Map();

      for (const skillMd of sourceMds)
        members.set(memberName(skillMd), { sourceMd: skillMd });
      for (const skillMd of distMds) {
        const member = members.get(memberName(skillMd));
        if (member) member.distMd = skillMd;
      }

      const direct =
        sourceMds.length === 1 && path.dirname(sourceMds[0]) === sourceRoot;
      const artifactRoot = built
        ? direct
          ? path.join(distRoot, name)
          : distRoot
        : sourceRoot;
      const ready = built
        ? sourceNames.size > 0 &&
          sourceNames.size === distNames.size &&
          [...sourceNames].every((member) => distNames.has(member))
        : sourceNames.size > 0;

      return {
        name,
        built,
        direct,
        sourceRoot,
        artifactRoot,
        ready,
        members: [...members.entries()].map(([member, files]) => ({
          name: member,
          ...files,
        })),
      };
    })
    .filter((unit) => unit.members.length > 0)
    .sort((a, b) => (a.name < b.name ? -1 : 1));
  return unitCache;
}

function installUnit(name) {
  return listUnits().find((unit) => unit.name === name) || null;
}

function listUnitNames() {
  return listUnits().map((unit) => unit.name);
}

let artifactCache = null;
function listArtifacts() {
  if (artifactCache) return artifactCache;
  artifactCache = listUnits()
    .flatMap((unit) =>
      unit.members.map((member) => ({
        ...member,
        owner: unit.built ? unit.name : null,
        unit: unit.name,
        dir: member.distMd
          ? path.dirname(member.distMd)
          : path.dirname(member.sourceMd),
      })),
    )
    .sort((a, b) => (a.name < b.name ? -1 : 1));
  return artifactCache;
}

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

function canonicalPath(candidate) {
  const suffix = [];
  let existing = path.resolve(candidate);
  while (!lexists(existing)) {
    const parent = path.dirname(existing);
    if (parent === existing) break;
    suffix.unshift(path.basename(existing));
    existing = parent;
  }
  try {
    return path.join(fs.realpathSync.native(existing), ...suffix);
  } catch {
    return path.resolve(candidate);
  }
}

// Content digest of a directory: sha256 over every file's relpath + bytes, in a
// stable order. null if the dir is absent. Source-only directories are excluded
// from natural Markdown artifacts; complex artifacts are already clean dist.
// Stateless: no marker file, no version field — the bytes are the identity.
function hashDir(dir, skippedDirectories = new Set()) {
  if (!fs.existsSync(dir)) return null;
  const files = [];
  const walk = (d, rel) => {
    const entries = fs
      .readdirSync(d, { withFileTypes: true })
      .sort((a, b) => (a.name < b.name ? -1 : 1));
    for (const e of entries) {
      if (e.isDirectory() && skippedDirectories.has(e.name)) continue;
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

function copyArtifact(unit, dest) {
  const source = unit.artifactRoot;
  if (unit.built) {
    fs.cpSync(source, dest, { recursive: true });
    return;
  }
  fs.cpSync(source, dest, {
    recursive: true,
    filter: (candidate) =>
      candidate === source ||
      !(
        SOURCE_SKIP.has(path.basename(candidate)) &&
        fs.lstatSync(candidate).isDirectory()
      ),
  });
}

// Install ledger: <destination>/.isp-skills.json maps each installed unit to its
// artifact hash at install time. It is the runner's memory of ownership, which
// byte-hashing cannot supply. It prevents clobbering foreign units and supports
// removing orphans whose source was deleted. It lives outside installed units,
// so status hashing is unaffected.
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

  list                              skills (one line each: name and description)
  status [--harness <h>]            per install unit+harness status
  install <unit...> --harness <h>   copy skill(s) or whole families into harness <h>
  install <unit...> --target <dir>  copy skill(s) or whole families into <dir>
  install --harness <h>             install every top-level unit
  install --target <dir>            install every top-level unit into <dir>
  install ... --force               take over a same-named unit we did not install
  uninstall <unit...> --harness <h> remove units we installed (refuses un-owned dirs)
  uninstall ... --force             remove even a dir not in our ledger
  uninstall --orphans [--harness h] remove installs whose source unit was deleted here

build/verify/fmt/lint are native mise tasks: mise run build | //skills/<unit>:build

harnesses: ${Object.keys(HARNESSES).join(', ')}`;
}

function printUsage() {
  console.log(usage());
}

function harnessPath(harness) {
  return HARNESSES[harness] || die(`unknown harness "${harness}"`);
}

function requireUnit(name) {
  if (!installUnit(name)) die(`unknown install unit "${name}"`);
}

function skillDescription(entry) {
  const candidates = [
    entry?.sourceMd,
    path.join(entry.dir, 'SKILL.md'),
    path.join(SKILLS_DIR, entry.name, 'SKILL.md'),
    entry.owner
      ? path.join(
          SKILLS_DIR,
          entry.owner,
          'src',
          'main',
          'markdown',
          entry.name,
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

function truncate(text, width) {
  if (width <= 0) return '';
  if (text.length <= width) return text;
  if (width === 1) return '…';
  return `${text.slice(0, width - 1).trimEnd()}…`;
}

function printList() {
  const entries = listArtifacts();
  const nameWidth = Math.max(...entries.map((entry) => entry.name.length));
  const outputWidth =
    process.stdout.columns || Number.parseInt(process.env.COLUMNS, 10) || 120;
  const useBold =
    !('NO_COLOR' in process.env) &&
    (process.stdout.isTTY ||
      (process.env.FORCE_COLOR && process.env.FORCE_COLOR !== '0'));

  for (const entry of entries) {
    const name = entry.name.padEnd(nameWidth);
    const styledName = useBold ? `\x1b[1m${name}\x1b[22m` : name;
    const description = skillDescription(entry).replace(/\s+/g, ' ');
    const available = outputWidth - nameWidth - 2;
    const suffix = available > 0 ? `  ${truncate(description, available)}` : '';
    process.stdout.write(`${styledName}${suffix}\n`);
  }
}

function installOne(name, base, force = false) {
  const unit = installUnit(name);
  requireUnit(name);
  if (!unit.ready)
    die(`${name}: not built. run: mise run //skills/${name}:build`);
  const source = unit.artifactRoot;
  const dest = path.join(base, name);
  const nested = (parent, child) => {
    const relative = path.relative(parent, child);
    return (
      relative === '' ||
      (!relative.startsWith('..') && !path.isAbsolute(relative))
    );
  };
  const canonicalSource = canonicalPath(source);
  const canonicalDest = canonicalPath(dest);
  if (
    nested(canonicalSource, canonicalDest) ||
    nested(canonicalDest, canonicalSource)
  )
    die(`${name}: install target overlaps source artifact`);
  const ledger = readLedger(base);
  const artifactHash = hashDir(source, unit.built ? undefined : SOURCE_SKIP);
  const destHash = hashDir(dest); // null if not installed
  const owned = name in ledger;

  // A foreign unit of the same name we never installed, whose bytes differ from
  // ours: refuse rather than silently destroy it. --force takes it over.
  if (destHash !== null && !owned && destHash !== artifactHash && !force) {
    process.stdout.write(
      `  ✗ ${name}: a different unit is already installed here and not in our ledger; refusing to overwrite. Use --force to take it over.\n`,
    );
    return;
  }
  // Already our exact content (incl. a pre-ledger install byte-identical to
  // ours): adopt into the ledger, no copy. Idempotent + quiet on reruns.
  if (destHash === artifactHash) {
    ledger[name] = artifactHash;
    writeLedger(base, ledger);
    process.stdout.write(`  = ${name}: unchanged\n`);
    return;
  }
  fs.mkdirSync(base, { recursive: true });
  fs.rmSync(dest, { recursive: true, force: true }); // clean-then-copy: no stale files
  copyArtifact(unit, dest);
  ledger[name] = artifactHash;
  writeLedger(base, ledger);
  process.stdout.write(`  ✓ ${name} -> ${dest}\n`);
}

function uninstallOne(name, harness, force = false) {
  requireUnit(name);
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
      `  ✗ ${name}: installed here but not in our ledger; refusing to remove. Use --force if you really mean it.\n`,
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

// `uninstall --orphans`: remove installs we own whose source unit no longer
// exists, including units whose names are no longer accepted by the CLI.
function uninstallOrphans(harness) {
  const base = harnessPath(harness);
  const ledger = readLedger(base);
  const live = new Set(listUnitNames());
  const orphans = Object.keys(ledger).filter((n) => !live.has(n));
  if (!orphans.length) {
    process.stdout.write(`  - ${harness}: no orphans\n`);
    return;
  }
  for (const n of orphans) {
    fs.rmSync(path.join(base, n), { recursive: true, force: true });
    delete ledger[n];
    process.stdout.write(
      `  ✓ removed orphan unit ${n} (source deleted) from ${harness}\n`,
    );
  }
  writeLedger(base, ledger);
}

function parseArgs(args) {
  let harness = null;
  let target = null;
  let force = false;
  let orphans = false;
  let help = false;
  const names = [];

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === '--harness' || arg === '--target') {
      const value = args[index + 1];
      if (!value || value.startsWith('--')) die(`${arg} needs a value`);
      if (arg === '--harness') harness = value;
      else target = value;
      index += 1;
    } else if (arg === '--force') force = true;
    else if (arg === '--orphans') orphans = true;
    else if (arg === '--help' || arg === '-h') help = true;
    else if (!arg.startsWith('--')) names.push(arg);
  }
  return { harness, target, names, force, orphans, help };
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
    // Drift report: for each install unit and harness, compare the complete unit
    // artifact with its installed copy. The ledger additionally identifies orphans.
    const { harness, target, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    if (target) die('--target is only supported by install');
    const harnesses = harness ? [harness] : Object.keys(HARNESSES);
    const live = listUnitNames();
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
          const unit = installUnit(n);
          const dh = unit.ready
            ? hashDir(unit.artifactRoot, unit.built ? undefined : SOURCE_SKIP)
            : null;
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
    const { harness, target, names, force, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    if (harness && target)
      die('install accepts either --harness or --target, not both');
    if (!harness && !target)
      die('install needs --harness <claude|pi|codex> or --target <dir>');
    const base = target ? path.resolve(target) : harnessPath(harness);
    const order = names.length ? names : listUnitNames();
    for (const n of order) requireUnit(n);
    console.log(
      target ? `install -> ${base}` : `install -> ${harness} (${base})`,
    );
    for (const n of order) installOne(n, base, force);
    break;
  }
  case 'uninstall': {
    const { harness, target, names, force, orphans, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    if (target) die('--target is only supported by install');
    if (orphans) {
      const harnesses = harness ? [harness] : Object.keys(HARNESSES);
      for (const h of harnesses) uninstallOrphans(h);
      break;
    }
    if (!harness) die('uninstall needs --harness <claude|pi|codex>');
    if (!names.length) die('uninstall needs a unit name (or --orphans)');
    for (const n of names) uninstallOne(n, harness, force);
    break;
  }
  default:
    printUsage();
    process.exitCode = 1;
}
