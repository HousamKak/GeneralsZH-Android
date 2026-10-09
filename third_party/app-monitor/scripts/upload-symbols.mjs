#!/usr/bin/env node
// Uploads an R8 mapping.txt (Android) or native symbol files (unstripped .so, zipped dSYM) for a release.
//   node scripts/upload-symbols.mjs --app zh-commander --version 1.4.2 --platform android --mapping app/build/outputs/mapping/release/mapping.txt
//   node scripts/upload-symbols.mjs --app zh-commander --version 1.4.2 --platform android --symbols path/to/libmain.so [more files...]
//   node scripts/upload-symbols.mjs --app zh-commander --version 1.4.2 --platform ios --symbols ZH.app.dSYM.zip
// Options:
//   --name <n>            store under this name (one file only; default: the file's base name, which must
//                         match the module name in crash frames, e.g. libmain.so)
//   --compress-debug <llvm-objcopy>   shrink DWARF first (zlib-compressed debug sections keep file and
//                         line info, llvm-symbolizer reads them directly). Typically 3x to 5x smaller.
// Files over 45 MB go up as a multipart upload (any size).
// Env: MONITOR_URL, and UPLOAD_TOKEN (upload-only, for CI) or ADMIN_TOKEN.
import { readFile, open, stat, mkdtemp } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { args, adminHeaders, dashUrl, fetchRetry } from './lib.mjs';

// AM_PART_MB overrides the part size (min 5, R2 limit) for testing.
const PART = Math.max(5, Number(process.env.AM_PART_MB) || 45) * 1024 * 1024;
const a = args();
if (!a.app || !a.version || !a.platform || (!a.mapping && !a.symbols)) {
  console.error('usage: upload-symbols.mjs --app <id> --version <v> --platform android|ios --mapping <file> | --symbols <file> [file ...] [--name n] [--compress-debug path/to/llvm-objcopy]');
  process.exit(2);
}
const base = `${dashUrl(a)}/admin/v1/apps/${a.app}/releases/${encodeURIComponent(a.version)}`;

async function call(url, init) {
  const res = await fetchRetry(url, { ...init, headers: adminHeaders(init.headers || {}) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${init.method} ${url.replace(base, '')}: ${res.status} ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

async function multipart(file, name, size) {
  const start = await call(`${base}/symbols/multipart?platform=${a.platform}&name=${encodeURIComponent(name)}`, { method: 'POST' });
  const id = encodeURIComponent(start.upload_id);
  const key = encodeURIComponent(start.key);
  const fh = await open(file, 'r');
  const parts = [];
  try {
    for (let n = 1, off = 0; off < size; n++, off += PART) {
      const len = Math.min(PART, size - off);
      const buf = Buffer.alloc(len);
      await fh.read(buf, 0, len, off);
      const r = await call(`${base}/symbols/multipart/${id}/${n}?key=${key}`, { method: 'PUT', body: buf, headers: { 'content-type': 'application/octet-stream' } });
      parts.push(r);
      process.stdout.write(`  part ${n}: ${Math.round((off + len) / size * 100)}%\n`);
    }
    return await call(`${base}/symbols/multipart/${id}/complete?key=${key}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ parts }),
    });
  } catch (e) {
    await call(`${base}/symbols/multipart/${id}?key=${key}`, { method: 'DELETE' }).catch(() => {});
    throw e;
  } finally {
    await fh.close();
  }
}

async function post(kind, file, nameOverride) {
  let path = file;
  if (kind === 'symbols' && a['compress-debug']) {
    const dir = await mkdtemp(join(tmpdir(), 'am-sym-'));
    path = join(dir, basename(file));
    execFileSync(a['compress-debug'], ['--compress-debug-sections=zlib', file, path]);
    console.log(`compressed debug sections: ${(await stat(file)).size} -> ${(await stat(path)).size} bytes`);
  }
  const name = kind === 'mappings' ? 'mapping.txt' : nameOverride || basename(file);
  const size = (await stat(path)).size;
  if (kind === 'symbols' && size > PART) {
    console.log(`uploading ${file} (${Math.round(size / 1048576)} MB) as ${name} in parts`);
    const r = await multipart(path, name, size);
    console.log(`uploaded ${file} as symbols/${name} (${r.size} bytes)`);
    return;
  }
  const res = await fetchRetry(`${base}/${kind}?platform=${a.platform}&name=${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: adminHeaders({ 'content-type': 'application/octet-stream' }),
    body: await readFile(path),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`upload of ${file} failed: ${res.status} ${text}`);
  console.log(`uploaded ${file} as ${kind}/${name}`);
}

try {
  if (a.mapping) await post('mappings', a.mapping);
  if (a.symbols) {
    const extra = process.argv.slice(process.argv.indexOf(a.symbols) + 1).filter((x, i, arr) => !x.startsWith('--') && !(i > 0 && arr[i - 1].startsWith('--')));
    const files = [a.symbols, ...extra];
    if (a.name && files.length > 1) throw new Error('--name works with one file only');
    for (const f of files) await post('symbols', f, a.name);
  }
} catch (e) {
  console.error(String(e.message || e));
  process.exit(1);
}
