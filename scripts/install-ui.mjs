import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createInterface } from 'node:readline/promises';
import { bold, descriptionLines } from './output.mjs';

export function statePath(
  platform = process.platform,
  env = process.env,
  home = os.homedir(),
) {
  const root =
    platform === 'win32'
      ? env.LOCALAPPDATA || path.join(home, 'AppData', 'Local')
      : platform === 'darwin'
        ? path.join(home, 'Library', 'Application Support')
        : env.XDG_STATE_HOME || path.join(home, '.local', 'state');
  return path.join(root, 'isp-insoft-ai-skills', 'state.json');
}

export function readHistory() {
  try {
    const state = JSON.parse(fs.readFileSync(statePath(), 'utf8'));
    if (
      !Array.isArray(state.recentProjectTargets) ||
      state.recentProjectTargets.some(
        (target) => typeof target !== 'string' || !path.isAbsolute(target),
      )
    )
      throw new Error('expected absolute project target paths');
    return [...new Set(state.recentProjectTargets)];
  } catch (error) {
    if (error.code !== 'ENOENT')
      console.error(`warning: cannot read project history: ${error.message}`);
    return [];
  }
}

export function rememberProject(target) {
  const history = readHistory();
  const equal = (a, b) =>
    process.platform === 'win32'
      ? a.toLowerCase() === b.toLowerCase()
      : a === b;
  const recentProjectTargets = [
    target,
    ...history.filter((previous) => !equal(previous, target)),
  ];
  try {
    fs.mkdirSync(path.dirname(statePath()), { recursive: true });
    fs.writeFileSync(
      statePath(),
      `${JSON.stringify({ recentProjectTargets }, null, 2)}\n`,
    );
  } catch (error) {
    console.error(`warning: cannot save project history: ${error.message}`);
  }
}

// A blank answer or q cancels every picker without writing anything.
export function createPrompt() {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const abort = new AbortController();
  rl.on('SIGINT', () => {
    abort.abort();
    rl.close();
  });
  rl.on('close', () => abort.abort());
  return {
    async ask(question) {
      try {
        const answer = (
          await rl.question(question, { signal: abort.signal })
        ).trim();
        return !answer || answer.toLowerCase() === 'q' ? null : answer;
      } catch (error) {
        if (error.name === 'AbortError') return null;
        throw error;
      }
    },
    close: () => rl.close(),
  };
}

export async function chooseDestination(ask, history, harnesses) {
  const available = history.map((target) => {
    try {
      return fs.statSync(target).isDirectory();
    } catch {
      return false;
    }
  });
  const names = Object.keys(harnesses);
  console.log(bold('Destination:'));
  names.forEach((name, index) => {
    console.log(`  ${index + 1}. ${bold(name)} (global): ${harnesses[name]}`);
  });
  history.forEach((target, index) => {
    console.log(
      `  ${names.length + index + 1}. ${target}${available[index] ? '' : ' [unavailable]'}`,
    );
  });
  const newIndex = names.length + history.length + 1;
  console.log(`  ${newIndex}. New project directory…`);
  while (true) {
    const answer = await ask('Destination number (q to cancel): ');
    if (answer === null) return null;
    const index = /^\d+$/.test(answer) ? Number(answer) : 0;
    if (index >= 1 && index <= names.length)
      return { harness: names[index - 1] };
    const historyIndex = index - names.length - 1;
    if (historyIndex >= 0 && historyIndex < history.length) {
      if (available[historyIndex]) return { target: history[historyIndex] };
      console.log('Directory unavailable; choose another destination.');
    } else if (index === newIndex) {
      const target = await ask('Project skills directory (q to cancel): ');
      return target === null ? null : { target: path.resolve(target) };
    } else console.log('Choose a listed destination number.');
  }
}

export async function chooseSkills(ask, units, installed) {
  console.log(bold('Skills'));
  console.log('Unselected existing skills are preserved.');
  units.forEach((unit, index) => {
    console.log(
      `  ${index + 1}. ${bold(unit.name)}${installed.has(unit.name) ? ' [installed]' : ''}`,
    );
    if (unit.description)
      console.log(descriptionLines(unit.description, '     '));
  });
  while (true) {
    const answer = await ask(
      'Skill numbers, separated by commas (q to cancel): ',
    );
    if (answer === null) return null;
    const values = answer.split(',').map((value) => value.trim());
    if (
      values.every(
        (value) =>
          /^\d+$/.test(value) &&
          Number(value) >= 1 &&
          Number(value) <= units.length,
      )
    )
      return [...new Set(values.map((value) => units[Number(value) - 1].name))];
    console.log('Choose one or more listed skill numbers.');
  }
}
