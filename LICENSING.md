# Plans & licensing

**Free forever:** selling, receipts, cash/e-wallet payments, QR display, stock, expiry warnings, basic Utang, manual backup/CSV export.
**Premium** (30-day free trial of everything): Google Drive auto-backup, multi-device sync, purchase orders, CSV import & bulk edits,
sales & profit insights, smart reorder, Utang aging, bulk reminders, receipt header/footer, GCash cash-in/out & load tracking.
Change what is premium in `Feature` / `LicenseState.allows` (`domain/License.kt`).

## One-time setup (you)
1. `node tools/license-tool.js keygen` → creates `license-private.pem` (KEEP SECRET, back it up, never put it in the app or git) and prints the **public key**.
2. Build the app with the public key and how customers can pay you:
   `./gradlew assembleRelease -PlicensePublicKey=MFkwEwYH... "-PpurchaseInfo=GCash 0917-xxx-xxxx · Viber same number"`
   (no public key = developer build: licensing off, everything unlocked).
3. Optional, protects the cloud features server-side too: run the server with `LICENSE_PUBLIC_KEY=MFkwEwYH...`.
   Each store then gets 30 days from its first sync, after which the server answers 402 unless the app sends a valid license.

## Selling a license
`node tools/license-tool.js issue --holder "Aling Nena Store" --days 365` → prints a **short code** like `SS-AVKAC-qiQW…` (about 110–120 characters). Copy it straight to the clipboard on Windows with `| clip`.
Send it by Viber/SMS; the owner pastes it in Settings → Plan & license (or on the lock screen). Codes are signed (ECDSA P-256), checked offline, and cannot be edited.

- The short code keeps only the first 24 bytes of the holder name; a code with no name is ~97 characters. Add `--long` for the older, longer self-describing format (the app and server accept both).
- A short code is valid through the END of its expiry day. About 64 of its bytes are the signature, which is why it cannot be much shorter and stay secure offline.

## Rules built in
- Selling is never locked. After a license expires there is a **14-day grace period**, then only the premium extras switch off; all data stays readable and exportable.
- Changing the phone clock backwards does not extend a trial or license.
- A license code is a bearer token: whoever has it can use it (on any phone). Check the holder name shown in the app if you suspect sharing.
- Honest limit: an offline APK can always be modified by a determined person. Server-side checks (sync) are the part that can't be bypassed in the app.
- If you sell through Google Play, Google requires its own billing for in-app digital features. This system fits direct sales (GCash/Maya/bank).

## Premium features added later
Supplier payables, promos & bundles, daily alerts, PDF reports, barcode labels, owner web view and multi-store view are all `Feature` flags (see `domain/License.kt`).
Multi-store = one license per store; the owner web view shows a store only while that store is licensed (or in trial).
