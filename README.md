# NarrateThis 🎧

**Listen to your Google Docs like an audiobook.**

NarrateThis is an Android app that reads documents from your Google Drive out loud. It reads
sentence by sentence and highlights each sentence as it goes. It keeps reading with the screen
off and remembers where you stopped in every document.

---

## ✨ Features

- **Reads straight from Google Drive.** Works with Google Docs, Word files (`.docx`) and plain
  text files, including files in shared drives.
- **Search your Drive** by document name.
- **Follow along.** The sentence being spoken is highlighted, and the page scrolls with the
  narration.
- **Tap any paragraph** to jump straight to it.
- **Remembers your place** in every document. The home screen shows a *Continue listening*
  card, and each document in the list shows how much you've heard.
- **Works offline after the first open.** Documents are saved on your phone, so you can keep
  listening without internet.
- **Keeps playing in the background** with the screen off. A notification gives you
  Back / Play-Pause / Forward / Stop controls.
- **Easy navigation:** previous/next sentence, previous/next paragraph, a slider to scrub
  through the whole document, and *Start over*.
- **Playback speed:** 0.75×, 0.9×, 1×, 1.25×, 1.5× and 2×.
- **Choose the voice:**
  - The default is Google's British English male narrator voice ("RJS").
  - Adjust the pitch, from deeper to higher, and tap *Hear a sample*.
  - Switch to any other speech engine installed on your phone, and choose from its voices.
  - Tip: [VoxSherpa TTS](https://play.google.com/store/search?q=VoxSherpa%20TTS&c=apps) is a
    free, open-source engine with very natural offline AI voices (Kokoro, Piper).

---

## 📱 Using the app

### First launch
1. Open **NarrateThis** and tap **Connect Google Drive**.
2. Pick your Google account. Google will warn that it *"hasn't verified this app"*. This is
   normal for a personal app. Tap **Continue**, then allow **read-only** access to your Drive.
3. Your documents appear, newest first.

> NarrateThis only asks for **read-only** access. It can never change or delete anything in
> your Drive.

### Listening
| Control | What it does |
|---|---|
| ▶ / ⏸ (big button) | Play or pause. Reading continues through the whole document until you pause. |
| ⏪ / ⏩ | Previous / next sentence |
| ⏮ / ⏭ | Start of this paragraph (tap again for the previous one) / next paragraph |
| Slider | Drag to any point in the document |
| Speed chips | Change the reading speed |
| **Stop** | Stop playback and close the notification. Your place is saved. |
| **Start over** | Go back to the beginning |
| Tap a paragraph | Jump to that paragraph |
| 🗣 (top right) | Voice settings: pitch, voice, speech engine |

On Android 13 and newer, the app asks for permission to show notifications the first time you
press Play. Allow it to get playback controls on the lock screen and in the notification shade.

### Get the best voice
For the default narrator voice to work **without internet**, open 🗣 Voice settings and tap
**Download voice**. Then download *English (United Kingdom)* in Google's speech settings.

---

## 🛠 Building it yourself

The app isn't on the Play Store, so you build it with Android Studio. This takes about
20 minutes, including a one-time Google Cloud setup.

### Requirements
- A recent version of [Android Studio](https://developer.android.com/studio)
- An Android phone running **Android 8.0 (API 26) or newer**, with
  [developer options and USB debugging](https://developer.android.com/studio/debug/dev-options)
  turned on
- *Speech Services by Google* installed on the phone (it comes with most phones)
- A Google account

### 1. Get the code
Clone this repository, or download it with **Code → Download ZIP**. Then open the folder in
Android Studio with **File → Open**. Wait for Gradle to finish syncing.

### 2. Change the package name (recommended)
Google allows each package name to be registered by only one Cloud project. So pick your own,
such as `com.yourname.narratethis`. Change `applicationId` in `app/build.gradle.kts`:

```kotlin
applicationId = "com.yourname.narratethis"
```

### 3. Find your SHA-1 fingerprint
Google identifies your build by its signing key. In Android Studio's **Terminal**, run:

```bash
# Windows
gradlew.bat signingReport
# macOS / Linux
./gradlew signingReport
```

Find the `Variant: debug` section and copy the **SHA1** line. It looks like
`AB:CD:12:...`.

### 4. Set up Google Drive access (one time, free)
1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and **create a project**,
   for example *NarrateThis*.
2. Open **APIs & Services → Library**, search for **Google Drive API**, and click **Enable**.
3. Open **APIs & Services → OAuth consent screen** (also called *Google Auth Platform*):
   - User type: **External**. App name: *NarrateThis*. Enter your email where asked.
   - **Data access / Scopes:** add `https://www.googleapis.com/auth/drive.readonly`
   - **Audience / Test users:** add the Google account(s) that will use the app.
   - Leave the app in **Testing** mode. You don't need Google's verification for personal use.
4. Open **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: the `applicationId` from step 2
   - SHA-1: the fingerprint from step 3
   - Click **Create**. You don't need to copy anything back into the app. Google recognises the
     app by its package name and SHA-1.

### 5. Run it
Plug in your phone, select it in Android Studio's device menu, and press **Run ▶**.

---

## ❓ Troubleshooting

| Problem | Fix |
|---|---|
| *"Google refused sign-in (code 10)"* or *DEVELOPER_ERROR* | The package name or SHA-1 in your OAuth client doesn't match the build. Check steps 2–4. Note that each computer has its own debug key, so it has its own SHA-1. |
| *Code 7 / network error* when connecting | Usually the same cause as code 10. Otherwise, check your internet connection. |
| *"Access blocked"* or *"app not available"* | Add your Google account under **Test users** (step 4.3). |
| A document isn't listed | Only Google Docs, `.docx` and `.txt` files are shown. Try the search box or the ⟳ refresh button. |
| No sound, or a robotic voice | Open 🗣 Voice settings. Make sure *Speech Services by Google* is installed, or pick another engine and download a voice in that engine's app. |
| Narration stops when the screen turns off | Allow the notification permission. Also turn off battery optimisation for NarrateThis in Android's settings. |
| Works on your PC's build but not after building on another PC | Add a second Android OAuth client with the other PC's SHA-1. Do the same for a signed release build. |

---

## 🧩 How it works (for developers)

Written in Kotlin with Jetpack Compose. It has no backend.

| File | Role |
|---|---|
| `MainActivity.kt` | All the Compose UI: document list, player, voice settings sheet |
| `MainViewModel.kt` | Screen state, sign-in flow, document loading |
| `GoogleDrive.kt` | Google sign-in (Play services `AuthorizationClient`, read-only Drive scope) and calls to the Drive REST v3 API. Exports Google Docs as text and extracts text from `.docx` files. |
| `DocText.kt` | Splits text into paragraphs and sentences (`BreakIterator`), and splits very long sentences so the speech engine accepts them |
| `Narrator.kt` | Wraps Android `TextToSpeech`. Speaks a whole paragraph per utterance so the voice sounds natural, keeps the next paragraphs queued, follows word progress to update the highlight, and saves your place. |
| `NarrationService.kt` | Foreground media service and notification, so playback continues in the background |

Your place is saved as a character position in the document, so it survives small edits.
Documents are saved in the app's private storage for offline listening.

---

## 🔒 Privacy

- The app asks only for **read-only** Drive access.
- Documents are downloaded straight from Google to your phone and stored only in the app's
  private storage. Nothing is sent anywhere else.
- Speech is generated by your phone's speech engine. Google's network voices, and some
  third-party engines, may send text to their servers, so download an offline voice if that
  matters to you.
