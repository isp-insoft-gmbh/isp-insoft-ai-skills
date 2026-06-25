#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';

const execFileP = promisify(execFile);
// kkg checkout location is per-developer, not standardized — no default path.
// Set ISP_KKG_ROOT to your kkg root (the dir holding `.bare` and `trunk`).
const KKG_ROOT = process.env.ISP_KKG_ROOT || null;
const OFFICE_PAGE_ID = process.env.ISP_OFFICE_PAGE_ID || '166494212';

function line(s = '') {
  console.log(s);
}
function ok(s) {
  line(`✓ ${s}`);
}
function warn(s) {
  line(`! ${s}`);
}
function fail(s) {
  line(`✗ ${s}`);
}

async function run(cmd, args = [], opts = {}) {
  try {
    const { stdout, stderr } = await execFileP(cmd, args, {
      timeout: opts.timeout ?? 120_000,
      cwd: opts.cwd,
      windowsHide: true,
      maxBuffer: opts.maxBuffer ?? 10 * 1024 * 1024,
    });
    return { ok: true, stdout, stderr };
  } catch (err) {
    return {
      ok: false,
      stdout: err.stdout ?? '',
      stderr: err.stderr ?? err.message ?? String(err),
      code: err.code,
    };
  }
}

async function has(cmd) {
  const probe =
    process.platform === 'win32'
      ? await run('where', [cmd], { timeout: 10_000 })
      : await run('which', [cmd], { timeout: 10_000 });
  return probe.ok;
}

function jsonParse(s) {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

function setupGuide() {
  line('');
  line('Setup guide (only missing items matter):');
  line('• Node.js 22+: required to run this cross-platform checker');
  line(
    '• acli: install Atlassian CLI; run `acli jira auth login --web` and `acli confluence auth login --web`',
  );
  line('• gh: install GitHub CLI; run `gh auth login` with repo scope');
  line('• git: install Git; needed for KKG worktrees/branches');
  line(
    '• Chrome CDP: optional; only when Confluence pages require live browser session',
  );
}

function decodeEntities(s) {
  return s
    .replace(/&#(x[0-9a-f]+|\d+);/gi, (_, n) =>
      String.fromCodePoint(
        n[0].toLowerCase() === 'x' ? parseInt(n.slice(1), 16) : parseInt(n, 10),
      ),
    )
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&ldquo;/g, '“')
    .replace(/&rdquo;/g, '”')
    .replace(/&ouml;/g, 'ö')
    .replace(/&auml;/g, 'ä')
    .replace(/&uuml;/g, 'ü')
    .replace(/&Ouml;/g, 'Ö')
    .replace(/&Auml;/g, 'Ä')
    .replace(/&Uuml;/g, 'Ü')
    .replace(/&szlig;/g, 'ß');
}

function stripTags(html) {
  return decodeEntities(html)
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function extractCells(rowHtml) {
  const cellRe = /<(td|th)\b([^>]*)>([\s\S]*?)<\/\1>/gi;
  const cells = [];
  for (const m of rowHtml.matchAll(cellRe)) {
    const attrs = m[2];
    const text = stripTags(m[3]);
    const color = (
      attrs.match(/data-highlight-colou?r="([^"]+)"/i)?.[1] || ''
    ).toLowerCase();
    cells.push({ text, color });
  }
  return cells;
}

function splitLegendTokens(text) {
  const compact = text.replace(/[“”"]/g, '').trim();
  if (/^(B|BP|H|A|AP)(\/(B|BP|H|A|AP))*$/i.test(compact))
    return compact.toUpperCase().split('/');
  if (/^MA\s*Kürzel$/i.test(compact)) return ['MA Kürzel'];
  return [];
}

function addColor(map, key, color) {
  if (!key || !color) return;
  const k = key.toUpperCase();
  if (!map.has(k)) map.set(k, new Set());
  map.get(k).add(color);
}

function parseOfficeLegend(body, monthLabel) {
  const start = body.indexOf(monthLabel);
  const scope = start > 0 ? body.slice(0, start) : body;
  const tableMatch = scope.match(/<table\b[\s\S]*?<\/table>/i);
  const result = {
    validColorsByValue: new Map(),
    plannedColorsByValue: new Map(),
    summary: 'Legend (page top): not found',
  };
  if (!tableMatch) return result;

  const rows = tableMatch[0].match(/<tr\b[\s\S]*?<\/tr>/gi) || [];
  const summaries = [];
  for (const row of rows) {
    const cells = extractCells(row);
    if (cells.length < 2) continue;
    const desc = cells.at(-1)?.text || '';
    const parts = [];
    for (const cell of cells.slice(0, -1)) {
      const tokens = splitLegendTokens(cell.text);
      if (!tokens.length || !cell.color) continue;
      for (const token of tokens) {
        addColor(result.validColorsByValue, token, cell.color);
        if (/geplant/i.test(desc))
          addColor(result.plannedColorsByValue, token, cell.color);
      }
      parts.push(`${tokens.join('/')}=${cell.color}`);
    }
    if (parts.length) summaries.push(`${parts.join(' ')} (${desc})`);
  }
  if (summaries.length)
    result.summary = `Legend (page top): ${summaries.join('; ')}`;
  return result;
}

function validateOfficeCell(cell, legend, label) {
  const text = cell.text.trim().toUpperCase();
  if (!text || !legend.validColorsByValue.has(text)) return '';
  const valid = legend.validColorsByValue.get(text);
  if (!cell.color)
    return `! ${label}: ${text} has no color; legend expects ${[...valid].join('/')}`;
  if (!valid.has(cell.color))
    return `! ${label}: ${text}/${cell.color} not in page legend (${[...valid].join('/')})`;
  return '';
}

function parseOffice(body, accountId) {
  const today = new Date();
  const months = [
    'Januar',
    'Februar',
    'März',
    'April',
    'Mai',
    'Juni',
    'Juli',
    'August',
    'September',
    'Oktober',
    'November',
    'Dezember',
  ];
  const label = `${months[today.getMonth()]} ${today.getFullYear()}`;
  const legend = parseOfficeLegend(body, label);
  const start = body.indexOf(label);
  if (start < 0)
    return [`! Current month section not found: ${label}`, legend.summary];
  const sub = body.slice(start);
  const tableMatch = sub.match(/<table\b[\s\S]*?<\/table>/i);
  if (!tableMatch) return ['! Current month table not found', legend.summary];
  const rows = tableMatch[0].match(/<tr\b[\s\S]*?<\/tr>/gi) || [];
  const row = rows.find((r) => r.includes(accountId));
  if (!row)
    return ['! Your row not found in current month table', legend.summary];
  const cells = extractCells(row);
  const dayVal = (day) => cells[day + 1] || { text: '', color: '' }; // dept + name then day 1
  const day = today.getDate();
  const current = dayVal(day);
  const out = [];
  const legendWarnings = [];
  out.push(
    `Today ${day}: ${current.text || '<blank>'} (${current.color || 'no color'})`,
  );
  const todayWarning = validateOfficeCell(current, legend, `today ${day}`);
  if (todayWarning) legendWarnings.push(todayWarning);
  const future = [];
  const missing = [];
  for (let n = 1; n < 15; n++) {
    const d = new Date(today);
    d.setDate(today.getDate() + n);
    if (d.getMonth() !== today.getMonth()) continue;
    const dow = d.getDay();
    if (dow === 0 || dow === 6) continue;
    const v = dayVal(d.getDate());
    future.push(`${d.getDate()}:${v.text || '_'}/${v.color || 'no-color'}`);
    if (!v.text) missing.push(String(d.getDate()));
    const warning = validateOfficeCell(v, legend, `future ${d.getDate()}`);
    if (warning) legendWarnings.push(warning);
  }
  out.push(`Next workdays: ${future.join(', ')}`);
  if (missing.length)
    out.push(`! Future workdays missing planning: ${missing.join(', ')}`);
  out.push(legend.summary);
  if (legendWarnings.length) out.push(...legendWarnings);
  else out.push('Legend check: ✓ current/future entries match page legend');
  return out;
}

function rdKeysFrom(text) {
  return [...new Set(text.match(/RD-[0-9]+/g) || [])];
}

async function main() {
  line('# isp-start-day eval');
  line('');
  line(`Date: ${new Date().toISOString().slice(0, 10)}`);
  line('');

  const tools = {};
  line('## Tool health');
  for (const c of ['node', 'acli', 'gh', 'git']) {
    tools[c] = c === 'node' ? true : await has(c);
    tools[c] ? ok(c) : fail(`${c} missing`);
  }
  let setup = !tools.acli || !tools.gh || !tools.git;
  if (tools.acli) {
    const ja = await run('acli', ['jira', 'auth', 'status']);
    ja.ok ? ok('Jira auth') : (fail('Jira auth'), (setup = true));
    const ca = await run('acli', ['confluence', 'auth', 'status']);
    ca.ok ? ok('Confluence auth') : (fail('Confluence auth'), (setup = true));
  }
  if (tools.gh) {
    const ga = await run('gh', ['auth', 'status']);
    ga.ok ? ok('GitHub auth') : (fail('GitHub auth'), (setup = true));
  }
  if (setup) setupGuide();
  line('');

  const seenKeys = new Set();

  line('## Bürobelegung');
  if (tools.acli) {
    const page = await run('acli', [
      'confluence',
      'page',
      'view',
      '--id',
      OFFICE_PAGE_ID,
      '--json',
      '--body-format',
      'storage',
    ]);
    if (page.ok) {
      ok(`Confluence office page ${OFFICE_PAGE_ID} reachable`);
      let accountId = process.env.ISP_JIRA_ACCOUNT_ID || '';
      if (!accountId) {
        const meIssue = await run('acli', [
          'jira',
          'workitem',
          'search',
          '--jql',
          'assignee = currentUser() ORDER BY updated DESC',
          '--fields',
          'key,assignee',
          '--limit',
          '1',
          '--json',
        ]);
        const data = jsonParse(meIssue.stdout);
        accountId = data?.[0]?.fields?.assignee?.accountId || '';
      }
      const data = jsonParse(page.stdout);
      const body = data?.body?.storage?.value || '';
      if (accountId && body) parseOffice(body, accountId).forEach(line);
      else
        warn(
          'Cannot parse your Bürobelegung row; set ISP_JIRA_ACCOUNT_ID if assignee lookup fails',
        );
    } else {
      fail(`Confluence office page ${OFFICE_PAGE_ID} unreachable`);
      line(
        `  ${page.stderr.split('\n')[0] || page.stdout.split('\n')[0] || 'unknown error'}`,
      );
    }
  } else warn('Skipped: acli missing');
  line('');

  line('## Jira: my current sprint');
  if (tools.acli) {
    const sprint = await run('acli', [
      'jira',
      'workitem',
      'search',
      '--jql',
      'assignee = currentUser() AND sprint in openSprints() ORDER BY status, priority DESC',
      '--fields',
      'key,summary,status,priority',
      '--limit',
      '100',
      '--json',
    ]);
    const data = sprint.ok ? jsonParse(sprint.stdout) : null;
    if (Array.isArray(data)) {
      line(`tickets: ${data.length}`);
      const counts = new Map();
      for (const item of data)
        counts.set(
          item.fields.status.name,
          (counts.get(item.fields.status.name) || 0) + 1,
        );
      line('status counts:');
      [...counts.entries()]
        .sort((a, b) => b[1] - a[1])
        .forEach(([k, v]) => line(`  ${v} ${k}`));
      line('items:');
      data.forEach((i) => {
        seenKeys.add(i.key);
        line(`  ${i.key} [${i.fields.status.name}] ${i.fields.summary}`);
      });
    } else fail('Jira sprint query failed');
  } else warn('Skipped: acli missing');
  line('');

  line('## KKG PRs');
  let prs = [];
  if (tools.gh && KKG_ROOT && existsSync(path.join(KKG_ROOT, 'trunk'))) {
    const pr = await run(
      'gh',
      [
        'pr',
        'list',
        '--state',
        'open',
        '--limit',
        '100',
        '--json',
        'number,title,headRefName,author,reviewDecision,url',
      ],
      { cwd: path.join(KKG_ROOT, 'trunk') },
    );
    prs = pr.ok ? jsonParse(pr.stdout) || [] : [];
    if (prs.length) {
      const me = (
        await run('gh', ['api', 'user', '-q', '.login'])
      ).stdout.trim();
      line('my PRs:');
      prs
        .filter((p) => p.author.login === me)
        .forEach((p) =>
          line(
            `  #${p.number} ${p.headRefName} ${p.reviewDecision || ''} ${p.title}`,
          ),
        );
      line('team PRs:');
      prs
        .filter((p) => p.author.login !== me)
        .forEach((p) =>
          line(
            `  #${p.number} ${p.headRefName} ${p.reviewDecision || ''} ${p.title}`,
          ),
        );
      prs
        .flatMap((p) => rdKeysFrom(`${p.headRefName} ${p.title}`))
        .forEach((k) => seenKeys.add(k));
    } else fail('gh pr list failed or no open PRs');
  } else
    warn(
      KKG_ROOT
        ? `Skipped: needs gh and ${KKG_ROOT}/trunk`
        : 'Skipped KKG PRs: set ISP_KKG_ROOT to your kkg checkout',
    );
  line('');

  line('## KKG worktrees/branches');
  if (tools.git && KKG_ROOT && existsSync(path.join(KKG_ROOT, '.bare'))) {
    const wt = await run('git', [
      `--git-dir=${path.join(KKG_ROOT, '.bare')}`,
      'worktree',
      'list',
      '--porcelain',
    ]);
    const entries = [];
    let cur = null;
    for (const l of wt.stdout.split(/\r?\n/)) {
      if (l.startsWith('worktree ')) cur = { worktree: l.slice(9) };
      if (l.startsWith('branch ') && cur) {
        cur.branch = l
          .replace(/^branch refs\/heads\//, '')
          .slice('branch '.length);
        entries.push(cur);
        cur = null;
      }
    }
    for (const e of entries) {
      if (path.basename(e.worktree) === '.bare') continue;
      const dirty = (
        await run('git', ['-C', e.worktree, 'status', '--porcelain'])
      ).stdout
        .split(/\r?\n/)
        .filter(Boolean).length;
      const up = (
        await run('git', [
          '-C',
          e.worktree,
          'for-each-ref',
          '--format=%(upstream:short)',
          `refs/heads/${e.branch}`,
        ])
      ).stdout.trim();
      let ahead = '-',
        behind = '-';
      if (up) {
        const ab = (
          await run('git', [
            '-C',
            e.worktree,
            'rev-list',
            '--left-right',
            '--count',
            `HEAD...${up}`,
          ])
        ).stdout
          .trim()
          .split(/\s+/);
        [ahead, behind] = ab.length === 2 ? ab : ['?', '?'];
      }
      const last = (
        await run('git', ['-C', e.worktree, 'log', '-1', '--format=%cs %h %s'])
      ).stdout.trim();
      rdKeysFrom(`${e.branch} ${last}`).forEach((k) => seenKeys.add(k));
      line(
        `  ${e.branch} | dirty=${dirty} | ahead=${ahead} behind=${behind} | ${last}`,
      );
    }
  } else
    warn(
      KKG_ROOT
        ? `Skipped: needs git and ${KKG_ROOT}/.bare`
        : 'Skipped KKG worktrees: set ISP_KKG_ROOT to your kkg checkout',
    );
  line('');

  line('## Cross-check: RD keys seen in PRs/branches');
  if (tools.acli && seenKeys.size) {
    const keys = [...seenKeys].sort().join(',');
    const q = await run('acli', [
      'jira',
      'workitem',
      'search',
      '--jql',
      `key in (${keys}) ORDER BY key`,
      '--fields',
      'key,summary,status,assignee',
      '--limit',
      '200',
      '--json',
    ]);
    const data = q.ok ? jsonParse(q.stdout) : null;
    if (Array.isArray(data))
      data.forEach((i) =>
        line(`  ${i.key} [${i.fields.status.name}] ${i.fields.summary}`),
      );
    else fail('RD key status query failed');
  } else warn('Skipped: no RD keys or acli missing');
}

main().catch((err) => {
  fail(err?.message || String(err));
  process.exitCode = 1;
});
