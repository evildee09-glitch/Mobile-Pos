#!/usr/bin/env node
// Sari-Sari POS license tool (Node 18+, no dependencies). KEEP license-private.pem SECRET — anyone with it can mint licenses.
//   node license-tool.js keygen                       -> license-private.pem + prints the PUBLIC key to put in the app / server
//   node license-tool.js issue --holder "Aling Nena Store" --days 365 [--key license-private.pem] [--plan PREMIUM] [--id lic-0001]
//   node license-tool.js verify --pub <PUBLIC_KEY_B64> <token>
const crypto = require('node:crypto'), fs = require('node:fs');
const a = process.argv.slice(2), cmd = a[0];
const opt = (n, d) => { const i = a.indexOf('--' + n); return i >= 0 ? a[i + 1] : d; };
const b64u = b => Buffer.from(b).toString('base64url');

if (cmd === 'keygen') {
  if (fs.existsSync('license-private.pem')) { console.error('license-private.pem already exists — refusing to overwrite'); process.exit(1); }
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  fs.writeFileSync('license-private.pem', privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  console.log('Private key saved to license-private.pem (back it up somewhere safe; never ship it).\nPUBLIC KEY (build the app with -PlicensePublicKey=..., and set LICENSE_PUBLIC_KEY on the server):\n' + publicKey.export({ type: 'spki', format: 'der' }).toString('base64'));
} else if (cmd === 'issue') {
  const key = crypto.createPrivateKey(fs.readFileSync(opt('key', 'license-private.pem')));
  const iat = Math.floor(Date.now() / 1000), days = +opt('days', 365);
  const payload = { v: 1, id: opt('id', 'lic-' + crypto.randomBytes(3).toString('hex')), sub: opt('holder', 'Unnamed store'), plan: opt('plan', 'PREMIUM'), iat, exp: iat + days * 86400 };
  const body = b64u(JSON.stringify(payload));
  const sig = crypto.sign('sha256', Buffer.from(body), { key, dsaEncoding: 'der' });     // DER = what Java's SHA256withECDSA expects
  console.log(body + '.' + b64u(sig));
  console.error(`Issued ${payload.id} for "${payload.sub}" valid ${days} days (until ${new Date(payload.exp * 1000).toISOString().slice(0, 10)})`);
} else if (cmd === 'verify') {
  const pub = crypto.createPublicKey({ key: Buffer.from(opt('pub'), 'base64'), format: 'der', type: 'spki' }), token = a[a.length - 1], [body, sig] = token.split('.');
  const ok = crypto.verify('sha256', Buffer.from(body), { key: pub, dsaEncoding: 'der' }, Buffer.from(sig, 'base64url'));
  console.log(ok ? JSON.parse(Buffer.from(body, 'base64url')) : 'INVALID'); process.exit(ok ? 0 : 1);
} else console.log('Usage: keygen | issue --holder NAME --days N | verify --pub B64 TOKEN');
