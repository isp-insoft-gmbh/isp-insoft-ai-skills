#!/usr/bin/env node
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

// RFC 4122 well-known namespaces for deterministic UUID3/UUID5 generation.
const NS = {
  dns: '6ba7b810-9dad-11d1-80b4-00c04fd430c8',
  url: '6ba7b811-9dad-11d1-80b4-00c04fd430c8',
  oid: '6ba7b812-9dad-11d1-80b4-00c04fd430c8',
  x500: '6ba7b814-9dad-11d1-80b4-00c04fd430c8',
};
function arg(name, def) {
  const i = process.argv.indexOf(name);
  return i === -1 ? def : process.argv[i + 1];
}
function bytesToUuid(b) {
  return [...b]
    .map((x) => x.toString(16).padStart(2, '0'))
    .join('')
    .replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, '$1-$2-$3-$4-$5');
}
function uuidToBytes(s) {
  return Buffer.from(s.replace(/^urn:uuid:/, '').replaceAll('-', ''), 'hex');
}
function v4() {
  const b = randomBytes(16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  return bytesToUuid(b);
}
function v1() {
  const b = randomBytes(16);
  const t = BigInt(Date.now()) * 10000n + 0x01b21dd213814000n;
  b.writeUInt32BE(Number(t & 0xffffffffn), 0);
  b.writeUInt16BE(Number((t >> 32n) & 0xffffn), 4);
  b.writeUInt16BE(Number((t >> 48n) & 0x0fffn) | 0x1000, 6);
  b[8] = (b[8] & 0x3f) | 0x80;
  return bytesToUuid(b);
}
function v5(namespace, name) {
  if (!namespace || !name)
    throw new Error('UUID5 requires --namespace and --name');
  if (!NS[namespace]) throw new Error(`Invalid namespace: ${namespace}`);
  const h = createHash('sha1')
    .update(uuidToBytes(NS[namespace]))
    .update(name)
    .digest();
  const b = Buffer.from(h.subarray(0, 16));
  b[6] = (b[6] & 0x0f) | 0x50;
  b[8] = (b[8] & 0x3f) | 0x80;
  return bytesToUuid(b);
}
function format(u, f) {
  if (f === 'hyphenated') return u;
  if (f === 'compact') return u.replaceAll('-', '');
  if (f === 'urn') return `urn:uuid:${u}`;
  if (f === 'uppercase') return u.toUpperCase();
  throw new Error(`Invalid format: ${f}`);
}
function gen(version, namespace, name, fmt) {
  if (version === 1) return format(v1(), fmt);
  if (version === 4) return format(v4(), fmt);
  if (version === 5) return format(v5(namespace, name), fmt);
  throw new Error(`Invalid version: ${version}`);
}
function valid(s) {
  return /^[0-9a-f]{8}-?[0-9a-f]{4}-?[1-5][0-9a-f]{3}-?[89ab][0-9a-f]{3}-?[0-9a-f]{12}$/i.test(
    s.replace(/^urn:uuid:/, ''),
  );
}
function csv(rows) {
  return `uuid\n${rows.join('\n')}`;
}
function main() {
  const validate = arg('--validate');
  if (validate) {
    const ok = valid(validate);
    console.log(`${validate}: ${ok ? 'valid' : 'invalid'}`);
    return ok ? 0 : 1;
  }
  const version = Number(arg('--version', '4'));
  const count = Number(arg('--count', '1'));
  const fmt = arg('--format', 'hyphenated');
  const namespace = arg('--namespace');
  const name = arg('--name');
  const output = arg('--output');
  const exp = arg('--export');
  const rows = Array.from({ length: count }, () =>
    gen(version, namespace, name, fmt),
  );
  let text = rows.join('\n');
  if (exp === 'json') text = JSON.stringify(rows, null, 2);
  else if (exp === 'csv') text = csv(rows);
  if (output) {
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, text + (text.endsWith('\n') ? '' : '\n'));
    console.log(`Generated ${count} UUIDs -> ${output}`);
  } else console.log(text);
  return 0;
}
try {
  process.exit(main());
} catch (e) {
  console.error(e.message);
  process.exit(1);
}
