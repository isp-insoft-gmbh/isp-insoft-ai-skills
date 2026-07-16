import assert from 'node:assert/strict';
import test from 'node:test';

import {
  jiraReviewFindings,
  parseDateArg,
  parseOffice,
} from './isp-start-day.mjs';

test('parses an explicit report date', () => {
  const date = parseDateArg(['--date', '2026-07-17']);

  assert.deepEqual(
    [date.getFullYear(), date.getMonth(), date.getDate()],
    [2026, 6, 17],
  );
});

test('rejects an invalid report date', () => {
  assert.throws(
    () => parseDateArg(['--date', '2026-02-30']),
    /Invalid date: 2026-02-30/,
  );
});

function officeBody(accountId, values) {
  const cells = Array.from({ length: 31 }, (_, index) => {
    const value = values[index + 1];
    return value
      ? `<td data-highlight-colour="#fff0b3">${value}</td>`
      : '<td></td>';
  }).join('');
  return `
    <table>
      <tr><td data-highlight-colour="#fff0b3">A</td><td>Abwesend</td></tr>
    </table>
    <h2>Juli 2026</h2>
    <table><tr><td>R&amp;D</td><td>${accountId}</td>${cells}</tr></table>
  `;
}

test('checks through month-end and proposes missing office values', () => {
  const values = Object.fromEntries(
    [15, 16, 17, 20, 21, 22, 23, 24, 27, 28, 29].map((day) => [day, 'A']),
  );
  const output = parseOffice(
    officeBody('account-1', values),
    'account-1',
    new Date(2026, 6, 15, 12),
  ).join('\n');

  assert.match(output, /30:_\/no-color/);
  assert.match(output, /31:_\/no-color/);
  assert.match(output, /Future workdays missing planning: 30, 31/);
  assert.match(output, /Proposed update: 30=A \(from 23\), 31=A \(from 24\)/);
  assert.match(output, /Confirm or correct each value \(B\/BP\/H\/A\/AP\)/);
});

const readyTicket = {
  key: 'RD-1',
  fields: { status: { name: 'Ready to Sync' }, summary: 'Review me' },
};
const friday = new Date(2026, 6, 17, 12);

function pr(overrides = {}) {
  return {
    number: 42,
    title: 'RD-1 Review me',
    headRefName: 'feature/RD-1',
    reviewDecision: '',
    updatedAt: '2026-07-16T12:00:00Z',
    ...overrides,
  };
}

test('flags Ready to Sync without an open PR', () => {
  const result = jiraReviewFindings([readyTicket], [], friday);

  assert.match(result.action.join('\n'), /RD-1.*no open PR/);
  assert.equal(result.waiting.length, 0);
});

test('flags requested review changes', () => {
  const result = jiraReviewFindings(
    [readyTicket],
    [pr({ reviewDecision: 'CHANGES_REQUESTED' })],
    friday,
  );

  assert.match(result.action.join('\n'), /PR #42.*changes requested/);
});

test('flags review activity older than one workday', () => {
  const result = jiraReviewFindings(
    [readyTicket],
    [pr({ updatedAt: '2026-07-15T12:00:00Z' })],
    friday,
  );

  assert.match(result.action.join('\n'), /PR #42.*stale since 2026-07-15/);
});

test('keeps recently active review work waiting', () => {
  const result = jiraReviewFindings([readyTicket], [pr()], friday);

  assert.equal(result.action.length, 0);
  assert.match(result.waiting.join('\n'), /RD-1.*PR #42.*waiting for review/);
});

test('does not call an approved PR stale', () => {
  const result = jiraReviewFindings(
    [readyTicket],
    [pr({ reviewDecision: 'APPROVED', updatedAt: '2026-07-14T12:00:00Z' })],
    friday,
  );

  assert.equal(result.action.length, 0);
  assert.match(result.waiting.join('\n'), /RD-1.*PR #42.*approved/);
});

test('does not apply review findings to other Jira states', () => {
  const ticket = {
    ...readyTicket,
    fields: { ...readyTicket.fields, status: { name: 'In Progress' } },
  };
  const result = jiraReviewFindings([ticket], [], friday);

  assert.deepEqual(result, { action: [], waiting: [] });
});
