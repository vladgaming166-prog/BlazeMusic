# BlazeMuzix 2.1.0

**BlazeMuzix** is a lightweight, ad-free, Google-inspired music app for Android. Version **2.1.0** adds a real **email/password + Google** account system, **BlazeMuzix Cloud** (user-owned uploads, playlists, likes, comments, profiles) on **Supabase**, and keeps official **YouTube Data API v3** and **Spotify Web API** discovery. Local music still works with no account and no internet.

- Package: `com.blazemuzix.app` (debug APK: `com.blazemuzix.app.debug`)
- Version: **2.1.0** (versionCode **210**)
- Minimum Android version: **4.4 KitKat (API 19)** where technically possible
- Target / compile SDK: **Android 16 (API 36)**
- Language: Kotlin, traditional Android Views (Material Components)
- Backend: **Supabase** (Auth, Postgres + RLS, Storage). Firebase is not used.
- No advertisements, no analytics, no trackers
- **Never** put a service-role key, Firebase Admin SDK, or Spotify client secret that you cannot accept leaking into an APK

BlazeMuzix does **not** scrape providers, extract protected streams, bypass DRM, ads, authentication or geographic limits. If a service does not permit in-app playback, the UI shows **Open in YouTube** / **Open in Spotify** / **Watch here** (official embed) instead of a fake Play button.

---

## Table of contents

1. [Features](#features)
2. [What works without credentials](#what-works-without-credentials)
3. [Building locally](#building-locally)
4. [GitHub Actions](#github-actions)
5. [Google Cloud OAuth setup](#google-cloud-oauth-setup)
6. [BlazeMuzix Cloud / Supabase](#blazemuzix-cloud--supabase)
7. [YouTube setup](#youtube-setup)
8. [Spotify setup](#spotify-setup)
9. [Release signing](#release-signing)
10. [Permissions](#permissions)
11. [Provider capabilities](#provider-capabilities)
12. [Troubleshooting](#troubleshooting)

---

## Features

| Area | What you get |
| --- | --- |
| **Accounts** | Email/password create + sign in (Supabase Auth), username, profile photo, password strength, forgot password, persistent session, logout, delete account. Continue with Google (official play-services-auth). Local music never requires an account. |
| **BlazeMuzix Cloud** | Upload original audio (MP3/WAV/M4A/AAC/OGG) with cover, title, rights notice, progress, cancel, retry. Feed: new, trending, recommended, artists, playlists. Track page: play, like, comments, share, report, offline cache **for the uploader only**. |
| **Home** | Greeting / Welcome back, recently played, your playlists, Cloud, YouTube Shorts teaser, Spotify sections when configured, local library. |
| **Search** | Tabs: All, Songs, Artists, Albums, Playlists, Videos, Shorts, Local, YouTube, Spotify, BlazeMuzix Cloud, Users. Every result is badged with its source. |
| **Shorts** | Vertical YouTube Shorts via the official Data API + IFrame embed. User-owned Cloud shorts are listed separately and opened with the system player (not YouTube). |
| **Library** | Songs, albums, artists, playlists, likes, recently played, folders, downloads, offline, **Uploaded**. Local files work signed out. |
| **Player** | Play/pause/prev/next/seek/shuffle/repeat/queue/mini + full player, system volume, background `MediaSession` (notification / lock screen / headset where the OS allows). KitKat does not get modern FGS types. |
| **Spotify** | Official Web API search (artists, albums, tracks), metadata, artwork, Open in Spotify. PKCE sign-in for user library. No fake full-catalog playback. |
| **YouTube** | Official Data API search + Shorts discovery. Official embed or Open in YouTube. No downloads. |

---

## What works without credentials

| Feature | Without secrets | Needs configuration |
| --- | --- | --- |
| Local music, library, player, playlists, queue, appearance | Yes | — |
| Email/password, Cloud uploads, cloud likes/comments | Shows “BlazeMuzix Cloud is not configured” | `SUPABASE_URL` + `SUPABASE_ANON_KEY` + `supabase/schema.sql` |
| Google Sign-In | Profile/email may still prompt if Play services exist; ID token / Cloud exchange needs Web client | `GOOGLE_WEB_CLIENT_ID` + Android OAuth client + SHA-1 |
| YouTube search / Shorts | Honest not-configured / retry | `YOUTUBE_API_KEY` |
| Spotify search / Open in Spotify | Honest not-configured | `SPOTIFY_CLIENT_ID` (PKCE). Optional secret for unsigned catalog only |

Missing credentials **do not fail the Gradle build**.

---

## Building locally

Requirements: JDK 17 (or 21) and the Android SDK (platform 36, build-tools 35.0.0).

```bash
git clone https://github.com/vladgaming166-prog/BlazeMusic.git
cd BlazeMusic
cp local.properties.example local.properties
# set sdk.dir=… and optional keys (never commit this file)

./gradlew :app:checkConfig
./gradlew testDebugUnitTest
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Artifact names in GitHub Actions: **`BlazeMuzix-debug`**, **`BlazeMuzix-release`**, **`test-reports`**.

---

## GitHub Actions

`.github/workflows/build.yml` runs on push/PR to `main` and **workflow_dispatch**. It reports which secrets are set (names only), runs `:app:checkConfig`, unit tests, debug + release APKs, and uploads artifacts.

### GitHub Secrets

| Name | Required for | Notes |
| --- | --- | --- |
| `YOUTUBE_API_KEY` | YouTube / Shorts | Public API key |
| `SPOTIFY_CLIENT_ID` | Spotify catalog / PKCE | Public client id |
| `SPOTIFY_CLIENT_SECRET` | Optional unsigned catalog | Can be extracted from an APK; prefer PKCE |
| `SPOTIFY_REDIRECT_URI` | Optional | Default `blazemuzix://callback` |
| `GOOGLE_WEB_CLIENT_ID` | Google ID token + Cloud Google provider | **Web** OAuth client, not Android |
| `SUPABASE_URL` | Cloud auth + uploads | `https://YOUR_PROJECT.supabase.co` |
| `SUPABASE_ANON_KEY` | Cloud auth + uploads | Public anon key only |
| `RELEASE_KEYSTORE_BASE64` | Store-signed release | Optional; otherwise debug key |
| `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | Store-signed release | Optional |

Never add `SUPABASE_SERVICE_ROLE_KEY` (or any admin secret) to GitHub Actions Android jobs that inject `BuildConfig`.

---

## Google Cloud OAuth setup

You need **two** OAuth clients in the same Google Cloud project. Do not invent credentials.

### 1. Package name

| Build | Application id |
| --- | --- |
| Release | `com.blazemuzix.app` |
| Debug (`assembleDebug`) | `com.blazemuzix.app.debug` |

Register **both** if you use debug and release.

### 2. SHA-1

```bash
# Debug key (local)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# Release key
keytool -list -v -keystore /path/to/release.jks -alias YOUR_ALIAS
```

Copy the **SHA-1** fingerprint (colon-separated). The app also prints the SHA-1 of the installed build in Google Sign-In error 10 / 12500.

### 3. Android OAuth client

Google Cloud Console → APIs & Services → Credentials → **Create credentials → OAuth client ID → Android**.

| Field | Value |
| --- | --- |
| Package name | `com.blazemuzix.app` and a second client for `com.blazemuzix.app.debug` |
| SHA-1 | Debug and/or release certificate |

This client is **not** put in the APK. Google Play services uses it to verify the app.

### 4. Web OAuth client

Create **OAuth client ID → Web application**. Copy the client id (looks like `….apps.googleusercontent.com`).

| Field | Where it goes |
| --- | --- |
| Web client ID | `GOOGLE_WEB_CLIENT_ID` in `local.properties` or GitHub Secret → `BuildConfig.GOOGLE_WEB_CLIENT_ID` → Google Sign-In `requestIdToken` **and** Supabase Google provider Client IDs |

This is `serverClientId`. **Never** put the Web client *secret* in the APK.

### 5. Credential Manager

`androidx.credentials` / Credential Manager 1.6+ requires **minSdk 23**. BlazeMuzix keeps **minSdk 19** and uses official **play-services-auth 20.7.0** (last line that still supports KitKat). That is the same Google identity; it is not a fake login.

### Common errors

| Code | Meaning |
| --- | --- |
| **10 / DEVELOPER_ERROR** | Package name or SHA-1 on the Android client does not match this APK, or Web client id is wrong. |
| **12500** | Sign-In failed; usually Android client / SHA-1 / support email on the OAuth consent screen. |
| **12501** | User cancelled. |
| Network | Device offline or Play services cannot reach Google. |

---

## BlazeMuzix Cloud / Supabase

Firebase is not in this project. Supabase was chosen because the app already speaks HTTPS via `HttpURLConnection` on API 19, RLS matches the requested schema, and the **anon key is public** (like a Firebase web API key). The **service_role** key must stay on the server.

### One-time project setup

1. Create a project at [supabase.com](https://supabase.com).
2. Project Settings → API: copy **Project URL** and **anon public** key.
3. Set `SUPABASE_URL` and `SUPABASE_ANON_KEY` in `local.properties` or GitHub Secrets.
4. SQL Editor → paste and run **`supabase/schema.sql`** (tables, RLS, storage buckets `audio` / `covers` / `avatars` / `videos`, `delete_own_account`, play-count).
5. Authentication → Providers:
   - Enable **Email**.
   - Enable **Google** and paste the **same Web client ID** as `GOOGLE_WEB_CLIENT_ID` (and the Web client secret **only** in the Supabase dashboard, never in the app).
6. Authentication → URL configuration: add a Site URL if you use email confirmation (the Android app still works; the user confirms then signs in).
7. Rebuild the APK.

### Database (RLS)

Owners can only insert/update/delete their own `profiles`, `tracks`, `playlists`, likes, comments, follows, reports. Public tracks/playlists are readable; private rows are owner-only. Storage writes are limited to `{auth.uid()}/…` in each bucket.

### Storage

Public buckets for playback of user-owned uploads (unguessable UUIDs). Privacy of listings is enforced by RLS on `tracks.visibility`. Do not upload audio you do not have the right to distribute.

---

## YouTube setup

1. Enable **YouTube Data API v3**.
2. Create an API key. Restrict it to that API (and optionally package + SHA-1).
3. Set `YOUTUBE_API_KEY`.

Playback is official IFrame embed or Open in YouTube. No downloads, no unofficial YouTube Music catalog.

---

## Spotify setup

1. [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) → app.
2. Redirect URI **`blazemuzix://callback`**.
3. Set `SPOTIFY_CLIENT_ID`. PKCE does **not** need a secret.
4. Optional `SPOTIFY_CLIENT_SECRET` only for unsigned catalog search. Prefer leaving it empty in store APKs.

Playback: official 30s preview when Spotify returns one, otherwise **Open in Spotify**. The UI never claims full in-app Spotify playback.

---

## Release signing

Release builds are minified with R8. Without a keystore, CI signs with the debug key so an APK still installs (not for Play Store).

```bash
keytool -genkeypair -v -keystore release.jks -alias blazemuzix -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks
```

Never commit `*.jks`, `*.keystore`, `local.properties`, or service-role keys.

---

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Official APIs, Cloud, artwork |
| `READ_EXTERNAL_STORAGE` (≤ 12) / `READ_MEDIA_AUDIO` (13+) | Local music |
| `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback |
| `POST_NOTIFICATIONS` (13+) | Media notification |

---

## Provider capabilities

| Source | Search | In-app playback | Offline |
| --- | --- | --- | --- |
| This device | Yes | Full | Already local |
| BlazeMuzix Cloud | Yes when configured | Direct HTTPS of **user-uploaded** audio | Uploader can cache own tracks |
| YouTube | Data API | Official embed / Open in YouTube | Not permitted |
| Spotify | Web API | Official preview / Open in Spotify | Not permitted |

---

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| Cloud “not configured” | Set `SUPABASE_URL` + `SUPABASE_ANON_KEY`, run `supabase/schema.sql`, rebuild. |
| Email sign-in fails | Enable Email provider; confirm the inbox if confirmation is on. |
| Google error 10 / 12500 | Package (`com.blazemuzix.app` vs `.debug`) and SHA-1 on the **Android** client; Web client id in `GOOGLE_WEB_CLIENT_ID`. |
| Google works but Cloud rejects the token | Enable Google in Supabase with the **same Web client ID**. |
| YouTube / Shorts empty | Set `YOUTUBE_API_KEY` and rebuild. |
| Spotify missing | Set `SPOTIFY_CLIENT_ID`, register `blazemuzix://callback`. |
| Upload fails | Signed-in Cloud session, internet, file ≤ 25 MB, schema + storage policies applied. |
| No local music | Grant music permission, Rescan. |

## License

BlazeMuzix source is MIT. YouTube is a trademark of Google LLC; Spotify is a trademark of Spotify AB. BlazeMuzix is not affiliated with or endorsed by either company.
