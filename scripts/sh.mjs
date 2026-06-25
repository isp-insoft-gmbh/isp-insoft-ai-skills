#!/usr/bin/env node
// Portable command runner for skill mise tasks.
//
// mise runs task bodies through `cmd /c` on Windows, where unix wrappers like
// ./mvnw fail. This normalizes them (./mvnw -> .\mvnw.cmd) so a single skill
// mise.toml works on every OS. Trust: the command comes from a skill's own
// committed mise.toml — same trust level as the skill's source.
import { execSync } from 'node:child_process';

const parts = process.argv.slice(2);
if (!parts.length) {
  process.stderr.write('sh.mjs: no command given\n');
  process.exit(2);
}
let cmd = parts.join(' ');
if (process.platform === 'win32')
  cmd = cmd.replace(/\.\/(\w+)/g, (_, w) => `.\\${w}.cmd`);
try {
  execSync(cmd, { stdio: 'inherit', shell: true });
} catch (e) {
  process.exit(e.status ?? 1);
}
