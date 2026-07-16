import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join, resolve } from "node:path";
import { performance } from "node:perf_hooks";

const root = resolve(import.meta.dirname, "../..");
if (process.platform !== "win32") throw new Error("benchmark-native.mjs requires Windows Node");

const fx = join(root, "skills/fxdriver");
const work = join(root, ".pi/tune/work");
const model = process.env.FXDRIVER_TUNE_MODEL ?? "gpt-5.5:high";
const runName = process.env.FXDRIVER_TUNE_RUN ?? `run-${Date.now()}`;
const runDir = join(work, runName);
const scenarioName = process.env.FXDRIVER_TUNE_SCENARIO ?? "hamilton";
const isData = scenarioName === "data";
const expectedState = isData
  ? { filter: "Borealis", view: "Active only", selectedProject: "Borealis", tableValue: "Borealis", selectedTab: "Hierarchy", finalStatus: "Archived project" }
  : scenarioName === "johnson"
    ? { name: "Katherine Johnson", theme: "Light", density: "Spacious", reviewDate: "2027-01-15", retentionDays: 60, zoom: 150, notifications: false, expertMode: true, beta: true, finalStatus: "Submitted Katherine Johnson", retentionSteps: "increment 35 then decrement 5" }
    : { name: "Margaret Hamilton", theme: "Dark", density: "Compact", reviewDate: "2026-12-24", retentionDays: 45, zoom: 135, notifications: false, expertMode: true, beta: true, finalStatus: "Submitted Margaret Hamilton", retentionSteps: "increment 20 then decrement 5" };
const javaInstalls = join(process.env.USERPROFILE, "AppData", "Local", "mise", "installs", "java");
const javaHome = join(javaInstalls, readdirSync(javaInstalls).find(name => name.startsWith("temurin-26.") && name.includes("+")));
const java = join(javaHome, "bin", "java.exe");
const jar = join(javaHome, "bin", "jar.exe");
const piCli = join(process.env.APPDATA, "npm", "node_modules", "@earendil-works", "pi-coding-agent", "dist", "cli.js");

rmSync(runDir, { recursive: true, force: true });
mkdirSync(join(runDir, "lib"), { recursive: true });

run("node", ["../../scripts/sh.mjs", "./mvnw", "-q", "-Djavafx.version=26.0.1", "-DskipTests", "test-compile", "package"], fx, { JAVA_HOME: javaHome, PATH: `${join(javaHome, "bin")};${process.env.PATH}` });
const testClasses = join(fx, "target/test-classes");
const appClasses = isData
  ? ["fxdriver/FxDriverDataApp.class", "fxdriver/FxDriverDataApp$1.class", "fxdriver/FxDriverDataApp$Project.class"]
  : ["fxdriver/FxDriverFormsApp.class"];
run(jar, ["--create", "--file", join(runDir, "app.jar"), "--main-class", isData ? "fxdriver.FxDriverDataApp" : "fxdriver.FxDriverFormsApp", ...appClasses.flatMap(file => ["-C", testClasses, file]), "-C", testClasses, "fxdriver/fixtures.css"], root);
copyFileSync(join(fx, "target/fxdriver.jar"), join(runDir, "fxdriver.jar"));
for (const module of ["javafx-base", "javafx-graphics", "javafx-controls"]) {
  const source = join(process.env.USERPROFILE, ".m2", "repository", "org", "openjfx", module, "26.0.1", `${module}-26.0.1-win.jar`);
  copyFileSync(source, join(runDir, "lib", `${module}.jar`));
}

const launch = `${slash(java)} -Dglass.platform=Headless -Djava.awt.headless=true -Dprism.order=sw --module-path ./lib --add-modules javafx.controls -jar ./app.jar`;
const workflow = isData
  ? `2. Exercise query/highlight plus batch or run. Inspect recent-projects with listItems; select Borealis without activation; select project-table row 1 and verify its project-name cell is Borealis; inspect project-tree-table row 1. Set project-filter to Borealis and project-view to Active only. Fire Save Workspace, Delete, Export, and Archive menu items, asserting each status; leave final status Archived project. Expand/collapse project-details and select the Hierarchy tab.\n3. Wait/assert after changes. Save final-summary.json, final-snapshot.json, after.png, name-field.png (the project table), diff.png, image summaries, and events.json.\n4. Write report.json with verified fields: filter, view, selectedProject, tableValue, selectedTab, finalStatus.`
  : `2. Exercise query/highlight plus batch or run. Set profile-name to ${expectedState.name}; theme ${expectedState.theme}; density ${expectedState.density}; review date ${expectedState.reviewDate}; retention ${expectedState.retentionDays} using ${expectedState.retentionSteps}; zoom ${expectedState.zoom}; notifications off; expert mode on; Beta selected. Show and hide the density popup. Exercise literal key chars, CTRL+S, then ENTER so final status is ${expectedState.finalStatus}.\n3. Wait/assert after changes. Save final-summary.json, final-snapshot.json, after.png, name-field.png, diff.png, image summaries, and events.json.\n4. Write report.json with verified fields: name, theme, density, reviewDate, retentionDays, zoom, notifications, expertMode, beta, finalStatus.`;
const prompt = `Drive the packaged JavaFX ${isData ? "Project Browser" : "Forms"} app entirely through fxdriver. Work only in the current directory. Launch it headlessly with JavaFX 26 using:\n${slash(java)} -jar ./fxdriver.jar launch --quiet -- ${launch}\n\nComplete and verify this workflow:\n1. Save capabilities, an initial summary, full snapshot, and before.png. Enable visual trace and mark the run.\n${workflow}\n5. Shut down fxdriver and terminate the launched app PID.\nUse stable node IDs, absolute artifact paths where RPC requires them, and inspect screenshots rather than merely creating them.`;

const args = [
  "--mode", "json", "--no-session", "--model", model,
  "--no-extensions", "--no-skills", "--skill", join(fx, "target/fxdriver"),
  "--no-prompt-templates", "--no-context-files", "--approve", prompt,
];
const start = performance.now();
const result = spawnSync(process.execPath, [piCli, ...args], {
  cwd: runDir,
  encoding: "utf8",
  timeout: model.includes("5.6-sol") ? 1_200_000 : 600_000,
  maxBuffer: 100 * 1024 * 1024,
  env: { ...process.env, PI_SKIP_VERSION_CHECK: "1", PI_TELEMETRY: "0" },
});
const wallMs = Math.round(performance.now() - start);
writeFileSync(join(runDir, "session.jsonl"), result.stdout ?? "");
writeFileSync(join(runDir, "pi.stderr.txt"), result.stderr ?? "");
cleanupApps(runDir);

const events = parseJsonl(result.stdout ?? "");
const toolCalls = events.filter(e => e.type === "tool_execution_start").length;
const toolErrors = events.filter(e => e.type === "tool_execution_end" && e.isError).length;
const expected = [
  "capabilities.json", "initial-summary.json", "initial-snapshot.json", "before.png",
  "final-summary.json", "final-snapshot.json", "after.png", "name-field.png", "diff.png",
  "before-image.json", "after-image.json", "events.json", "report.json",
];
const paths = Object.fromEntries(expected.map(name => [name, artifactPath(runDir, name)]));
const missing = expected.filter(name => !paths[name]);
const invalid = [];
for (const name of expected.filter(name => name.endsWith(".json") && paths[name])) {
  try { JSON.parse(readFileSync(paths[name], "utf8")); } catch { invalid.push(name); }
}
for (const name of expected.filter(name => name.endsWith(".png") && paths[name])) {
  const bytes = readFileSync(paths[name]);
  if (bytes.length < 100 || bytes.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a") invalid.push(name);
}
const stateFailures = verifyState(paths, expectedState);
let rpcErrors = 0;
try {
  const eventDoc = JSON.parse(readFileSync(paths["events.json"], "utf8"));
  rpcErrors = (JSON.stringify(eventDoc).match(/\"ok\":false/g) ?? []).length;
} catch {}
const success = result.status === 0 && !result.error && missing.length === 0 && invalid.length === 0 && stateFailures.length === 0;
const correctnessFailures = missing.length + invalid.length + stateFailures.length;
const cleanupFailures = result.status === 0 && !result.error ? 0 : 1;
const score = correctnessFailures * 10_000 + cleanupFailures * 1_000 + toolErrors * 100 + rpcErrors * 100 + toolCalls;
const summary = { runName, model, scenario: scenarioName, exitCode: result.status, signal: result.signal, timedOut: result.error?.code === "ETIMEDOUT", wallMs, toolCalls, toolErrors, rpcErrors, missing, invalid, stateFailures, success, score };
writeFileSync(join(runDir, "metrics.json"), JSON.stringify(summary, null, 2));
console.log(`METRIC reliability_cost=${score}`);
console.log(`METRIC wall_ms=${wallMs}`);
console.log(`METRIC tool_calls=${toolCalls}`);
console.log(`METRIC tool_errors=${toolErrors}`);
console.log(`METRIC rpc_errors=${rpcErrors}`);
console.log(`METRIC missing_artifacts=${missing.length}`);
console.log(`METRIC state_failures=${stateFailures.length}`);
console.log(JSON.stringify(summary));
process.exit(success ? 0 : 1);

function run(command, args, cwd, env = {}) {
  const result = spawnSync(command, args, { cwd, encoding: "utf8", timeout: 300_000, maxBuffer: 50 * 1024 * 1024, env: { ...process.env, ...env } });
  if (result.status !== 0) {
    const detail = JSON.stringify({ command, args, cwd, status: result.status, error: result.error?.message, stdout: result.stdout, stderr: result.stderr }, null, 2);
    writeFileSync(join(root, ".pi/tune/setup-error.txt"), detail);
    throw new Error(detail);
  }
  return result;
}
function slash(value) { return value.replaceAll("\\", "/"); }
function parseJsonl(text) {
  const values = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    try { values.push(JSON.parse(line)); } catch {}
  }
  return values;
}
function artifactPath(dir, name) {
  const aliases = { "initial-snapshot.json": "full-snapshot.json", "before-image.json": "before-image-summary.json", "after-image.json": "after-image-summary.json" };
  for (const root of [dir, join(dir, "artifacts")]) {
    for (const candidate of [name, aliases[name]].filter(Boolean)) {
      const path = join(root, candidate);
      if (existsSync(path)) return path;
    }
  }
}
function verifyState(paths, expected) {
  const failures = [];
  let snapshot = "";
  try { snapshot = readFileSync(paths["final-snapshot.json"], "utf8"); } catch {}
  for (const [key, value] of Object.entries(expected)) {
    if (key !== "retentionSteps" && (typeof value === "string" || typeof value === "number") && !snapshot.includes(String(value))) failures.push(`snapshot:${value}`);
  }
  try {
    const report = JSON.parse(readFileSync(paths["report.json"], "utf8"));
    for (const [key, value] of Object.entries(expected).filter(([key]) => key !== "retentionSteps")) if (report[key] !== value) failures.push(`report:${key}`);
  } catch { failures.push("report:unreadable"); }
  return failures;
}
function cleanupApps(dir) {
  const endpoints = join(dir, "target", "fxdriver-endpoints");
  if (!existsSync(endpoints)) return;
  for (const file of readdirSync(endpoints)) {
    try {
      const { pid } = JSON.parse(readFileSync(join(endpoints, file), "utf8"));
      if (Number.isInteger(pid)) spawnSync("taskkill", ["/PID", String(pid), "/T", "/F"], { encoding: "utf8" });
    } catch {}
  }
}
