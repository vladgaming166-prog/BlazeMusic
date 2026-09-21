# BlazeMuzix

**BlazeMuzix** is a lightweight, ad-free, Google-inspired **local music player** for Android. This version plays the music already stored on your device — no account, no internet connection and no API keys required — behind a clean, green-accented interface with a full player, background playback, lock-screen controls, playlists, favorites and a highly customisable appearance.

> **Local-only release.** The `YouTubeProvider` / `SpotifyProvider` code remains in the repository behind the `MusicProvider` abstraction, but online providers are compiled out (`BuildConfig.ONLINE_PROVIDERS = false`). The app never shows "YouTube not configured" or "Spotify not configured" and works fully offline.

- Package: `com.blazemuzix.app`
- Minimum Android version: **4.4 KitKat (API 19)**
- Target / compile SDK: **Android 16 (API 36)**
- Language: Kotlin, traditional Android Views (Material Components)
- No advertisements, no analytics, no trackers

---

## Table of contents

1. [Features](#features)
2. [Supported Android versions](#supported-android-versions)
3. [Architecture](#architecture)
4. [Project structure](#project-structure)
5. [Building locally](#building-locally)
6. [GitHub Actions builds](#github-actions-builds)
7. [API configuration](#api-configuration)
8. [Release signing](#release-signing)
9. [Permissions](#permissions)
10. [Provider capabilities and limitations](#provider-capabilities-and-limitations)
11. [Legal and content limitations](#legal-and-content-limitations)
12. [Performance and old-device mode](#performance-and-old-device-mode)
13. [Privacy](#privacy)
14. [Getting the APK from GitHub Actions](#getting-the-apk-from-github-actions)

---

## Features

| Area | What you get |
| --- | --- |
| **Home** | Quick access (Shuffle all · Songs · Albums · Artists), Recently played, Recently added, Favorites, Albums, Artists — all from the device. With no music found it shows a **"No music found" → "Scan device"** empty state that requests the storage permission and rescans. |
| **Local music** | MediaStore scan of every audio format the device can decode: title, artist, album, artwork, duration, folders. Play / pause / previous / next / seek / shuffle / repeat / favorite / add to playlist / queue. |
| **Search** | Instant local search over songs, artists, albums and playlists with filter chips, recent-search history (clearable) and suggestions. No network access. |
| **Library** | Songs · Albums · Artists · Playlists · Favorites · Recently played · Folders. Sort by Alphabetical / Artist / Album / Recently added / Recently played / Duration, plus an instant filter field for the current list. |
| **Player** | Mini player above the bottom navigation, full-screen player (artwork, seek bar, times, shuffle, repeat, favorite, queue), queue sheet with jump/remove, optional artwork-tinted background. |
| **Appearance** | Theme (Light / Dark / System), Accent color (6 palettes), Dynamic accent (Android 12+), Rounded corners, Compact lists, Show artwork / artist / album / duration / source badge, Mini player on/off, Background style, Navigation labels, Status bar and Navigation bar style (Android 5+), Reduced visual effects, Low-end device mode. Every switch changes the interface. |
| **Player options** | Large / compact artwork, artwork-tinted background, and individual toggles for the seek bar, queue, shuffle, repeat and favorite buttons. No lyrics feature is shown because no lyrics source exists. |
| **Background playback** | Foreground `MediaPlayer` service with `MediaSessionCompat`, MediaStyle notification (previous / play-pause / next / seek / artwork), headset and Bluetooth controls, audio focus handling, pause on unplug, automatic resource release when idle. |
| **Offline** | Everything works without a connection; the app never contacts a server. |
| **Settings** | Appearance and Player sections above, Playback (Autoplay, Shuffle, Repeat), Performance, Storage (cache size / limit / clear, local music info), About, Version, Open-source licenses, Privacy. |
| **Accessibility** | Content descriptions everywhere, 48dp touch targets, readable sizes, contrast-checked palettes, state never conveyed by color alone. |

## Supported Android versions

BlazeMuzix runs on Android 4.4.2 (API 19) through Android 16 (API 36) and newer.

| Capability | Android 4.4 – 5.x | Android 6 – 7 | Android 8 – 12 | Android 13+ |
| --- | --- | --- | --- | --- |
| Local music, library, player | Yes | Yes | Yes | Yes (`READ_MEDIA_AUDIO`) |
| Background playback + notification controls | Yes (legacy `MediaSessionCompat`) | Yes | Yes (foreground service + channel) | Yes (`mediaPlayback` FGS type, notification permission) |
| Accent colors, rounded/compact UI, list options | Yes | Yes | Yes | Yes |
| Status / navigation bar color options | Not available (setting is disabled with a note) | Yes | Yes | Yes |
| Dynamic (wallpaper) accent | No | No | Android 12+ only | Yes |
| Dark mode | Follows battery saver / manual | Manual / battery saver | Manual / battery saver | Follows system |

Every AndroidX dependency was chosen at a version that still supports API 19 (`appcompat 1.6.1`, `core 1.13.1`, `recyclerview 1.3.2`, `fragment 1.6.2`, `lifecycle 2.6.2`, `preference 1.2.1`, `media 1.7.0`, `material 1.12.0`). Java 8+ library features are desugared with `desugar_jdk_libs`, and legacy multidex is enabled for API < 21.

## Architecture

BlazeMuzix follows **MVVM + repository pattern** with strictly separated layers.

```
UI (Activities / Fragments / Adapters / ViewModels)
        │  LiveData<UiState>          PlayerController (LiveData<PlayerState>)
        ▼                                     ▲
Repositories  DiscoveryRepository  LibraryRepository       PlaybackService
        │                 │                                  (MediaPlayer + MediaSessionCompat)
        ▼                 ▼
Providers   MusicProvider ── LocalMusicProvider · YouTubeProvider · SpotifyProvider
        │
        ▼
Network / Storage   HttpClient (HttpURLConnection + coroutines) · ResponseCache · BlazeDatabase (SQLite) · AppPreferences · Glide
```

Key design points:

- **`MusicProvider`** is the single abstraction for every content source. It exposes `search`, `homeSections`, `shorts`, `collectionItems`, plus capability flags. Adding a service means implementing this interface and registering it in `ProviderRegistry`; no UI code changes.
- **`MediaItem`** carries an explicit `Playback` capability (`DIRECT`, `EMBEDDED`, `EXTERNAL`, `NONE`) so the UI always shows the real supported action ("Play", "Play 30s preview", "Watch here", "Open in Spotify", "Open in YouTube").
- **Networking** is dependency-free (`HttpURLConnection`): asynchronous, connect/read timeouts, bounded retry with exponential backoff, coroutine cancellation (disconnects the socket), offline detection, typed errors (`Offline`, `Timeout`, `RateLimited`, `Unauthorized`, `InvalidRequest`, `ServerError`, `NotConfigured`), max 4 concurrent requests, TTL response cache, forced TLS 1.2 on KitKat.
- **Persistence** is plain SQLite through `SQLiteOpenHelper` (no annotation processors, fast builds, API 19 compatible). Items are stored as JSON snapshots so the library works completely offline.
- **Images** go through Glide with view-sized downsampling, a bounded disk cache (user-configurable), RGB_565 decoding and smaller memory caches on low-end devices. Placeholders are generated once and cached.
- **Playback** uses `android.media.MediaPlayer` (present and stable on every supported version) in a foreground service with `MediaSessionCompat`, `NotificationCompat.MediaStyle`, `AudioFocusRequest` on 8+, legacy focus below, and `ServiceCompat.startForeground` with the `mediaPlayback` type on 10+.
- **UI state** is always one of `Loading / Success / Empty / Error / Offline`; there is no blank screen anywhere.

## Project structure

```
BlazeMuzix/
├── .github/workflows/build.yml       # CI: tests + debug/release APK artifacts
├── app/
│   ├── build.gradle.kts              # minSdk 19, targetSdk 35, secrets via BuildConfig
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/java/com/blazemuzix/app/
│       │   ├── BlazeApp.kt           # Application + lightweight dependency graph
│       │   ├── data/
│       │   │   ├── cache/            # ResponseCache (TTL, size-capped)
│       │   │   ├── db/               # BlazeDatabase (SQLiteOpenHelper)
│       │   │   ├── models/           # MediaItem, Section, Page, UiState, …
│       │   │   ├── prefs/            # AppPreferences
│       │   │   └── repository/       # DiscoveryRepository, LibraryRepository
│       │   ├── network/              # HttpClient, NetworkMonitor, ApiException, Tls12SocketFactory
│       │   ├── player/               # PlaybackService, PlayerController, PlayerState
│       │   ├── providers/            # MusicProvider, Local/YouTube/Spotify providers, ProviderRegistry
│       │   ├── ui/
│       │   │   ├── MainActivity.kt   # bottom navigation, mini player, offline banner
│       │   │   ├── common/           # MediaAdapter, StateView, ItemActionsSheet, WebPlayerActivity
│       │   │   ├── home/  search/  shorts/  library/  player/  settings/
│       │   └── utils/                # Artwork (Glide), DeviceProfile, formatters, intents
│       ├── main/res/                 # layouts, themes (light + night), vector icons, launcher icons
│       └── test/                     # JVM unit tests
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                   # Gradle 8.9 wrapper
├── local.properties.example          # safe configuration template
└── README.md
```

## Building locally

Requirements: JDK 17 (or 21) and the Android SDK (platform 36, build-tools 35.0.0). Android Studio is **not** required.

```bash
git clone https://github.com/vladgaming166-prog/BlazeMusic.git
cd BlazeMusic

# Point Gradle at your SDK and (optionally) add provider credentials
cp local.properties.example local.properties
#   edit local.properties → sdk.dir=/path/to/Android/sdk, YOUTUBE_API_KEY=…, SPOTIFY_CLIENT_ID=…, SPOTIFY_CLIENT_SECRET=…

./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

Install on a connected device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Without any credentials the app still builds and runs: local music, the library and the player work fully, and online screens show an honest "provider not configured" state.

### Startup checks and crash diagnostics

- `./gradlew testDebugUnitTest` includes `AppLaunchTest`, which boots the real `BlazeApp` and `MainActivity` under Robolectric on API 21, 33 and 35. A startup crash fails the build (locally and in CI) instead of being discovered on a phone.
- Debug builds install `CrashDiagnostics`: every uncaught exception is logged under the Logcat tag `BlazeMuzixCrash` and written to `files/crash/last-crash.txt` on the device. Read it with:

```bash
adb logcat -d -s BlazeMuzixCrash AndroidRuntime
adb shell run-as com.blazemuzix.app.debug cat files/crash/last-crash.txt
```

Nothing is uploaded; release builds do not install the handler.

## GitHub Actions builds

`.github/workflows/build.yml` runs on every push and pull request to `main`, and manually through **Actions → Build BlazeMuzix APK → Run workflow** (`workflow_dispatch`). It:

1. Checks out the repository
2. Sets up Temurin JDK 17
3. Installs the Android SDK (`platform-tools`, `platforms;android-36`, `build-tools;35.0.0`)
4. Accepts all Android SDK licenses
5. Restores/saves the Gradle cache (`gradle/actions/setup-gradle`)
6. Runs the unit tests
7. Builds the **debug** APK
8. Builds the **release** APK (minified with R8, signed with your keystore if configured — see below)
9. Uploads `BlazeMuzix-debug`, `BlazeMuzix-release` and `test-reports` as workflow artifacts

Repository secrets used by the workflow (all optional):

| Secret | Purpose |
| --- | --- |
| `YOUTUBE_API_KEY` | YouTube Data API v3 key |
| `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` | Spotify Web API app credentials |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore (`base64 -w0 release.jks`) |
| `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | Keystore / key credentials |

## API configuration

> Not needed for this local-only version: no key is required and the online providers are compiled out. The section below documents how credentials are wired for a future build with `ONLINE_PROVIDERS = true`.

Secrets are **never** committed. They are read at build time, in this order of precedence, and exposed through `BuildConfig`:

1. Environment variables (`YOUTUBE_API_KEY`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`) — this is how GitHub Actions secrets arrive
2. Gradle properties (`./gradlew assembleDebug -PYOUTUBE_API_KEY=…`)
3. `local.properties` (git-ignored; see `local.properties.example`)
4. Empty → the provider is reported as *not configured* in the app

Credentials are compiled into the APK at build time, so **an APK built without them will always show "not configured"** — the fix is to add the credentials and rebuild, not to change the app.

### Adding the secrets in GitHub (step by step)

1. Open your repository on GitHub → **Settings** → **Secrets and variables** → **Actions**.
2. Under **Repository secrets** click **New repository secret** and add, one at a time:

   | Secret name | Value | Required for |
   |---|---|---|
   | `YOUTUBE_API_KEY` | Your YouTube Data API v3 key | YouTube provider (Home, Search, Blaze Shorts) |
   | `SPOTIFY_CLIENT_ID` | Spotify app Client ID | Spotify provider |
   | `SPOTIFY_CLIENT_SECRET` | Spotify app Client Secret | Spotify provider |

   The YouTube key is a *public* API key (it necessarily ships inside the APK); you may add it under the **Variables** tab instead of Secrets if you prefer — the workflow accepts either. Spotify credentials must be **Secrets**.
3. Run the workflow again (**Actions** → **Build BlazeMuzix APK** → **Run workflow**, or push to `main`). Secrets are only injected into runs started after they were created.
4. In the run log, open the step **Report provider configuration**. It prints `YOUTUBE_API_KEY: set` / `MISSING` for each name (values are never printed). Gradle also logs `BlazeMuzix provider configuration: …` at the start of the build.
5. Download the new APK artifact and install it. **Settings → Providers** inside the app shows *Configured · official API* for each enabled provider.

Note: secrets are not available to workflows triggered by pull requests from forks — those builds intentionally produce a "not configured" APK.

### YouTube Data API v3
1. Create a project in the [Google Cloud Console](https://console.cloud.google.com/), enable **YouTube Data API v3** and create an API key.
2. Restrict the key to the YouTube Data API (and optionally to your Android app's package + signing certificate).
3. Set `YOUTUBE_API_KEY`.

The default quota is 10,000 units/day; a search costs 100 units. BlazeMuzix caches responses and batches duration look-ups to keep usage low, and shows a *Provider limit reached* state when the quota is exhausted.

### Spotify Web API
1. Create an app in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
2. Copy the Client ID and Client Secret and set `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET`.

BlazeMuzix uses the **Client Credentials** flow for catalog search and browsing (`search`, `browse/new-releases`, album/playlist tracks, artist top tracks). Rate limits (HTTP 429) are surfaced as a friendly state.

## Release signing

The release build type is minified and shrunk with R8. Signing works like this:

- If `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are available (as environment variables or in `local.properties`), the release APK is signed with that keystore. In CI these are derived from the `RELEASE_*` secrets listed above; the keystore is decoded to a temporary file and never committed.
- If no keystore is configured, the release APK is signed with the **debug key** so CI still produces an installable artifact. This is fine for personal use and testing but **not** for store distribution.

Create a keystore locally with:

```bash
keytool -genkeypair -v -keystore release.jks -alias blazemuzix -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # paste the output into the RELEASE_KEYSTORE_BASE64 secret
```

Never commit `*.jks`, `*.keystore` or `local.properties` (they are git-ignored).

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Provider APIs, artwork, offline detection |
| `READ_EXTERNAL_STORAGE` (≤ Android 12) / `READ_MEDIA_AUDIO` (Android 13+) | Reading the music already on the device through MediaStore. Requested at runtime only when you open the Library on Android 6+ |
| `WAKE_LOCK` | Keeps playback running with the screen off |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback service (Android 8+ / 14+) |
| `POST_NOTIFICATIONS` (Android 13+) | Media notification with playback controls |

No location, contacts, camera, microphone or account permissions are requested.

## Provider capabilities and limitations

In this release only **This device** is active. The online rows describe the dormant providers for reference.

| Source | Search / browse | Playback in BlazeMuzix | Offline download | Notes |
| --- | --- | --- | --- | --- |
| **This device** | Songs, albums, artists (MediaStore) | Full playback, background, lock-screen controls | Already offline | Files in the device's *Download* folder are tagged **DOWNLOADED** |
| **YouTube** | Music videos, playlists, channels, short-form videos (Data API v3) | Official embedded YouTube player ("Watch here") or **Open in YouTube** / **Open in YouTube Music** | Not permitted | Background audio, ads and availability are governed by YouTube. Streams are never extracted. |
| **Spotify** | Tracks, albums, artists, playlists (Web API) | **Play 30s preview** when Spotify supplies an official preview URL; otherwise official embed or **Open in Spotify** | Not permitted | Full-length playback requires the Spotify app. Preview URLs are not available for all tracks/apps. |

Additional constraints:

- Search result counts are limited only by the provider (Spotify caps search at 1,000 results; YouTube pages until `nextPageToken` ends).
- YouTube quota and Spotify rate limits are surfaced as *Provider limit reached*; invalid credentials as *Provider credentials rejected*.
- Recommendations are derived from what each API legitimately offers (trending charts, new releases, search) — BlazeMuzix never fabricates catalog data.

## Legal and content limitations

BlazeMuzix does **not** and will not:

- circumvent DRM, authentication, geographic, subscription, playback or download restrictions;
- extract, rip or convert protected audio/video streams (no "YouTube to MP3");
- scrape private or undocumented APIs;
- bypass advertisements or playback rules of third-party services;
- offer downloads for content whose provider does not explicitly permit third-party offline storage.

Offline access is implemented only for music the user already owns on the device. Third-party services may impose their own API, playback, authentication, offline and content restrictions, which BlazeMuzix respects and surfaces in the UI. YouTube is a trademark of Google LLC; Spotify is a trademark of Spotify AB. BlazeMuzix is not affiliated with or endorsed by either company.

## Performance and old-device mode

- Startup does no network work; the dependency graph is created lazily and the database warms up in the background.
- RecyclerViews use `ListAdapter`/`DiffUtil`, shared view pools and small view caches; artwork is downsampled to view size and never decoded at full resolution.
- At most 4 concurrent network requests; JSON responses are cached with TTLs and a size cap; search is debounced and cancels superseded requests.
- Blaze Shorts creates a single player lazily and never preloads pages.
- **Low-end device mode** is enabled automatically on Android < 6, `isLowRamDevice`, ≤ 128 MB heap class or ≤ 1.5 GB RAM (and can be toggled manually). It reduces image sizes, uses RGB_565 decoding, smaller caches, shorter/no animations, and disables inline video (Shorts open in YouTube instead).
- The playback service stops itself a few minutes after playback pauses and releases the player, session and wake lock.

## Privacy

- No ads, no advertising SDKs, no analytics, no crash reporters, no trackers.
- Search history, favorites, playlists, recently played and settings are stored **only on the device** (SQLite / SharedPreferences) and can be cleared from the app.
- The only network traffic is to the providers you configured (Google/YouTube APIs, Spotify API) and to artwork URLs they return.

## Getting the APK from GitHub Actions

1. Open the repository on GitHub and go to the **Actions** tab.
2. Select the **Build BlazeMuzix APK** workflow and pick the latest successful run (or start one with **Run workflow**).
3. Scroll to **Artifacts** and download `BlazeMuzix-debug` (for testing) or `BlazeMuzix-release`.
4. Unzip the artifact, copy the `.apk` to your phone, allow installation from unknown sources and install it.

The debug APK uses the application id `com.blazemuzix.app.debug`, so it can be installed side-by-side with a release build.

## License

BlazeMuzix source code is provided under the MIT License. Third-party libraries keep their own licenses (listed inside the app under *Settings → Open-source licenses*).
