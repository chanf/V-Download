# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

V-Download is an Android video downloader app ("视频下载神器") built with Kotlin + Jetpack Compose + Material 3. It downloads videos from TikTok, Douyin, Instagram, YouTube, Xiaohongshu, X/Twitter, and Bilibili, saving them to `DCIM/v-down`. The app also supports video deduplication (via Media3 Transformer) and audio transcription (via ASR/LLM).

## Build Commands

```bash
# Compile (quick check)
./gradlew :app:compileDebugKotlin

# Unit tests
./gradlew :app:testDebugUnitTest

# Release APK
./gradlew :app:assembleRelease

# Install to device
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release signing requires `keystore.properties` in the project root with `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_FILE`, `STORE_PASSWORD`.

## Architecture

Single-module, single-Activity architecture. No DI framework — manual instantiation.

### Key Files

| File | Role |
|---|---|
| `MainActivity.kt` | Entry point. Handles share intents, URL extraction/normalization, permissions. |
| `ui/VDownloadApp.kt` | Main Compose UI. 4 tabs: Download, Dedup, Transcript, Settings. |
| `ui/CookieImportViewModel.kt` | Monolithic ViewModel (~2500 lines) coordinating all features. |
| `download/VideoDownloadRepository.kt` | Core download engine (~3200 lines). Platform-specific page parsing, parallel downloads, cookie injection. |
| `dedup/VideoDedupRepository.kt` | Video dedup via Media3 Transformer. Speed adjust, trim, PTS jitter, cover overlay. |
| `cookie/` | Room database for cookies: `AppDatabase`, `CookieEntity`, `CookieDao`, `NetscapeCookieParser`, `CookieImportRepository`, `VideoCookieSources`. |

### Data Flow

1. User shares/pastes URL → `MainActivity` extracts and normalizes it
2. `CookieImportViewModel` delegates to `VideoDownloadRepository.downloadVideo()`
3. `resolveSourceUrlForDownload()` routes to platform-specific resolver (e.g. `resolveDouyinSourceUrl`)
4. Resolver fetches HTML page with cookies, extracts video URL via regex patterns
5. Downloads via `HttpURLConnection` (single-thread or parallel segments for files >4MB)
6. Saves to `DCIM/v-down` via MediaStore (Android 10+) or legacy file API
7. Progress/results flow back through ViewModel state to Compose UI

### Cookie System

- **Import**: User imports Netscape-format `cookies.txt` (exported from Chrome extension). `NetscapeCookieParser` parses tab-separated fields (domain, includeSubDomains, path, secure, expires, name, value).
- **Storage**: Room database (`cookies` table). Unique index on `(domain, path, name)`. Upsert strategy — new imports merge with existing cookies.
- **Filtering**: `VideoCookieSources` maps domain suffixes to video sources (TikTok, Douyin, YouTube, etc.). Only cookies matching video domains are kept.
- **Request injection**: `buildCookieHeader(url)` queries valid (non-expired) cookies from Room, filters by domain+path+secure matching, builds `Cookie` header.
- **Runtime capture**: `persistResponseCookies()` captures `Set-Cookie` headers from every HTTP response and stores them in Room, enabling session cookies to persist across requests.

### Platform Resolver Pattern

Each platform resolver in `VideoDownloadRepository` follows the same pattern:
1. Identify the platform from URL hostname
2. Extract item ID from URL (or follow redirect to get it)
3. Fetch the page HTML with appropriate UA/Referer/Cookies
4. Extract video URL candidates via regex patterns (multiple pattern sets for escaped/JSON variants)
5. Normalize candidates (e.g. `playwm` → `play` for watermark removal, `line` parameter variants for Douyin)
6. Rank candidates by watermark penalty + quality score
7. Fall back to platform API if page parsing fails

### Video Dedup (Media3 Transformer)

Uses `Transformer` from Android Media3. Operations: speed adjustment, start/end trim, PTS jitter, cover frame overlay, ending video append. Seed-based `Random` for reproducibility. Config via `DedupFeatureConfigRepository` (SharedPreferences).

## Conventions

- All HTTP is done via raw `HttpURLConnection` — no OkHttp/Retrofit
- Error messages are in Chinese, targeting end-user readability
- Diagnostics use `android.util.Log.i` with tag `"VDownDownload"`
- Version: increment `versionCode` + patch segment of `versionName` each release
- Git commit messages are in Chinese

## QA / Regression Scripts

- **Download automation**: `qa/download_automation/run_download_batch.sh --serial <device> [--dry-run] [--limit N]`
- **Dedup validation**: `qa/dedup_validation/run_dedup_eval.sh "source.mp4" "dedup.mp4"`
- **ASR regression**: `./scripts/regression_say_e2e.sh`
