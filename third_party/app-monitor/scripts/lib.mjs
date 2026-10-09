// Shared helpers for scripts: argument parsing and admin API calls.
// Auth: ADMIN_TOKEN (bearer), plus CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET when the
// dashboard sits behind Cloudflare Access and CI uses an Access service token.

export function args(defaults = {}) {
  const out = { ...defaults };
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i++) {
    if (!a[i].startsWith('--')) continue;
    const k = a[i].slice(2);
    const v = a[i + 1] && !a[i + 1].startsWith('--') ? a[++i] : true;
    out[k] = v;
  }
  return out;
}

export function adminHeaders(extra = {}) {
  const h = { ...extra };
  const token = process.env.ADMIN_TOKEN || process.env.UPLOAD_TOKEN;
  if (token) h.authorization = `Bearer ${token}`;
  if (process.env.CF_ACCESS_CLIENT_ID) {
    h['cf-access-client-id'] = process.env.CF_ACCESS_CLIENT_ID;
    h['cf-access-client-secret'] = process.env.CF_ACCESS_CLIENT_SECRET || '';
  }
  return h;
}

export function dashUrl(a) {
  return String(a.dash || process.env.MONITOR_URL || 'http://localhost:8798').replace(/\/$/, '');
}

export function ingestUrl(a) {
  return String(a.ingest || process.env.INGEST_URL || 'http://localhost:8797').replace(/\/$/, '');
}

// fetch with retries on network errors (DNS hiccups, resets). HTTP errors are returned as is.
export async function fetchRetry(url, init, tries = 6) {
  for (let i = 1; ; i++) {
    try {
      return await fetch(url, init);
    } catch (e) {
      if (i >= tries) throw e;
      await sleep(500 * i);
    }
  }
}

export async function admin(base, path, opts = {}) {
  const init = { method: opts.method || 'GET', headers: adminHeaders() };
  if (opts.body !== undefined) {
    init.body = JSON.stringify(opts.body);
    init.headers['content-type'] = 'application/json';
  }
  const res = await fetchRetry(base + '/admin/v1' + path, init);
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  if (!res.ok && !opts.allowError) throw new Error(`${init.method} ${path}: ${res.status} ${text.slice(0, 300)}`);
  return opts.withStatus ? { status: res.status, data } : data;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
