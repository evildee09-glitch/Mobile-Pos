// Sari-Sari POS sync server — zero dependencies (Node 18+). 
// Each STORE has its own bearer token and its own append-only event log (data isolation between stores).
//   STORE_TOKENS="aling-nena:s3cret1,mang-pedro:s3cret2" PORT=8080 DATA_DIR=./data node server.js
// Put it behind HTTPS (Caddy / nginx / Cloudflare Tunnel). For many stores swap the JSONL files for PostgreSQL (same contract).
const http = require('node:http'), fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto');
const PORT = +process.env.PORT || 8080, DATA = process.env.DATA_DIR || path.join(__dirname, 'data');
// ---- optional license enforcement for the cloud features (set LICENSE_PUBLIC_KEY from tools/license-tool.js keygen) ----
const LIC_PUB = process.env.LICENSE_PUBLIC_KEY ? crypto.createPublicKey({ key: Buffer.from(process.env.LICENSE_PUBLIC_KEY, 'base64'), format: 'der', type: 'spki' }) : null;
const TRIAL_MS = 30 * 864e5, GRACE_MS = 14 * 864e5, metaFile = path.join(DATA, 'meta.json');
let meta = {}; try { meta = JSON.parse(fs.readFileSync(metaFile, 'utf8')); } catch (_) {}
const saveMeta = () => fs.writeFileSync(metaFile, JSON.stringify(meta));
function getMeta(id) { let m = meta[id]; if (typeof m === 'number') m = meta[id] = { first: m }; if (!m) { m = meta[id] = { first: Date.now() }; saveMeta(); } return m; }
function licenseOk(storeId, header) {
  if (!LIC_PUB) return true;                                                     // enforcement off
  const m = getMeta(storeId);                                                     // trial starts at the store's first request
  try {
    const [body, sig] = String(header || '').split('.');
    if (crypto.verify('sha256', Buffer.from(body), { key: LIC_PUB, dsaEncoding: 'der' }, Buffer.from(sig, 'base64url'))) {
      const exp = JSON.parse(Buffer.from(body, 'base64url')).exp * 1000;
      if (Date.now() <= exp + GRACE_MS) { if (m.licExp !== exp) { m.licExp = exp; saveMeta(); } return true; }
    }
  } catch (_) {}
  return Date.now() - m.first < TRIAL_MS;
}
/** The owner web view is allowed for stores with a valid (or recently lapsed) license seen on sync, or still in their trial. */
function viewerAllowed(storeId) {
  if (!LIC_PUB) return true; const m = meta[storeId]; if (!m) return true; if (typeof m === 'number') return Date.now() - m < TRIAL_MS;
  return (m.licExp && Date.now() <= m.licExp + GRACE_MS) || Date.now() - m.first < TRIAL_MS;
}

// ---- owner web view: read-only numbers rebuilt from the synced event log ----
const viewers = new Map(); // viewer token -> [storeIds]   VIEWER_TOKENS="ownerToken:storeA+storeB,otherToken:storeC"
for (const p of (process.env.VIEWER_TOKENS || '').split(',').filter(Boolean)) { const i = p.indexOf(':'); if (i > 0) viewers.set(p.slice(0, i), p.slice(i + 1).split('+').map(x => x.replace(/[^a-z0-9_-]/gi, '')).filter(Boolean)); }
const DAYMS = 864e5, MANILA = 8 * 3600e3, dayKey = t => new Date(t + MANILA).toISOString().slice(0, 10);

function aggregate(events, now, days) {
  const sales = new Map(), products = new Map(), stock = new Map(), customers = new Map(), bal = new Map(), services = new Map(); let storeName = null;
  const add = (m, k, d) => m.set(k, (m.get(k) || 0) + d);
  for (const e of events) {
    const p = e.payload || {};
    switch (e.entityType + '/' + e.operation) {
      case 'STORE/INFO': storeName = p.name; break;
      case 'PRODUCT/UPSERT': { const c = products.get(p.id); if (!c || p.updatedAt >= c.updatedAt) products.set(p.id, { name: p.name, reorder: p.reorder || 0, expiry: p.expiry || null, active: p.active !== false, updatedAt: p.updatedAt || 0, unit: p.baseUnit }); break; }
      case 'CUSTOMER/UPSERT': customers.set(p.id, p.name); break;
      case 'SALE/CREATE': {
        const s = p.sale; sales.set(s.id, { created: s.createdAt, total: s.total, voided: false, items: (p.items || []).map(i => ({ name: i.name, subtotal: i.subtotal, base: i.base, cost: i.cost })) });
        for (const i of p.items || []) add(stock, i.productId, -i.base);
        for (const l of p.ledger || []) add(bal, l.customerId, l.amount); break; }
      case 'SALE/VOID': { const s = sales.get(p.id); if (s) s.voided = true; for (const m of p.moves || []) add(stock, m.productId, m.delta); for (const l of p.ledger || []) add(bal, l.customerId, l.amount); break; }
      case 'DEBT_PAYMENT/CREATE': add(bal, p.ledger.customerId, p.ledger.amount); break;
      case 'STOCK/DELTA': add(stock, p.move.productId, p.move.delta); break;
      case 'SERVICE/CREATE': services.set(p.id, { created: p.createdAt, fee: p.fee, voided: !!p.voided }); break;
      case 'SERVICE/VOID': { const t = services.get(p.id); if (t) t.voided = true; break; }
    }
  }
  const from = now - days * DAYMS, todayKey = dayKey(now), dailyMap = new Map(), top = new Map();
  let periodSales = 0, periodProfit = 0, periodCount = 0, today = { sales: 0, count: 0, profit: 0, fees: 0 }, fees = 0;
  for (const s of sales.values()) {
    if (s.voided || s.created < from) continue;
    const profit = s.items.reduce((a, i) => a + i.subtotal - Math.floor((i.cost * i.base + 500) / 1000), 0), k = dayKey(s.created);
    const d = dailyMap.get(k) || { day: k, total: 0, count: 0, profit: 0 }; d.total += s.total; d.count++; d.profit += profit; dailyMap.set(k, d);
    periodSales += s.total; periodProfit += profit; periodCount++;
    if (k === todayKey) { today.sales += s.total; today.count++; today.profit += profit; }
    for (const i of s.items) { const t = top.get(i.name) || { name: i.name, revenue: 0 }; t.revenue += i.subtotal; top.set(i.name, t); }
  }
  for (const t of services.values()) if (!t.voided && t.created >= from) { fees += t.fee; if (dayKey(t.created) === todayKey) today.fees += t.fee; }
  const daily = []; for (let i = days - 1; i >= 0; i--) { const k = dayKey(now - i * DAYMS); daily.push(dailyMap.get(k) || { day: k, total: 0, count: 0, profit: 0 }); }
  const lowStock = [], expiring = []; const soon = dayKey(now + 30 * DAYMS);
  for (const [id, pr] of products) {
    if (!pr.active) continue; const st = stock.get(id) || 0;
    if (pr.reorder > 0 && st <= pr.reorder) lowStock.push({ name: pr.name, stock: st / 1000, reorder: pr.reorder / 1000, unit: pr.unit });
    if (pr.expiry && st > 0 && pr.expiry <= soon) expiring.push({ name: pr.name, expiry: pr.expiry, stock: st / 1000, expired: pr.expiry < todayKey });
  }
  const debtors = [...bal].filter(([, v]) => v > 0).map(([id, v]) => ({ name: customers.get(id) || 'Customer', balance: v })).sort((a, b) => b.balance - a.balance);
  return { storeName, today, period: { days, sales: periodSales, profit: periodProfit, count: periodCount, fees }, daily,
    top: [...top.values()].sort((a, b) => b.revenue - a.revenue).slice(0, 10), lowStock: lowStock.slice(0, 30), expiring: expiring.sort((a, b) => a.expiry < b.expiry ? -1 : 1).slice(0, 30),
    utang: { total: debtors.reduce((a, d) => a + d.balance, 0), debtors: debtors.slice(0, 15) } };
}
const OWNER_HTML = `<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Owner view</title>
<style>body{font-family:system-ui,sans-serif;background:#f6f8f6;margin:0;padding:14px;color:#14201a}.card{background:#fff;border-radius:16px;padding:14px;margin:10px 0;box-shadow:0 1px 3px #0001}
h1,h2,h3{margin:.3em 0}.row{display:flex;gap:10px;flex-wrap:wrap}.stat{flex:1;min-width:130px;background:#cff0e3;border-radius:14px;padding:10px}.stat b{font-size:22px;display:block}
.bars{display:flex;align-items:flex-end;height:90px;gap:2px}.bars i{flex:1;background:#0b7a5a;border-radius:3px 3px 0 0;min-height:2px}table{width:100%;border-collapse:collapse;font-size:14px}td{padding:4px 0;border-bottom:1px solid #eee}td:last-child{text-align:right}
.bad{color:#b3261e}input,button,select{padding:10px;border-radius:10px;border:1px solid #bbb;font-size:15px}button{background:#0b7a5a;color:#fff;border:0}</style>
<h1>📊 Owner view</h1><div id=login><input id=t type=password placeholder="Viewer token"> <button onclick=go()>Open</button></div>
<div id=ctl style=display:none>Period: <select id=d onchange=load()><option>7<option selected>30<option>90</select> <span id=upd></span></div><div id=out></div>
<script>const P=c=>'₱'+(c/100).toLocaleString('en-PH',{minimumFractionDigits:2,maximumFractionDigits:2});let tok=sessionStorage.getItem('vt')||'';if(tok){t.value=tok;go()}
function go(){tok=t.value.trim();sessionStorage.setItem('vt',tok);load();setInterval(load,60000)}
async function load(){const r=await fetch('/owner/api/summary?days='+d.value,{headers:{Authorization:'Bearer '+tok}});if(!r.ok){out.innerHTML='<p class=bad>Wrong token</p>';return}
login.style.display='none';ctl.style.display='block';const j=await r.json();upd.textContent='updated '+new Date().toLocaleTimeString();let h='';
if(j.stores.length>1)h+=card('All stores combined',j.combined,true);for(const s of j.stores)h+=s.locked?'<div class=card><h2>'+s.id+'</h2><p class=bad>License required for the owner view of this store.</p></div>':card(s.storeName||s.id,s,false);out.innerHTML=h}
function card(n,s,comb){const mx=Math.max(1,...(s.daily||[]).map(x=>x.total));let h='<div class=card><h2>'+n+'</h2><div class=row><div class=stat>Today<b>'+P(s.today.sales)+'</b>'+s.today.count+' sales · profit '+P(s.today.profit)+'</div><div class=stat>Last '+s.period.days+' days<b>'+P(s.period.sales)+'</b>profit '+P(s.period.profit)+'</div><div class=stat>Utang owed<b>'+P(s.utang.total)+'</b>'+(s.utang.debtors||[]).length+' customers</div><div class=stat>GCash/load fees<b>'+P(s.period.fees)+'</b>this period</div></div>';
if(s.daily)h+='<h3>Sales per day</h3><div class=bars>'+s.daily.map(x=>'<i title="'+x.day+' '+P(x.total)+'" style="height:'+Math.max(2,x.total/mx*100)+'%"></i>').join('')+'</div>';
if(!comb){h+='<h3>Best sellers</h3><table>'+s.top.map(x=>'<tr><td>'+x.name+'<td>'+P(x.revenue)).join('')+'</table>';
h+='<h3>Low stock</h3>'+(s.lowStock.length?'<table>'+s.lowStock.map(x=>'<tr><td>'+x.name+'<td>'+x.stock+' / '+x.reorder).join('')+'</table>':'<p>None 👍</p>');
h+='<h3>Expired / expiring</h3>'+(s.expiring.length?'<table>'+s.expiring.map(x=>'<tr><td>'+x.name+'<td class="'+(x.expired?'bad':'')+'">'+x.expiry).join('')+'</table>':'<p>None 👍</p>');
h+='<h3>Who owes</h3>'+(s.utang.debtors.length?'<table>'+s.utang.debtors.map(x=>'<tr><td>'+x.name+'<td>'+P(x.balance)).join('')+'</table>':'<p>Nobody 👍</p>')}
return h+'</div>'}</script>`;
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
    if (url.pathname === '/owner') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(OWNER_HTML); }
    if (url.pathname === '/owner/api/summary') {
      const vt = (req.headers.authorization || '').replace(/^Bearer /, ''); let ids = null;
      for (const [tk, list] of viewers) if (safeEq(tk, vt)) ids = list;
      if (!ids) return send(res, 401, { error: 'unauthorized' });
      const days = Math.min(365, Math.max(1, parseInt(url.searchParams.get('days') || '30', 10) || 30)), now = Date.now();
      const stores = ids.map(id => viewerAllowed(id) ? { id, ...aggregate(store(id).events, now, days) } : { id, locked: true });
      const live = stores.filter(x => !x.locked), sum = f => live.reduce((a, x) => a + f(x), 0);
      const combined = { today: { sales: sum(x => x.today.sales), count: sum(x => x.today.count), profit: sum(x => x.today.profit), fees: sum(x => x.today.fees) },
        period: { days, sales: sum(x => x.period.sales), profit: sum(x => x.period.profit), count: sum(x => x.period.count), fees: sum(x => x.period.fees) },
        daily: live.length ? live[0].daily.map((d, i) => ({ day: d.day, total: live.reduce((a, x) => a + x.daily[i].total, 0), count: 0, profit: 0 })) : [],
        utang: { total: sum(x => x.utang.total), debtors: [] } };
      return send(res, 200, { stores, combined });
    }
    const sid = auth(req); if (!sid) return send(res, 401, { error: 'unauthorized' });
    if (!licenseOk(sid, req.headers['x-license'])) return send(res, 402, { error: 'license required' });
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
