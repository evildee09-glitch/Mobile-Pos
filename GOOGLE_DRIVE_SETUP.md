# Google Drive backup — one-time setup (you, the developer)

The app talks to Google using the *Authorization API*: there is **no client secret or API key in the code**.
Google recognises the app by its **package name** and **signing-key SHA-1**.

1. Open https://console.cloud.google.com → create a project (e.g. "Sari-Sari POS").
2. **APIs & Services → Library → Google Drive API → Enable.**
3. **OAuth consent screen:** User type *External*; app name, support email, developer email.
   Add the scope `.../auth/drive.appdata` (this "app data folder" scope is non-sensitive: the app can only see files it created).
   While the app is in *Testing*, only the Google accounts you add as **Test users** can connect (max 100).
   When ready, click **Publish app** so any owner can connect (Google may ask for a short brand review).
4. **Credentials → Create credentials → OAuth client ID → Android**
   - Package name: `ph.sarisari.mpos`
   - SHA-1: run `./gradlew signingReport` (debug key) — and add your **release key** SHA-1 too.
     If you publish on Google Play with *Play App Signing*, also add the **app-signing key SHA-1** shown in Play Console → App integrity.
   You may need one Android client per key.
5. Install the app, Settings → Google Drive backup → type a passphrase → Connect → choose the Google account → Allow.

Troubleshooting: "sign-in failed" / error 10 or 12500 almost always means the package name or SHA-1 in step 4 does not match the build you installed.
Devices without Google Play services (some Huawei phones) cannot use this feature; they can still use the manual backup export.
