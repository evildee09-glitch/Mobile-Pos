# Sync server
`STORE_TOKENS="mystore:LONG-RANDOM-TOKEN" PORT=8080 node server.js` (Node 18+, no npm install).
In the app: Settings → Cloud sync URL `https://your-host`, token `LONG-RANDOM-TOKEN`.
Contract: `POST /v1/sync/push`, `GET /v1/sync/pull?cursor=&deviceId=`, `GET /health`. One token = one store = one event log.
This is a minimal reference server: add HTTPS, backups of `data/*.jsonl`, and move to PostgreSQL for many stores.

## Owner web view & multiple stores
Run with `VIEWER_TOKENS="ownerTokenA:storeA+storeB,ownerTokenB:storeC"` (token : store ids joined by `+`; the store ids are the names in `STORE_TOKENS`).
Open `https://your-host/owner`, enter the viewer token: today's sales/profit, daily chart, best sellers, low stock, expiring items, who owes, GCash/load fees —
per store, plus a combined total when the token covers several stores. Numbers are rebuilt from the synced events, so they are only as fresh as the last sync of each device.
With `LICENSE_PUBLIC_KEY` set, a store appears only while it is in trial or has a valid license (seen on its last sync); otherwise it shows "License required".
Use a long random viewer token and HTTPS: this page shows customer names and balances.
