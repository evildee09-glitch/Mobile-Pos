// Sari-Sari POS sync server — zero dependencies (Node 18+). 
// Each STORE has its own bearer token and its own append-only event log (data isolation between stores).
//   STORE_TOKENS="aling-nena:s3cret1,mang-pedro:s3cret2" PORT=8080 DATA_DIR=./data node server.js
// Put it behind HTTPS (Caddy / nginx / Cloudflare Tunnel). For many stores swap the JSONL files for PostgreSQL (same contract).
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const PORT = +process.env.PORT || 8080, DATA = process.env.DATA_DIR || path.join(__dirname, 'data');
const tokens = new Map(); // token -> storeId
for (const p of (process.env.STORE_TOKENS || '').split(',').filter(Boolean)) { const [s, t] = p.split(':'); if (s && t) tokens.set(t, s.replace(/[^a-z0-9_-]/gi, '')); }
if (!tokens.size) { console.error('Set STORE_TOKENS="store:token,..."'); process.exit(1); }
fs.mkdirSync(DATA, { recursive: true });

const stores = new Map(); // storeId -> { file, events: [], ids: Set }
function store(id) {
  if (stores.has(id)) return stores.get(id);
  const file = path.join(DATA, id + '.jsonl'), events = [], ids = new Set();
  if (fs.existsSync(file)) for (const l of fs.readFileSync(file, 'utf8').split('\n')) if (l.trim()) { const e = JSON.parse(l); events.push(e); ids.add(e.id); }
  const s = { file, events, ids }; stores.set(id, s); return s;
}
const send = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
const safeEq = (a, b) => a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
function auth(req) {
  const t = (req.headers.authorization || '').replace(/^Bearer /, '');
  for (const [tok, id] of tokens) if (safeEq(tok, t)) return id;
  return null;
}
function body(req) { return new Promise((ok, no) => { let b = ''; req.on('data', c => { b += c; if (b.length > 10e6) { no(new Error('too large')); req.destroy(); } }); req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch (e) { no(e); } }); }); }

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  try {
    if (url.pathname === '/health') return send(res, 200, { ok: true });
    const sid = auth(req); if (!sid) return send(res, 401, { error: 'unauthorized' });
    const st = store(sid);
    if (req.method === 'POST' && url.pathname === '/v1/sync/push') {
      const { deviceId, events } = await body(req);
      if (!deviceId || !Array.isArray(events)) return send(res, 400, { error: 'bad request' });
      const acked = []; let out = '';
      for (const e of events) {
        if (!e.id || !e.entityType || !e.operation || typeof e.payload !== 'object') continue;
        if (!st.ids.has(e.id)) {                       // idempotent: a retried push never duplicates a sale
          const rec = { seq: st.events.length + 1, id: e.id, deviceId, entityType: e.entityType, entityId: e.entityId, operation: e.operation, createdAt: e.createdAt, payload: e.payload };
          st.events.push(rec); st.ids.add(e.id); out += JSON.stringify(rec) + '\n';
        }
        acked.push(e.id);
      }
      if (out) fs.appendFileSync(st.file, out);        // durable before we acknowledge
      return send(res, 200, { acked });
    }
    if (req.method === 'GET' && url.pathname === '/v1/sync/pull') {
      const cursor = Math.max(0, parseInt(url.searchParams.get('cursor') || '0', 10) || 0), dev = url.searchParams.get('deviceId');
      const slice = st.events.slice(cursor, cursor + 200);              // seq == index+1
      const events = slice.filter(e => e.deviceId !== dev);
      return send(res, 200, { events, cursor: String(cursor + slice.length) });
    }
    send(res, 404, { error: 'not found' });
  } catch (e) { send(res, 400, { error: String(e.message || e) }); }
}).listen(PORT, () => console.log('sync server on :' + PORT));
