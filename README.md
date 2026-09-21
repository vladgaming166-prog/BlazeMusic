# BlazeMuzix 2.0.0

**BlazeMuzix** is a lightweight, ad-free, Google-inspired music app for Android. Version **2.0.0** combines a full **local music player** with **official YouTube Data API v3** and **Spotify Web API** discovery, Blaze Shorts, Google Sign-In, Spotify PKCE, playlists, queue, sleep timer and a highly customisable green/white/black interface.

- Package: `com.blazemuzix.app`
- Version: **2.0.0** (versionCode **200**)
- Minimum Android version: **4.4 KitKat (API 19)**
- Target / compile SDK: **Android 16 (API 36)**
- Language: Kotlin, traditional Android Views (Material Components)
- No advertisements, no analytics, no trackers

BlazeMuzix does **not** scrape providers, extract protected streams, bypass DRM, ads, authentication or geographic limits. If a service does not permit in-app playback, the UI shows **Open in YouTube** / **Open in Spotify** / **Watch here** (official embed) instead of a fake Play button.

---

## Table of contents

1. [Features](#features)
2. [Supported Android versions](#supported-android-versions)
3. [Architecture](#architecture)
4. [Building locally](#building-locally)
5. [GitHub Actions builds](#github-actions-builds)
6. [API configuration](#api-configuration)
7. [Google Sign-In setup](#google-sign-in-setup)
8. [Spotify setup](#spotify-setup)
9. [YouTube setup](#youtube-setup)
10. [Release signing](#release-signing)
11. [Permissions](#permissions)
12. [Provider capabilities and limitations](#provider-capabilities-and-limitations)
13. [Offline limitations](#offline-limitations)
14. [Privacy](#privacy)
15. [Troubleshooting](#troubleshooting)
16. [Getting the APK from GitHub Actions](#getting-the-apk-from-github-actions)

---

## Features

| Area | What you get |
| --- | --- |
| **Home 2.0** | Greeting, quick local actions, Continue listening, Recently played, Recently added, Favorites, Because you listened, Music Shorts teaser, Spotify new releases / your playlists / top tracks (when signed in), local albums/artists. Sections appear only when they have real data. |
| **Local music** | MediaStore scan: title, artist, album, genre, year, track number, duration, artwork, folders. Play / pause / previous / next / seek / shuffle / repeat / favorite / playlist / queue. |
| **Search 2.0** | Debounced search across local + configured online providers. Tabs: All, Songs, Artists, Albums, Playlists, Videos, Shorts, Local, YouTube, Spotify. Recent history, suggestions, pagination, provider failures isolated. |
| **Library 2.0** | Songs · Albums · Artists · Playlists · Favorites · Recently played · Folders · Downloads · Offline. Sort, filter, list/grid, multi-select bulk (queue, playlist, unfavorite, delete local files with confirmation). |
| **Blaze Shorts** | Vertical music Shorts from the official YouTube Data API. Official IFrame embed on capable devices; Open in YouTube on low-end / data saver. Like, save, share, queue, playlist. Limited prefetch. |
| **Player** | Full-screen player (artwork, remaining time, seek, shuffle, repeat, favorite, queue, share, sleep timer 15/30/45/60/custom), mini player with swipe-to-stop, persisted queue, reorder. |
| **Accounts** | Welcome, Sign in, Create account, Forgot password, Account, Sign out. Identity is **Google Sign-In** (no invented password database). Spotify PKCE sign-in for user playlists / top tracks. |
| **Appearance** | Light / Dark / System, accents (Green, Emerald, Mint, Lime, Teal, Blue, Purple, Orange, Red), dynamic accent (Android 12+), rounded corners, compact lists, artwork/artist/album/duration/badge, mini player, background, navigation labels, system bars, large artwork, artwork-tinted player background. |
| **Performance** | Performance / low-end mode (automatic on old/low-RAM devices), data saver, reduced effects, bounded caches. |
| **Background playback** | Foreground `MediaPlayer` service, `MediaBrowserServiceCompat` (Android Auto / media browsers), `MediaSessionCompat`, MediaStyle notification, lock-screen / headset / Bluetooth controls. |
| **Settings** | Appearance, Player, Playback, Performance, Storage, Accounts, Providers, Library layout, Downloads policy, Notifications, Audio, Accessibility, About, Privacy, Licenses. |
| **Offline** | Local files, favorites, playlists, recently played, cached metadata, restored queue. YouTube/Spotify audio is **not** downloaded (not permitted). |

## Supported Android versions

BlazeMuzix runs on Android 4.4.2 (API 19) through Android 16 (API 36) and newer.

| Capability | Android 4.4 – 5.x | Android 6 – 7 | Android 8 – 12 | Android 13+ |
| --- | --- | --- | --- | --- |
| Local music, library, player | Yes | Yes | Yes | Yes (`READ_MEDIA_AUDIO`) |
| Background playback + notification controls | Yes (legacy `MediaSessionCompat`) | Yes | Yes (foreground service + channel) | Yes (`mediaPlayback` FGS type, notification permission) |
| Google Sign-In | If Play services present | Yes | Yes | Yes |
| Status / navigation bar color options | Not available (setting disabled) | Yes | Yes | Yes |
| Dynamic (wallpaper) accent | No | No | Android 12+ only | Yes |
| MediaStore delete confirmation | Direct delete | Direct delete | Android 11+ system sheet | Yes |

Every AndroidX dependency was chosen at a version that still supports API 19. Java 8+ library features are desugared, and legacy multidex is enabled for API < 21.

## Architecture

```
UI (Activities / Fragments / Adapters / ViewModels)
        │  LiveData<UiState>          PlayerController (LiveData<PlayerState>)
        ▼                                     ▲
Repositories  DiscoveryRepository  LibraryRepository       PlaybackService
        │                 │                     (MediaBrowserServiceCompat)
        ▼                 ▼
Providers   MusicProvider ── LocalMusicProvider · YouTubeProvider · SpotifyProvider
        │                   ProviderManager / ProviderCapability / ProviderAuthentication
        ▼
Network / Storage   HttpClient · ResponseCache · BlazeDatabase (SQLite v2 + migrations) · SecureStore · AppPreferences · Glide
Auth                GoogleAuth (play-services-auth) · SpotifyAuth (Authorization Code + PKCE)
```

- **`MusicProvider`** is the single abstraction (`search`, `homeSections`, `shorts`, `collectionItems`, `capabilities`). Add a service by implementing it and registering in `ProviderRegistry`.
- **`MediaItem`** (also `UnifiedTrack` / `UnifiedAlbum` / `UnifiedArtist` / `UnifiedPlaylist` / `UnifiedVideo`) always carries its `Source` and a `Playback` capability (`DIRECT`, `EMBEDDED`, `EXTERNAL`, `NONE`).
- Tokens live in **`SecureStore`**: Android Keystore AES on API 23+, device-private AES on API 19–22. Tokens and secrets are never logged.
- **Playback** uses `android.media.MediaPlayer` in a foreground `MediaBrowserServiceCompat` with `MediaSessionCompat`.
- **UI state** is always one of `Loading / Success / Empty / Error / Offline`. One provider failing does not disable local music or other providers.

## Building locally

Requirements: JDK 17 (or 21) and the Android SDK (platform 36, build-tools 35.0.0). Android Studio is **not** required.

```bash
git clone https://github.com/vladgaming166-prog/BlazeMusic.git
cd BlazeMusic

cp local.properties.example local.properties
# edit local.properties → sdk.dir=… and optional API credentials

./gradlew testDebugUnitTest
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

Without credentials the app still builds and runs: local music, library and player work fully. Online screens show an honest **not configured** state. Cursor / GitHub Actions produce the APKs; you only create third-party developer credentials in Google/Spotify consoles when you want those catalogs.

## GitHub Actions builds

`.github/workflows/build.yml` runs on every push and pull request to `main`, and via **workflow_dispatch**. It checks out, sets up JDK 17 and the Android SDK, restores the Gradle cache, reports which secrets are present (names only), runs tests, lint (non-blocking), builds **debug** and **release** APKs, and uploads artifacts.

`.github/workflows/release.yml` runs on tags `v*` (for example `v2.0.0`) and produces the same APKs with longer artifact retention.

### GitHub Secrets / Variables

| Name | Secret or Variable | Purpose |
| --- | --- | --- |
| `YOUTUBE_API_KEY` | Either (public API key) | YouTube Data API v3 |
| `SPOTIFY_CLIENT_ID` | Secret | Spotify app client id |
| `SPOTIFY_CLIENT_SECRET` | Secret | Optional client-credentials catalog fallback. **Do not** treat this as safe inside an APK. |
| `SPOTIFY_REDIRECT_URI` | Secret (optional) | Defaults to `blazemuzix://callback` |
| `GOOGLE_WEB_CLIENT_ID` | Either | Google Sign-In OpenID web client |
| `RELEASE_KEYSTORE_BASE64` | Secret | Base64-encoded release keystore |
| `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | Secret | Signing |

Fork PRs do not receive secrets; they produce a working local-music APK with providers marked not configured.

## API configuration

Secrets are **never** committed. They are read at build time, in this order, and compiled into `BuildConfig`:

1. Environment variables (GitHub Actions)
2. Gradle `-P` properties
3. `local.properties` (git-ignored; see `local.properties.example` and `.env.example`)
4. Empty → honest *not configured* UI

An APK built without credentials will always show "not configured" until you add keys and rebuild.

## YouTube setup

1. Google Cloud Console → enable **YouTube Data API v3** → create an API key.
2. Restrict the key to that API (and optionally your package + SHA-1).
3. Set `YOUTUBE_API_KEY`.

Default quota is 10,000 units/day (search = 100). BlazeMuzix caches responses. Quota exceeded, private/deleted/restricted videos and network errors surface as states — the app does not crash.

**Playback:** official YouTube IFrame embed ("Watch here") or **Open in YouTube** / **YouTube Music**. Streams are never extracted. Downloads are not offered.

## Spotify setup

1. [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) → create an app.
2. Add redirect URI **`blazemuzix://callback`** (or the value of `SPOTIFY_REDIRECT_URI`).
3. Set `SPOTIFY_CLIENT_ID`. Optionally `SPOTIFY_CLIENT_SECRET` for unsigned catalog browsing (Client Credentials). User playlists and top tracks require **Sign in to Spotify** (Authorization Code + PKCE). The code verifier never leaves the device.

**Playback:** official 30-second preview URL when Spotify provides one; otherwise **Open in Spotify**. Full-length playback requires the Spotify app/account/subscription. Audio is never ripped.

## Google Sign-In setup

1. Google Cloud Console → OAuth client of type **Web application**. Copy the client id into `GOOGLE_WEB_CLIENT_ID`.
2. Also create an **Android** OAuth client with package `com.blazemuzix.app` (and `.debug` for debug APKs) and the SHA-1 of the signing key.
3. Enable Google Sign-In. There is **no** BlazeMuzix password: Create account / Forgot password open Google's official flows.

If Play services are missing or the web client id is unset, the app does not crash. Sign-In still requests public profile/email without an ID token when the web client is missing, and Settings explains the gap.

## Release signing

Release builds are minified with R8. If `KEYSTORE_FILE` / passwords are set, that keystore is used; otherwise CI signs with the **debug key** so an installable APK is still produced (not for store distribution).

```bash
keytool -genkeypair -v -keystore release.jks -alias blazemuzix -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # paste into RELEASE_KEYSTORE_BASE64
```

Never commit `*.jks`, `*.keystore` or `local.properties`.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Official provider APIs, artwork, offline detection |
| `READ_EXTERNAL_STORAGE` (≤ 12) / `READ_MEDIA_AUDIO` (13+) | MediaStore local music |
| `WRITE_EXTERNAL_STORAGE` (≤ 9) | Deleting the user's own files on old APIs after confirmation |
| `WAKE_LOCK` | Screen-off playback |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback |
| `POST_NOTIFICATIONS` (13+) | Media notification |

No ads, location, contacts, camera, microphone or invented account permissions.

## Provider capabilities and limitations

| Source | Search / browse | Playback in BlazeMuzix | Offline download | Notes |
| --- | --- | --- | --- | --- |
| **This device** | Songs, albums, artists, folders | Full playback, background, lock-screen | Already offline | Files in *Download* are tagged DOWNLOADED |
| **YouTube** | Videos, playlists, channels, Shorts (Data API v3) | Official embed or Open in YouTube | Not permitted | Ads/availability governed by YouTube |
| **Spotify** | Tracks, albums, artists, playlists | 30s official preview or Open in Spotify | Not permitted | User library requires PKCE sign-in |

Search pagination follows each API (Spotify search offset cap 1,000; YouTube `nextPageToken`). Quota / 429 / invalid credentials are typed errors, not crashes.

## Offline limitations

Works offline: local files, favorites, playlists, recently played, search history, settings, cached metadata, last queue. Does **not** work offline: YouTube/Spotify catalog, Shorts video, Spotify previews that were not already started. BlazeMuzix will not implement DRM bypass or YouTube-to-MP3.

## Privacy

- No ads, advertising SDKs, analytics, crash reporters or trackers.
- Library data stays on device (SQLite / SharedPreferences).
- Auth tokens in `SecureStore`. Passwords, tokens and API secrets are never logged.
- Network traffic is only to providers you configured and the artwork URLs they return.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| "YouTube is not configured" | Set `YOUTUBE_API_KEY` and rebuild. Check Actions log **Report provider configuration**. |
| "Spotify is not configured" | Set `SPOTIFY_CLIENT_ID` (and sign in, or also set the optional secret) and rebuild. Register `blazemuzix://callback`. |
| Google Sign-In "misconfigured" | Add `GOOGLE_WEB_CLIENT_ID`, register Android SHA-1, wait for Google's propagation. |
| No local music | Grant storage / music permission, copy audio files, tap Rescan. |
| Release APK signed with debug key | Provide `RELEASE_KEYSTORE_*` secrets. |
| Provider limit reached | YouTube quota or Spotify 429 — wait and retry. Local music still works. |
| Startup crash (debug) | `adb logcat -d -s BlazeMuzixCrash AndroidRuntime` and `files/crash/last-crash.txt`. |

## Getting the APK from GitHub Actions

1. **Actions** → **Build BlazeMuzix APK** → latest successful run (or **Run workflow**).
2. Download `BlazeMuzix-debug` or `BlazeMuzix-release`.
3. Unzip, install (unknown sources). Debug id is `com.blazemuzix.app.debug`.

## License

BlazeMuzix source code is provided under the MIT License. Third-party libraries keep their own licenses (*Settings → Open-source licenses*). YouTube is a trademark of Google LLC; Spotify is a trademark of Spotify AB. BlazeMuzix is not affiliated with or endorsed by either company.
