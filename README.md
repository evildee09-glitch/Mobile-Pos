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
If you installed an earlier build, **uninstall it first** (database schema changed; v1 had no release migrations).

### Implemented
- Offline-first Room DB (integer centavos, milli-unit quantities, historical snapshots), atomic idempotent checkout, cash/GCash/Maya/ShopeePay/split/Utang.
- Unit conversions, fractional sales, receiving, adjustments, movement history, Utang ledger, partial repayments, statements.
- PIN login + lockout, role permissions in the service layer, owner-PIN approvals, audit log, shifts, Z-Read, void.
- **New UI:** theme (light/dark), bottom navigation, product grid with categories, cart bottom-sheet, quick-cash chips, payment sheet, cards everywhere.
- **Camera barcode scanning** (CameraX + ML Kit, offline) at checkout (continuous), in Stock, and in the product form. Keyboard scanners still work.
- **Purchase orders:** draft → ordered → partial/received, reorder suggestions from low stock, over-receipt blocked, share PO as text.
- **CSV import** (preview/dry-run, per-row errors, match by barcode/SKU) and export; **bulk price** (%/±₱/set) and **bulk stock** (±qty or stock count).
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
