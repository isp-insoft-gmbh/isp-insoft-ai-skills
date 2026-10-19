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
import {
  chooseDestination,
  chooseSkills,
  createPrompt,
  readHistory,
  rememberProject,
} from './install-ui.mjs';
import { bold, descriptionLines } from './output.mjs';

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

function inspectInstalledHash(dest) {
  try {
    if (!lexists(dest)) return null;
    const hash = hashDir(dest);
    if (hash === null) throw new Error('installed path is unavailable');
    return hash;
  } catch (error) {
    console.error(
      `  ✗ ${bold(path.basename(dest), process.stderr)}: cannot inspect installed content; refusing to overwrite: ${error.message}`,
    );
    process.exitCode = 1;
    return undefined;
  }
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
function readLedger(base, strict = false) {
  try {
    const ledger = JSON.parse(fs.readFileSync(ledgerPath(base), 'utf8'));
    if (
      strict &&
      (!ledger ||
        Array.isArray(ledger) ||
        typeof ledger !== 'object' ||
        Object.entries(ledger).some(
          ([name, hash]) =>
            !/^[a-z0-9][a-z0-9-]*$/.test(name) ||
            typeof hash !== 'string' ||
            !/^[a-f0-9]{64}$/.test(hash),
        ))
    )
      throw new Error('expected unit names mapped to SHA-256 hashes');
    return ledger;
  } catch (error) {
    if (strict && error.code !== 'ENOENT')
      die(`invalid inventory ${ledgerPath(base)}: ${error.message}`);
    return {};
  }
}
function writeLedger(base, ledger) {
  fs.mkdirSync(base, { recursive: true });
  fs.writeFileSync(ledgerPath(base), `${JSON.stringify(ledger, null, 2)}\n`);
}

function usage() {
  return `usage: mise run sk -- [command] [options]

  (no command)                     choose destination, then update or pick skills
  add [unit...]                    install selected units; prompts for destination
  add ... --harness <h>            select a global harness
  add ... --target <dir>           select a project skills directory
  add ... --pick                   interactively choose skills to add
  add ... --all                    explicitly install every top-level unit
  add ... --force                  take over a foreign same-named unit
  update                          update inventoried units in known global locations
  update --harness <h>             update inventoried units in one harness
  update --target <dir>            update inventoried units in a project skills dir
  update ... --force               discard local edits; requires --harness or --target
  remove <unit...> --harness <h>    remove units we installed
  remove ... --force               remove even a directory not in our inventory
  remove --orphans [--harness <h>] offer deletion of units whose source was deleted
  status [--harness <h>]           per install unit+harness status
  list                            skill names and descriptions

Options without a command use the interactive add/update flow.
Explicit names or --all are required for first install without a terminal.
Orphan deletion requires interactive confirmation; default: keep.

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
  for (const entry of entries) {
    const name = entry.name.padEnd(nameWidth);
    const styledName = bold(name);
    const description = skillDescription(entry).replace(/\s+/g, ' ');
    const available = outputWidth - nameWidth - 2;
    const suffix = available > 0 ? `  ${truncate(description, available)}` : '';
    process.stdout.write(`${styledName}${suffix}\n`);
  }
}

function installOne(
  name,
  base,
  { force = false, overwriteModified = false } = {},
) {
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
  const ledger = readLedger(base, true);
  const artifactHash = hashDir(source, unit.built ? undefined : SOURCE_SKIP);
  const destHash = inspectInstalledHash(dest); // null if not installed
  if (destHash === undefined) return false;
  const owned = name in ledger;

  if (
    owned &&
    destHash !== null &&
    destHash !== ledger[name] &&
    !overwriteModified
  ) {
    console.error(
      `  ✗ ${bold(name, process.stderr)}: locally modified; refusing to overwrite`,
    );
    process.exitCode = 1;
    return false;
  }

  // A foreign unit of the same name we never installed, whose bytes differ from
  // ours: refuse rather than silently destroy it. --force takes it over.
  if (destHash !== null && !owned && destHash !== artifactHash && !force) {
    process.stdout.write(
      `  ✗ ${bold(name)}: a different unit is already installed here and not in our ledger; refusing to overwrite. Use --force to take it over.\n`,
    );
    process.exitCode = 1;
    return false;
  }
  // Already our exact content (incl. a pre-ledger install byte-identical to
  // ours): adopt into the ledger, no copy. Idempotent + quiet on reruns.
  if (destHash === artifactHash) {
    ledger[name] = artifactHash;
    writeLedger(base, ledger);
    process.stdout.write(`  = ${bold(name)}: unchanged\n`);
    return 'unchanged';
  }
  fs.mkdirSync(base, { recursive: true });
  fs.rmSync(dest, { recursive: true, force: true }); // clean-then-copy: no stale files
  copyArtifact(unit, dest);
  ledger[name] = artifactHash;
  writeLedger(base, ledger);
  process.stdout.write(`  ✓ ${bold(name)}: updated\n`);
  return 'updated';
}

function describeOrphan(name, base) {
  const dest = path.join(base, name);
  console.log(`  - ${bold(name)}: ORPHAN (source missing)`);
  console.log(`    Location: ${dest}`);
  try {
    const members = findSkillSources(dest);
    if (!members.length) console.log('    Description unavailable.');
    for (const sourceMd of members) {
      const description = skillDescription({
        name: memberName(sourceMd),
        sourceMd,
        dir: path.dirname(sourceMd),
      });
      console.log(
        `    ${bold(memberName(sourceMd))}\n${descriptionLines(description || 'Description unavailable.', '      ')}`,
      );
    }
  } catch {
    console.log(
      '    Description unavailable (installed files could not be read).',
    );
  }
}

async function offerOrphanRemoval(name, base, ledger, ask) {
  const dest = path.join(base, name);
  describeOrphan(name, base);
  if (!ask) {
    console.log('    Preserved; deletion requires interactive confirmation.');
    return false;
  }
  const hash = inspectInstalledHash(dest);
  if (hash === undefined) return undefined;
  if (hash !== null && hash !== ledger[name])
    console.log('    WARNING: installed content has local modifications.');
  if (hash === null)
    console.log(
      '    Installed directory missing; only inventory entry will be removed.',
    );
  const answer = await ask(
    `Delete orphan ${bold(name)} and all its installed files? [y/N]: `,
  );
  if (!answer || !/^y(es)?$/i.test(answer)) {
    console.log(`    ${bold(name)}: preserved`);
    return false;
  }
  // Earlier units may already have refreshed their installation hashes.
  const currentLedger = readLedger(base, true);
  fs.rmSync(dest, { recursive: true, force: true });
  delete currentLedger[name];
  writeLedger(base, currentLedger);
  delete ledger[name];
  console.log(`  ✓ removed orphan ${bold(name)}`);
  return true;
}

function printUpdateSummary(results) {
  const total = {
    updated: 0,
    unchanged: 0,
    deleted: 0,
    skipped: 0,
    kept: 0,
    conflicts: [],
    errors: [],
  };
  for (const result of results) {
    for (const key of ['updated', 'unchanged', 'deleted', 'skipped', 'kept'])
      total[key] += result[key];
    total.conflicts.push(...result.conflicts);
    total.errors.push(...result.errors);
  }
  console.log(
    `\n${bold('Update summary')}: ${total.updated} updated | ${total.unchanged} unchanged | ${total.deleted} deleted`,
  );
  console.log(
    `  ${total.conflicts.length} preserved conflicts | ${total.kept} kept orphans | ${total.skipped} skipped | ${total.errors.length} errors`,
  );
  for (const [label, issues] of [
    ['Preserved conflict', total.conflicts],
    ['Error', total.errors],
  ]) {
    for (const issue of issues) {
      const name = path.basename(issue.slice(0, issue.lastIndexOf(': ')));
      console.log(`  ${label}: ${bold(name)}\n    ${issue}`);
    }
  }
  if (total.conflicts.length || total.errors.length) {
    console.log(bold('Exit status 1: conflicts or errors remain.'));
    console.log('Successful changes remain applied; no rollback.');
  }
}

async function updateInstalled(base, ledger, ask, force = false) {
  const result = {
    updated: 0,
    unchanged: 0,
    deleted: 0,
    skipped: 0,
    kept: 0,
    conflicts: [],
    errors: [],
  };
  console.log(`\n${bold('Update')}\n  ${base}`);
  for (const [name, installedHash] of Object.entries(ledger)) {
    const unit = installUnit(name);
    if (!unit) {
      const removed = await offerOrphanRemoval(name, base, ledger, ask);
      if (removed) result.deleted += 1;
      else if (removed === undefined)
        result.errors.push(
          `${path.join(base, name)}: cannot inspect installed content`,
        );
      else result.kept += 1;
      continue;
    }
    const destHash = inspectInstalledHash(path.join(base, name));
    if (destHash === undefined) {
      result.errors.push(
        `${path.join(base, name)}: cannot inspect installed content`,
      );
      continue;
    }
    if (destHash === null) {
      console.log(`  - ${bold(name)}: not installed; skipped`);
      result.skipped += 1;
      continue;
    }
    if (destHash !== installedHash && !force) {
      console.error(
        `  ✗ ${bold(name, process.stderr)}: locally modified; refusing to overwrite`,
      );
      process.exitCode = 1;
      result.conflicts.push(`${path.join(base, name)}: locally modified`);
      continue;
    }
    if (!unit.ready) {
      console.error(
        `  ✗ ${bold(name, process.stderr)}: not built. run: mise run //skills/${name}:build`,
      );
      process.exitCode = 1;
      result.errors.push(`${path.join(base, name)}: not built`);
      continue;
    }
    if (destHash !== installedHash)
      console.error(
        `  ! ${bold(name, process.stderr)}: --force replaces local edits with this checkout's version`,
      );
    const outcome = installOne(name, base, { overwriteModified: force });
    if (outcome) result[outcome] += 1;
    else result.errors.push(`${path.join(base, name)}: installation refused`);
  }
  return result;
}

async function install(args) {
  let { harness, target, names, force, help, pick, all, orphans } = parseArgs(
    args,
    true,
  );
  if (help) return printUsage();
  if (harness && target)
    die('add accepts either --harness or --target, not both');
  if (orphans || (pick && all) || (names.length && (pick || all)))
    die('add accepts unit names, --pick, or --all, not a combination');
  const interactive = Boolean(process.stdin.isTTY && process.stdout.isTTY);
  if (!interactive && !harness && !target)
    die('non-interactive add needs --harness <h> or --target <dir>');
  let prompt;
  const ask = (question) => {
    prompt ||= createPrompt();
    return prompt.ask(question);
  };
  try {
    if (!harness && !target) {
      const destination = await chooseDestination(
        ask,
        readHistory(),
        HARNESSES,
      );
      if (!destination) return console.log('Cancelled; no changes.');
      ({ harness, target } = destination);
    }
    const base = canonicalPath(
      target ? path.resolve(target) : harnessPath(harness),
    );
    const ledger = readLedger(base, true);
    const updating =
      !names.length && !pick && !all && lexists(ledgerPath(base));
    console.log(`\n${bold('Destination')}\n  ${base}`);
    if (!updating && !names.length && !all) {
      if (!interactive)
        die('first install needs unit names or --all in non-interactive mode');
      const entries = listUnits().map((unit) => ({
        name: unit.name,
        description:
          unit.members.length > 1
            ? `${unit.members.length} skills`
            : skillDescription({ ...unit.members[0], dir: unit.sourceRoot }),
      }));
      names = await chooseSkills(ask, entries, new Set(Object.keys(ledger)));
      if (!names) return console.log('Cancelled; no changes.');
    }
    if (pick && !interactive) die('--pick requires an interactive terminal');
    const order = all ? listUnitNames() : names;
    for (const name of order) requireUnit(name);
    if (interactive) {
      console.log(
        updating
          ? 'Update inventoried skills only.'
          : `Install: ${order.map((name) => bold(name)).join(', ')}`,
      );
      const confirmation = await ask('Proceed? [y/N]: ');
      if (!confirmation || !/^y(es)?$/i.test(confirmation))
        return console.log('Cancelled; no changes.');
    }
    let completed;
    if (updating) {
      const result = await updateInstalled(
        base,
        ledger,
        interactive ? ask : null,
      );
      printUpdateSummary([result]);
      completed = result.updated + result.unchanged + result.deleted;
    } else
      completed = order.filter((name) =>
        installOne(name, base, { force }),
      ).length;
    if (target && completed && !process.exitCode) rememberProject(base);
  } finally {
    prompt?.close();
  }
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
    process.stdout.write(`  - ${bold(name)} not installed in ${harness}\n`);
    return;
  }
  // A dir we did not install: don't remove it on a bare name. --force overrides.
  if (!known && !force) {
    process.stdout.write(
      `  ✗ ${bold(name)}: installed here but not in our ledger; refusing to remove. Use --force if you really mean it.\n`,
    );
    return;
  }
  fs.rmSync(dest, { recursive: true, force: true });
  if (known) {
    delete ledger[name];
    writeLedger(base, ledger);
  }
  process.stdout.write(`  ✓ removed ${bold(name)}\n    ${dest}\n`);
}

// Offer removal of owned units whose source no longer exists.
async function uninstallOrphans(harness, ask) {
  const base = harnessPath(harness);
  const ledger = readLedger(base, true);
  const live = new Set(listUnitNames());
  const orphans = Object.keys(ledger).filter((n) => !live.has(n));
  if (!orphans.length) {
    process.stdout.write(`  - ${harness}: no orphans\n`);
    return;
  }
  for (const n of orphans) await offerOrphanRemoval(n, base, ledger, ask);
}

function parseArgs(args, strict = false) {
  let harness = null;
  let target = null;
  let force = false;
  let orphans = false;
  let help = false;
  let pick = false;
  let all = false;
  const names = [];

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === '--harness' || arg === '--target') {
      const value = args[index + 1];
      if (!value || value.startsWith('--')) die(`${arg} needs a value`);
      if (arg === '--harness') harness = value;
      else target = value;
      index += 1;
    } else if (arg === '--pick') pick = true;
    else if (arg === '--all') all = true;
    else if (arg === '--force') force = true;
    else if (arg === '--orphans') orphans = true;
    else if (arg === '--help' || arg === '-h') help = true;
    else if (!arg.startsWith('--')) names.push(arg);
    else if (strict) die(`unknown option "${arg}"`);
  }
  return { harness, target, names, force, orphans, help, pick, all };
}

let [cmd, ...rest] = process.argv.slice(2);
if (
  cmd === undefined ||
  (cmd.startsWith('-') && cmd !== '--help' && cmd !== '-h')
) {
  rest = process.argv.slice(2);
  cmd = 'add';
}
switch (cmd) {
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
    if (target) die('--target is only supported by add and update');
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
          state = 'ORPHAN'; // source deleted; status remains read-only
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
        console.log(`${bold(n.padEnd(18))} ${h.padEnd(7)} ${state}`);
        if (state === 'ORPHAN') describeOrphan(n, base);
      }
    }
    break;
  }
  case 'add': {
    await install(rest);
    break;
  }
  case 'update': {
    const { harness, target, names, force, orphans, help, pick, all } =
      parseArgs(rest, true);
    if (help) {
      printUsage();
      break;
    }
    if (harness && target)
      die('update accepts either --harness or --target, not both');
    if (names.length || orphans || pick || all)
      die('update accepts --harness <h> or --target <dir>, optionally --force');
    if (force && !harness && !target)
      die('update --force needs an explicit --harness <h> or --target <dir>');
    const bases = target
      ? [path.resolve(target)]
      : harness
        ? [harnessPath(harness)]
        : Object.values(HARNESSES);
    // Validate every inventory before updating any destination.
    const inventories = [...new Set(bases.map(canonicalPath))].map((base) => ({
      base,
      ledger: readLedger(base, true),
    }));
    const prompt =
      process.stdin.isTTY && process.stdout.isTTY ? createPrompt() : null;
    const results = [];
    try {
      if (
        force &&
        inventories.some(({ ledger }) => Object.keys(ledger).length)
      ) {
        console.error(
          bold(
            'WARNING: --force discards local edits in inventoried skills. No backup is created.',
            process.stderr,
          ),
        );
        console.error(`Destination: ${inventories[0].base}`);
        if (prompt) {
          const answer = await prompt.ask(
            'Overwrite local edits with this checkout? [y/N]: ',
          );
          if (!answer || !/^y(es)?$/i.test(answer)) {
            console.log('Cancelled; no changes.');
            break;
          }
        }
      }
      for (const { base, ledger } of inventories) {
        if (!Object.keys(ledger).length) {
          console.log(`  - ${base}: no inventoried units; skipped`);
          continue;
        }
        const result = await updateInstalled(base, ledger, prompt?.ask, force);
        results.push(result);
        const completed = result.updated + result.unchanged + result.deleted;
        if (target && completed && !process.exitCode) rememberProject(base);
      }
      printUpdateSummary(results);
    } finally {
      prompt?.close();
    }
    break;
  }
  case 'remove': {
    const { harness, target, names, force, orphans, help } = parseArgs(rest);
    if (help) {
      printUsage();
      break;
    }
    if (target) die('--target is only supported by add and update');
    if (orphans) {
      const harnesses = harness ? [harness] : Object.keys(HARNESSES);
      const prompt =
        process.stdin.isTTY && process.stdout.isTTY ? createPrompt() : null;
      try {
        for (const h of harnesses) await uninstallOrphans(h, prompt?.ask);
      } finally {
        prompt?.close();
      }
      break;
    }
    if (!harness) die('remove needs --harness <claude|pi|codex>');
    if (!names.length) die('remove needs a unit name (or --orphans)');
    for (const n of names) uninstallOne(n, harness, force);
    break;
  }
  default:
    printUsage();
    process.exitCode = 1;
}
