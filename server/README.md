# Sync server
`STORE_TOKENS="mystore:LONG-RANDOM-TOKEN" PORT=8080 node server.js` (Node 18+, no npm install).
In the app: Settings → Cloud sync URL `https://your-host`, token `LONG-RANDOM-TOKEN`.
Contract: `POST /v1/sync/push`, `GET /v1/sync/pull?cursor=&deviceId=`, `GET /health`. One token = one store = one event log.
This is a minimal reference server: add HTTPS, backups of `data/*.jsonl`, and move to PostgreSQL for many stores.
