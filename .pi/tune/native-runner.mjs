import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join, resolve } from "node:path";

const tune = resolve(import.meta.dirname);
const queue = join(tune, "queue");
mkdirSync(queue, { recursive: true });
writeFileSync(join(tune, "native-runner.pid"), String(process.pid));

while (true) {
  const requestPath = join(queue, "request.json");
  if (!existsSync(requestPath)) {
    await new Promise(resolveWait => setTimeout(resolveWait, 100));
    continue;
  }
  let request;
  try {
    request = JSON.parse(readFileSync(requestPath, "utf8"));
    rmSync(requestPath);
  } catch {
    await new Promise(resolveWait => setTimeout(resolveWait, 100));
    continue;
  }
  const result = spawnSync(process.execPath, [join(tune, "benchmark-native.mjs")], {
    cwd: resolve(tune, "../.."),
    encoding: "utf8",
    timeout: request.model.includes("5.6-sol") ? 1_300_000 : 900_000,
    maxBuffer: 100 * 1024 * 1024,
    env: { ...process.env, FXDRIVER_TUNE_MODEL: request.model, FXDRIVER_TUNE_RUN: request.runName, FXDRIVER_TUNE_SCENARIO: request.scenario },
  });
  writeFileSync(join(queue, `${request.id}.json`), JSON.stringify({ status: result.status, stdout: result.stdout, stderr: result.stderr, error: result.error?.message }));
}
