# Sari-Sari POS (offline-first Android mPOS)

Kotlin · Jetpack Compose · Room/SQLite · WorkManager · manual DI (`AppContainer`) · MVVM

## Open & run
1. Android Studio Koala+ (JDK 17). *File > Open* this folder. Studio generates the Gradle wrapper and downloads dependencies.
2. Run on an Android 10+ device. First launch asks you to create the Owner (4-digit PIN).
3. Tests: `./gradlew testDebugUnitTest` (JVM) and `./gradlew connectedDebugAndroidTest` (device/emulator, Room + business rules).

## STATUS — read this first
Written without a compiler or device. **The Android code has never been built or run** — expect some compile errors on first sync
(typos, API drift) and untested behaviour. The only thing actually executed so far is the sync server (`server/`, tested with curl:
auth, idempotent retry, per-device filtering, per-store isolation, cursor).
If you installed an earlier build, **uninstall it first** (the database schema changes between these builds; there are no migrations until the first real release).

### Implemented
- Offline-first Room DB (integer centavos, milli-unit quantities, historical snapshots), atomic idempotent checkout, cash/GCash/Maya/ShopeePay/split/Utang.
- Unit conversions, fractional sales, receiving, adjustments, movement history, Utang ledger, partial repayments, statements.
- PIN login + lockout, role permissions in the service layer, owner-PIN approvals, audit log, shifts, Z-Read, void.
- **New UI:** theme (light/dark), bottom navigation, product grid with categories, cart bottom-sheet, quick-cash chips, payment sheet, cards everywhere.
- **Camera barcode scanning** (CameraX + ML Kit, offline) at checkout (continuous), in Stock, and in the product form. Keyboard scanners still work.
- **Purchase orders:** draft → ordered → partial/received, reorder suggestions from low stock, over-receipt blocked, share PO as text.
- **CSV import** (preview/dry-run, per-row errors, match by barcode/SKU) and export; **bulk price** (%/±₱/set) and **bulk stock** (±qty or stock count).
- **Google Drive backup:** each owner connects their OWN Drive (hidden app folder, encrypted with their passphrase, daily, keeps 7). Needs the one-time Google Cloud setup in GOOGLE_DRIVE_SETUP.md. Not tested against real Google accounts.
- **Plans & licensing:** 30-day trial, signed offline license codes, 14-day grace, lock screen for premium features, optional server-side check (see LICENSING.md). Not yet tested on a device; the code format and server check ARE tested (Node signs, Java verifies, server returns 402).
- **GCash cash-in/out & e-load tracking** (drawer effect + fee, feeds the Z-Read), **sales & profit insights** (best sellers, slow movers, daily charts), **smart reorder** from real sales speed, **Utang aging** (FIFO) with a one-by-one reminder queue, **receipt header/footer**.
- **Supplier payables** (bills, due dates, partial payments, pay from drawer = shift expense), **promos & bundles** (N-for-price, buy X get Y, % off, volume price; applied automatically, never stacked), **daily alerts** (local notification), **PDF reports** (sales, inventory, receivables, payables, statements — made on the phone), **barcode labels** (thermal ESC/POS and A4 PDF sheet; in-store EAN-13 generator), **owner web view** + **multi-store** (server `/owner`, one viewer token can combine several stores).
- **Expiry dates:** per-product date + batch (date picker), set when adding products or receiving deliveries (nearest date is kept), ⏳ Expiring filter and warning window (Settings), expired items blocked at the till (UI + service layer), one-tap write-off, CSV `expiry` column. One date per product, not full per-batch stock tracking.
- **Reminders:** SMS / Viber / WhatsApp / any app via the user's own apps (nothing sent silently), PH number normalisation, duplicate-reminder warning.
- **Multi-device sync:** outbox push + pull; append-only facts (sales, payments, stock deltas) merge additively; products/customers last-writer-wins;
  negative stock / over-limit conflicts are kept and logged as SYNC_CONFLICT audit rows. `server/` is a runnable reference server (Node, no deps).
- Hardware: ESC/POS over Bluetooth and LAN, drawer kick, paired-printer picker, Test print. Encrypted backup/restore. Reports.

### Still NOT done / unverified
- Printer, cash drawer and built-in terminal printers on real hardware (see HARDWARE.md). Vendor SDKs not integrated.
- Purchase orders and reminder logs are local-only (not synced). Staff accounts are not synced between devices (create the same staff on each).
- Sync conflicts are only logged — there is no conflict-resolution screen yet. A failed event apply is logged but earlier partial writes are not rolled back.
- Server: single process, JSON-lines storage, no TLS (put it behind HTTPS), no PostgreSQL, no cloud backup upload.
- Finalized Z-Read amendments, per-sale debt allocation UI, DB migrations, UI tests, security/privacy review, BIR accreditation.

### Known limitations
- Backup copies the DB after a WAL checkpoint; back up while the till is idle. 4-digit PINs are weak; lockout only slows guessing.

## Sync contract (optional)
`POST {sync_url}/v1/sync/push` with `{deviceId, events:[{id, entityType, entityId, operation, createdAt, payload}]}`
→ `{"acked":["<event id>", ...]}`. Server must dedupe on event `id`. Rows are marked synced only if acked.
