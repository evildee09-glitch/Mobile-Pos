#!/usr/bin/env node
// Sari-Sari POS license tool (Node 18+, no dependencies). KEEP license-private.pem SECRET — anyone with it can mint licenses.
//   node license-tool.js keygen                       -> license-private.pem + prints the PUBLIC key to put in the app / server
//   node license-tool.js issue --holder "Aling Nena" --days 365 [--key license-private.pem] [--long]
//        default = SHORT code "SS-..." (~110 chars). --long = the older, longer self-describing format (still accepted by the app).
//   node license-tool.js verify --pub <PUBLIC_KEY_B64> <code>
const crypto = require('node:crypto'), fs = require('node:fs');
const a = process.argv.slice(2), cmd = a[0];
const opt = (n, d) => { const i = a.indexOf('--' + n); return i >= 0 ? a[i + 1] : d; };
const b64u = b => Buffer.from(b).toString('base64url');
const pubKey = b64 => crypto.createPublicKey({ key: Buffer.from(b64, 'base64'), format: 'der', type: 'spki' });

/** Short format: "SS-" + base64url( 0x01 | expiryDay(2 bytes, days since 1970) | id(3 bytes) | holder(UTF-8, <=24 bytes) | signature(64 bytes, raw r||s) ). */
function verifyAny(token, pub) {
  token = token.trim();
  if (token.startsWith('SS-')) {
    const raw = Buffer.from(token.slice(3), 'base64url'); if (raw.length < 70) return null;
    const pl = raw.subarray(0, raw.length - 64), sig = raw.subarray(raw.length - 64);
    if (!crypto.verify('sha256', pl, { key: pub, dsaEncoding: 'ieee-p1363' }, sig)) return null;
    return { id: pl.subarray(3, 6).toString('hex'), holder: pl.subarray(6).toString('utf8'), plan: 'PREMIUM', expires: new Date(((pl[1] << 8 | pl[2]) * 86400 + 86399) * 1000).toISOString().slice(0, 10) };
  }
  const [body, sig] = token.split('.');
  if (!crypto.verify('sha256', Buffer.from(body), { key: pub, dsaEncoding: 'der' }, Buffer.from(sig, 'base64url'))) return null;
  const p = JSON.parse(Buffer.from(body, 'base64url')); return { id: p.id, holder: p.sub, plan: p.plan, expires: new Date(p.exp * 1000).toISOString().slice(0, 10) };
}

if (cmd === 'keygen') {
  if (fs.existsSync('license-private.pem')) { console.error('license-private.pem already exists — refusing to overwrite'); process.exit(1); }
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  fs.writeFileSync('license-private.pem', privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  console.log('Private key saved to license-private.pem (back it up somewhere safe; never ship it).\nPUBLIC KEY (build the app with -PlicensePublicKey=..., and set LICENSE_PUBLIC_KEY on the server):\n' + publicKey.export({ type: 'spki', format: 'der' }).toString('base64'));
} else if (cmd === 'issue') {
  const key = crypto.createPrivateKey(fs.readFileSync(opt('key', 'license-private.pem'))), days = +opt('days', 365), nowSec = Math.floor(Date.now() / 1000);
  let holder = opt('holder', ''); while (Buffer.byteLength(holder) > 24) holder = holder.slice(0, -1);                 // short code keeps only the first 24 bytes of the name
  const idHex = crypto.randomBytes(3).toString('hex'), expSec = nowSec + days * 86400;
  if (a.includes('--long')) {
    const body = b64u(JSON.stringify({ v: 1, id: 'lic-' + idHex, sub: opt('holder', 'Unnamed store'), plan: 'PREMIUM', iat: nowSec, exp: expSec }));
    console.log(body + '.' + b64u(crypto.sign('sha256', Buffer.from(body), { key, dsaEncoding: 'der' })));
  } else {
    const day = Math.floor(expSec / 86400);
    const payload = Buffer.concat([Buffer.from([1, day >> 8, day & 255]), Buffer.from(idHex, 'hex'), Buffer.from(holder)]);
    console.log('SS-' + b64u(Buffer.concat([payload, crypto.sign('sha256', payload, { key, dsaEncoding: 'ieee-p1363' })])));
  }
  console.error(`Issued lic-${idHex} for "${holder}" valid ${days} days (until ${new Date((Math.floor(expSec / 86400) * 86400 + 86399) * 1000).toISOString().slice(0, 10)})`);
} else if (cmd === 'verify') {
  const r = verifyAny(a[a.length - 1], pubKey(opt('pub'))); console.log(r || 'INVALID'); process.exit(r ? 0 : 1);
} else console.log('Usage: keygen | issue --holder NAME --days N [--long] | verify --pub B64 CODE');
