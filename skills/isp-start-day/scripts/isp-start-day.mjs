#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { promisify } from 'node:util';

const execFileP = promisify(execFile);
// kkg checkout location is per-developer, not standardized — no default path.
// Set ISP_KKG_ROOT to your kkg root (the dir holding `.bare` and `trunk`).
const KKG_ROOT = process.env.ISP_KKG_ROOT || null;
const OFFICE_PAGE_ID = process.env.ISP_OFFICE_PAGE_ID || '166494212';

export function parseDateArg(args) {
  const index = args.indexOf('--date');
  if (index < 0) return new Date();

  const value = args[index + 1] || '';
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) throw new Error(`Invalid date: ${value}`);

  const [, year, month, day] = match.map(Number);
  const date = new Date(year, month - 1, day, 12);
  if (
    date.getFullYear() !== year ||
    date.getMonth() !== month - 1 ||
    date.getDate() !== day
  )
    throw new Error(`Invalid date: ${value}`);
  return date;
}

function formatDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

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

export function parseOffice(body, accountId, today) {
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
  const lastDay = new Date(
    today.getFullYear(),
    today.getMonth() + 1,
    0,
  ).getDate();
  for (let dayNumber = day + 1; dayNumber <= lastDay; dayNumber++) {
    const date = new Date(today.getFullYear(), today.getMonth(), dayNumber, 12);
    const dow = date.getDay();
    if (dow === 0 || dow === 6) continue;
    const value = dayVal(dayNumber);
    future.push(
      `${dayNumber}:${value.text || '_'}/${value.color || 'no-color'}`,
    );
    if (!value.text) missing.push(dayNumber);
    const warning = validateOfficeCell(value, legend, `future ${dayNumber}`);
    if (warning) legendWarnings.push(warning);
  }
  out.push(`Next workdays: ${future.join(', ')}`);
  if (missing.length) {
    out.push(`! Future workdays missing planning: ${missing.join(', ')}`);
    const proposals = missing.map((dayNumber) => {
      const sourceDay = dayNumber - 7;
      const source = dayVal(sourceDay).text.trim().toUpperCase();
      return source && legend.validColorsByValue.has(source)
        ? `${dayNumber}=${source} (from ${sourceDay})`
        : `${dayNumber}=?`;
    });
    out.push(`Proposed update: ${proposals.join(', ')}`);
    out.push(
      'Confirm or correct each value (B/BP/H/A/AP); Confluence unchanged.',
    );
  }
  out.push(legend.summary);
  if (legendWarnings.length) out.push(...legendWarnings);
  else out.push('Legend check: ✓ current/future entries match page legend');
  return out;
}

function rdKeysFrom(text) {
  return [...new Set(text.match(/RD-[0-9]+/g) || [])];
}

function previousWorkdayStart(today) {
  const date = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  do date.setDate(date.getDate() - 1);
  while (date.getDay() === 0 || date.getDay() === 6);
  return date;
}

export function jiraReviewFindings(tickets, prs, today) {
  const result = { action: [], waiting: [] };
  const cutoff = previousWorkdayStart(today);
  for (const ticket of tickets) {
    if (ticket.fields.status.name !== 'Ready to Sync') continue;
    const pullRequest = prs.find((candidate) =>
      rdKeysFrom(`${candidate.headRefName} ${candidate.title}`).includes(
        ticket.key,
      ),
    );
    if (!pullRequest) {
      result.action.push(`! ${ticket.key} — Ready to Sync but no open PR`);
      continue;
    }
    if (pullRequest.reviewDecision === 'CHANGES_REQUESTED') {
      result.action.push(
        `! ${ticket.key} — PR #${pullRequest.number} changes requested`,
      );
      continue;
    }
    if (pullRequest.reviewDecision === 'APPROVED') {
      result.waiting.push(
        `• ${ticket.key} — PR #${pullRequest.number} approved`,
      );
      continue;
    }
    const updated = new Date(pullRequest.updatedAt);
    if (updated < cutoff) {
      result.action.push(
        `! ${ticket.key} — PR #${pullRequest.number} stale since ${updated.toISOString().slice(0, 10)}`,
      );
      continue;
    }
    result.waiting.push(
      `• ${ticket.key} — PR #${pullRequest.number} waiting for review; active ${updated.toISOString().slice(0, 10)}`,
    );
  }
  return result;
}

async function main(today) {
  line('# isp-start-day eval');
  line('');
  line(`Date: ${formatDate(today)}`);
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
    if (ja.ok) ok('Jira auth');
    else {
      fail('Jira auth');
      setup = true;
    }
    const ca = await run('acli', ['confluence', 'auth', 'status']);
    if (ca.ok) ok('Confluence auth');
    else {
      fail('Confluence auth');
      setup = true;
    }
  }
  if (tools.gh) {
    const ga = await run('gh', ['auth', 'status']);
    if (ga.ok) ok('GitHub auth');
    else {
      fail('GitHub auth');
      setup = true;
    }
  }
  if (setup) setupGuide();
  line('');

  const seenKeys = new Set();
  let sprintTickets = [];

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
      if (accountId && body) parseOffice(body, accountId, today).forEach(line);
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
      sprintTickets = data;
      data.forEach((item) => {
        seenKeys.add(item.key);
      });
      for (const [heading, status] of [
        ['In progress', 'In Progress'],
        ['To do', 'To Do'],
      ]) {
        const tickets = data.filter(
          (item) => item.fields.status.name === status,
        );
        if (!tickets.length) continue;
        line(`${heading}:`);
        tickets.forEach((item) => {
          line(
            `  • ${item.key} [${item.fields.priority?.name || 'no priority'}] ${item.fields.summary}`,
          );
        });
      }
      if (data.some((item) => item.fields.status.name === 'Ready to Sync'))
        line('Review queue: cross-checked with open PRs below.');
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
        'number,title,headRefName,author,reviewDecision,updatedAt,url',
      ],
      { cwd: path.join(KKG_ROOT, 'trunk') },
    );
    prs = pr.ok ? jsonParse(pr.stdout) || [] : [];
    if (pr.ok) {
      if (prs.length) {
        const me = (
          await run('gh', ['api', 'user', '-q', '.login'])
        ).stdout.trim();
        line('my PRs:');
        prs
          .filter((p) => p.author.login === me)
          .forEach((p) => {
            line(
              `  #${p.number} ${p.headRefName} ${p.reviewDecision || ''} ${p.title}`,
            );
          });
        line('team PRs:');
        prs
          .filter((p) => p.author.login !== me)
          .forEach((p) => {
            line(
              `  #${p.number} ${p.headRefName} ${p.reviewDecision || ''} ${p.title}`,
            );
          });
      } else line('No open PRs.');
      prs
        .flatMap((p) => rdKeysFrom(`${p.headRefName} ${p.title}`))
        .forEach((k) => {
          seenKeys.add(k);
        });
      const findings = jiraReviewFindings(sprintTickets, prs, today);
      if (findings.action.length) {
        line('Action:');
        findings.action.forEach(line);
      }
      if (findings.waiting.length) {
        line('Review state:');
        findings.waiting.forEach(line);
      }
    } else fail('gh pr list failed');
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
        cur.branch = l.replace(/^branch refs\/heads\//, '');
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
      rdKeysFrom(`${e.branch} ${last}`).forEach((k) => {
        seenKeys.add(k);
      });
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
      data.forEach((i) => {
        line(`  ${i.key} [${i.fields.status.name}] ${i.fields.summary}`);
      });
    else fail('RD key status query failed');
  } else warn('Skipped: no RD keys or acli missing');
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
)
  main(parseDateArg(process.argv.slice(2))).catch((err) => {
    fail(err?.message || String(err));
    process.exitCode = 1;
  });
