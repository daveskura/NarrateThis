# NarrateThis – one-time Google setup

The app reads your Google Docs through the Google Drive API, so Google needs to know about it.
This is free and takes about 10 minutes.

1. Go to https://console.cloud.google.com/ and create a project (e.g. "NarrateThis").
2. **APIs & Services → Library** → search for **Google Drive API** → **Enable**.
3. **APIs & Services → OAuth consent screen** (a.k.a. "Google Auth Platform"):
   - User type: **External**. App name: NarrateThis. Fill in your email where asked.
   - **Data access / Scopes**: add `https://www.googleapis.com/auth/drive.readonly`.
   - **Audience / Test users**: add your own Google account (dskura@gmail.com).
   - Leave the app in **Testing** mode. No verification is needed for your own use.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.dskura.narratethis`
   - SHA-1 certificate fingerprint (your debug key on this PC):
     `E3:54:D1:A4:6E:80:F5:3B:BE:1E:A6:34:44:B2:10:BF:9F:64:65:1A`
   - Save. You don't need to copy anything into the app; Google matches on package + SHA-1.
5. Run the app from Android Studio onto your phone and tap **Connect Google Drive**.
   You'll see a "Google hasn't verified this app" warning. That's expected for your own
   test app: tap **Continue**.

If you see "DEVELOPER_ERROR", the package name or SHA-1 in step 4 doesn't match.
If you later build a signed release APK, add a second Android OAuth client with the
release key's SHA-1.
