# Manga Offline Downloader — Specification

## 1. Overview

An Android app for downloading manga chapters (single chapters or entire series) from websites and reading them offline. Downloads are organized into **Books** (one per series). The reader moves seamlessly from one chapter to the next, and the user controls the image quality/size of downloaded pages.

The app does **not** save raw webpages. Manga chapter pages are mostly ads, scripts and lazy‑loaded images that break offline. Instead the app extracts the ordered list of chapter images and stores them in a portable archive, displayed in a native reader.

### 1.1 Goals
- Download a single chapter, a selection, or an entire series in one action.
- Organize downloads into Books with cover, title, source URL and ordered chapter list.
- Continuous vertical reader with "next chapter" navigation, fully offline.
- User‑selectable download quality (Original / High / Medium / Low), with the option to re‑compress existing downloads.
- Work on most manga sites with zero configuration; allow small per‑site rules where needed.
- Store files in a portable format (CBZ) in a user‑chosen folder.

### 1.2 Non‑goals (v1)
- iOS / desktop versions.
- Account sync, cloud backup.
- Bypassing paywalls or DRM. The app only saves what the user can already view in the browser.
- Desktop browser extension. Firefox integration is via Android's Share sheet only.

### 1.3 Reference site
Initial development and testing target: **Asura Scans** (`asurascans.com`).

| Item | Observed format |
|---|---|
| Series page | `https://asurascans.com/comics/{slug}-{id}` (e.g. `/comics/reaper-of-the-drifting-moon-11fe7da3`) |
| Chapter link | `/comics/{slug}-{id}/chapter/{number}` (numbers may start at 0) |
| Chapter list order | Descending by chapter number (newest first). Labels may include extras like `{S3 END}`; dates are not reliable for ordering |
| Chapter images | `https://cdn.asurascans.com/asura-images/chapters/{slug}/{chapter}/{hash}.webp?v={ts}` |
| Image identification | `<img>` with `alt` containing `Page N - Chapter X - ...` |
| Cover | `https://cdn.asurascans.com/asura-images/covers/{slug}.{hash}-400.webp` |
| Chapter nav | Prev/Next links on chapter page |

---

## 2. Platform & Tech Stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 (dynamic color, dark theme default) |
| Min / target SDK | minSdk 26 (Android 8.0), targetSdk latest stable |
| Architecture | MVVM + unidirectional data flow; single‑activity, Compose Navigation |
| DI | Hilt |
| Database | Room (SQLite) |
| Background work | WorkManager (foreground service type `dataSync` with progress notification) |
| Networking | OkHttp (shares cookies with WebView via a `CookieJar` bridging `android.webkit.CookieManager`) |
| Browser / extraction | Android `WebView` with injected JavaScript |
| Image decode/encode | Android `ImageDecoder` / `BitmapFactory` + `Bitmap.compress` (WebP lossy / JPEG) |
| Image display | Coil 3 (with subsampling for very tall images) |
| Archive | `java.util.zip` → CBZ |
| Storage access | Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`, persisted URI permission) |
| Settings | Jetpack DataStore |
| Site rules | JSON bundled in assets + user‑editable overrides |

---

## 3. Core Concepts & Data Model

### 3.1 Entities (Room)

**Source** — a website.
| Field | Type | Notes |
|---|---|---|
| id | Long PK | |
| host | String | e.g. `asurascans.com` |
| ruleJson | String? | Per‑site rule (see §6). Null → generic rule |

**Book** — a manga series.
| Field | Type | Notes |
|---|---|---|
| id | Long PK | |
| title | String | Editable |
| sourceUrl | String | Series homepage URL (unique) |
| sourceHost | String | |
| coverPath | String? | Local cover file |
| qualityOverride | Quality? | Null → global default |
| folderName | String | Sanitized directory name |
| addedAt / updatedAt | Instant | |
| lastCheckedAt | Instant? | Last "check for new chapters" |
| category | String? | Optional user shelf/tag |

**Chapter**
| Field | Type | Notes |
|---|---|---|
| id | Long PK | |
| bookId | FK → Book | cascade delete |
| url | String | unique per book |
| number | Double | Parsed chapter number (supports 10.5) |
| label | String | Display text, e.g. "Chapter 120 {S3 END}" |
| sortIndex | Int | Ascending reading order |
| status | enum | `NOT_DOWNLOADED`, `QUEUED`, `DOWNLOADING`, `DOWNLOADED`, `FAILED` |
| pageCount | Int? | |
| filePath | String? | CBZ document URI |
| sizeBytes | Long? | |
| quality | Quality? | Quality it was saved at |
| readProgress | Float | 0..1 scroll position |
| lastPageRead | Int | |
| isRead | Boolean | |

**DownloadTask**: a queue row per chapter (`chapterId`, `attempts`, `error`, `createdAt`).

### 3.2 Quality presets

| Preset | Max width | Format | Quality |
|---|---|---|---|
| Original | unchanged | unchanged bytes (no re‑encode) | — |
| High | 1600 px | WebP lossy | 90 |
| Medium | 1200 px | WebP lossy | 80 |
| Low | 900 px | WebP lossy | 65 |
| Custom | user | WebP / JPEG | user (40–100) |

- Images narrower than the max width are never upscaled. Those already smaller in bytes than the re‑encoded output are kept as‑is.
- Very tall strip images (height > 8000 px) are processed in tiles, using `BitmapRegionDecoder`, to avoid OOM.
- Quality resolution order: per‑download choice → Book override → global default.

---

## 4. Features

### 4.1 Library (home screen)
- Grid (default) or list of Books: cover, title, `downloaded/total` chapters, unread badge.
- Sort by: recently read, recently added, title, recently updated.
- Filter: categories, "has unread", "has new chapters".
- Long‑press a Book to rename, change cover, set category, set quality override, check for updates, or delete (with an option to keep files).
- Search bar filters by title.

### 4.2 In‑app browser
- Full WebView browser with address bar, back/forward, reload, bookmarks of sources.
- JavaScript, DOM storage and cookies enabled; cookies shared with the download engine so Cloudflare/age gates passed in the browser apply to downloads.
- Desktop/mobile user‑agent toggle.
- Basic ad/pop‑up suppression: block new‑window requests by default and offer an optional host blocklist.
- **Context‑aware floating action button:**
  - On a **series page** (chapter links detected): **Add as Book / Update Book** opens the Chapter Picker (§4.3).
  - On a **chapter page** (page images detected): **Download this chapter** asks whether to add it to an existing Book or create one from the series link on the page.
  - Neither detected: the button is hidden. The overflow menu offers "Try detect anyway".

### 4.3 Chapter Picker
- Shows the detected Book title and cover, and the chapter list in reading order (oldest first, toggle to reverse).
- Each row: label, status icon (downloaded / queued / new).
- Actions: **Select all**, **Select new only**, range select (tap first, long‑press last), **Download selected**.
- Quality dropdown (defaults to Book / global setting).
- Confirming creates or updates the Book and queues downloads.

### 4.4 Download manager
- Queue screen: active, queued and failed items with per‑chapter progress (`page x / n`) and overall progress.
- Pause / resume all, cancel, retry failed, reorder.
- Runs in WorkManager with a foreground notification showing progress. Survives app close and resumes after reboot.
- Settings: concurrent chapters (default 1), concurrent images per chapter (default 3), Wi‑Fi only toggle, delay between requests (default 300 ms, polite to sites).
- Retry policy: 3 attempts per image with exponential backoff. After that the chapter is marked `FAILED` with the error message.

### 4.5 Book detail screen
- Header: cover, title, source host, chapter counts, storage used, **Resume reading** button.
- Chapter list in reading order with status, read state and size.
- Actions: download all / remaining, check for new chapters, re‑compress, delete selected chapters, mark read/unread, open source in browser.
- Pull‑to‑refresh runs "check for new chapters".

### 4.6 Reader
- **Vertical continuous scroll** (webtoon mode, default): pages stacked with no gaps, matching the website.
- Optional **paged mode** (left‑to‑right / right‑to‑left) for traditional manga.
- Tap zones: center toggles the toolbar; top/bottom scroll by one screen.
- Pinch‑zoom, double‑tap zoom.
- Toolbar: chapter title, page indicator (`7 / 14`), progress slider, prev/next chapter buttons, reader settings.
- **Chapter transitions:**
  - The end of a chapter shows a "Chapter X finished, next: Chapter Y →" card.
  - Option **Auto‑continue**: scrolling past the card loads the next chapter seamlessly (infinite scroll across chapters).
  - If the next chapter isn't downloaded: offer **Download & open**, or **Read online** in the in‑app browser.
  - Previous chapter is available the same way at the top.
- Next/previous is determined by `Chapter.sortIndex`, never by the website's links, so it works offline.
- Progress saved continuously; chapters are marked read when the end card is reached.
- Settings: background color (black/white/gray), keep screen on, fullscreen/immersive, volume‑key scrolling, brightness filter, image fit width.

### 4.7 Update checking
- Manual "Check for new chapters" per Book or for the whole library.
- Optional periodic background check (off by default; 12 h / 24 h / weekly), which notifies the user of new chapters.
- Optional "auto‑download new chapters" per Book.

### 4.8 Re‑compress existing downloads
- From Book detail or Settings → Storage: pick the target quality, then re‑encode the selected chapters' CBZs in a background job.
- Shows the expected and actual space saved.
- Original files cannot be recovered after re‑compression; the app warns about this first.

### 4.9 Firefox / share integration
- The app registers an intent filter for `ACTION_SEND` (text/plain URLs) and `ACTION_VIEW` for http/https links.
- Sharing a link from Firefox for Android (Share → this app) opens it in the in‑app browser with auto‑detection running.
- No Firefox extension required.

### 4.10 Import / export
- **Rescan library folder**: rebuilds the database from the folder structure and `book.json` files (§5). This supports reinstalling the app or moving to a new phone.
- Export/import a backup JSON (Books, chapters, read progress, settings, site rules).

---

## 5. Storage Layout

The user picks a root folder at first launch (via SAF). The layout is:

```
<Root>/
  <Book Title>/
    book.json                 # metadata: title, sourceUrl, chapter list, read progress
    cover.webp
    Chapter 000.cbz
    Chapter 001.cbz
    ...
    Chapter 120 - S3 END.cbz
```

- CBZ contents: `001.webp`, `002.webp`, ... (zero‑padded, reading order), plus `ComicInfo.xml` (series, number, title, page count, source URL) for compatibility with other readers (Mihon, Perfect Viewer, Kavita).
- Chapters are stored uncompressed (`STORED`) inside the zip, since images are already compressed and this keeps writes fast.
- File names are sanitized (strip `/\:*?"<>|`, collapse whitespace, max 100 chars). Chapter numbers are zero‑padded to 3 digits (4 if any chapter ≥ 1000).
- Downloads write to app cache first, then move the finished CBZ into the root folder. A partially downloaded chapter never appears as a complete one.
- Covers and thumbnails for the library grid are cached in app‑private storage.

---

## 6. Extraction Engine

Extraction runs inside a WebView: either the visible browser or a hidden "headless" WebView for background downloads. It works on the fully rendered DOM, so it handles server‑rendered pages, JavaScript‑injected content and lazy loading the same way.

### 6.1 Page‑type detection
1. If the site rule matches the URL with `seriesUrlPattern` or `chapterUrlPattern`, use that.
2. Generic rule:
   - **Chapter page**: ≥ 3 images with natural width ≥ 400 px, stacked vertically in a common container.
   - **Series page**: ≥ 3 links whose href matches `/(chapter|ch|episode|ep)[-_/]?\d+/i`.

### 6.2 Series page → chapter list
1. Expand any "Show more"/"Load all" controls if the rule defines `expandSelector`.
2. Collect anchors matching `chapterLinkSelector` (rule) or the generic regex. Normalize to absolute URLs and dedupe.
3. Parse the chapter number from the URL (preferred) or label: `/chapter[\/-]?(\d+(?:\.\d+)?)/i`.
4. Sort **by number ascending**. Links without a number keep page order and are appended to the end. Assign `sortIndex`.
5. Title: rule `titleSelector` → `og:title` → `<h1>` → `<title>` (with the site name stripped).
6. Cover: rule `coverSelector` → `og:image` → first large image.

### 6.3 Chapter page → ordered image list
1. Wait for `DOMContentLoaded` plus a network‑idle period (no new requests for 1 s, 20 s timeout).
2. **Auto‑scroll** to the bottom in steps (viewport height every 150 ms) to trigger lazy loading, then wait for idle again.
3. Collect `<img>` elements from `imageContainerSelector` (rule) or the generic container. For each one, resolve the real URL in this order: `data-src`, `data-lazy-src`, `data-original`, the largest `srcset` candidate, then `src`. Ignore `data:` placeholders.
4. Filter by:
   - rule `imageFilter` (e.g. `alt` matches `/Page \d+/` or URL host matches `cdn.asurascans.com/asura-images/chapters/`), or
   - generic: natural width ≥ 400 px and not inside `header/footer/nav/aside`, excluding known ad hosts.
5. Order: by explicit page number if the rule provides `pageNumberFrom` (alt/URL), otherwise DOM order.
6. Dedupe by URL. Return `{ title, images: [url...], prevUrl, nextUrl }` to Kotlin via a `@JavascriptInterface` bridge.
7. If 0 images are found, the chapter is marked `FAILED: no images detected`. The user is offered "Open in browser" to diagnose or to download manually from the visible browser.

### 6.4 Image download
- Downloaded with OkHttp, not the WebView, so the app can stream, retry and re‑encode.
- Request headers: `Referer: <chapter URL>`, the WebView's `User-Agent`, `Accept: image/avif,image/webp,*/*`, and cookies from `CookieManager`.
- Responses are validated: HTTP 200, `Content-Type: image/*`, and decodable. Otherwise the image is retried.
- Quality processing (§3.2) is applied, then the file is written to the chapter's temp directory as `NNN.<ext>`.
- If an anti‑bot challenge is detected (HTTP 403/503 with challenge HTML), downloads pause and a notification asks the user to open the site in the in‑app browser once to pass the check, after which the queue resumes.

### 6.5 Site rules (JSON)

```json
{
  "host": "asurascans.com",
  "seriesUrlPattern": "^https://asurascans\\.com/comics/[^/]+$",
  "chapterUrlPattern": "^https://asurascans\\.com/comics/[^/]+/chapter/[\\d.]+$",
  "chapterLinkSelector": "a[href*='/chapter/']",
  "chapterNumberRegex": "/chapter/(\\d+(?:\\.\\d+)?)",
  "titleSelector": "h1",
  "coverSelector": "img[src*='/asura-images/covers/']",
  "imageContainerSelector": null,
  "imageFilter": { "urlContains": "/asura-images/chapters/", "altRegex": "Page \\d+" },
  "pageNumberFrom": { "alt": "Page (\\d+)" },
  "expandSelector": null,
  "requestDelayMs": 300
}
```

- Bundled rules ship in `assets/site_rules/*.json`.
- Users can view and edit rules, or add a new one, in Settings → Sites. A user rule overrides the bundled rule for that host.
- A **"Test rule"** button runs detection on the current browser page and previews the results (chapter count, first/last chapter, image count with thumbnails).
- Every rule field is optional; missing fields fall back to the generic rule.

---

## 7. Screens & Navigation

```
Bottom nav: Library | Browse | Downloads | Settings

Library ──► Book Detail ──► Reader
   │             └──► Chapter Picker (update/download more)
Browse (in‑app browser) ──► Chapter Picker ──► (queue) ──► Downloads
Downloads (queue)
Settings ──► General | Reader | Downloads | Quality | Storage | Sites | Backup | About
```

### Settings summary
- **General**: theme, library layout, default category.
- **Reader**: default mode, auto‑continue, background, tap zones, keep screen on.
- **Downloads**: concurrency, Wi‑Fi only, request delay, auto‑check interval, auto‑download new.
- **Quality**: global default preset, custom preset values.
- **Storage**: root folder, storage used per Book, clear cache, re‑compress, rescan folder.
- **Sites**: site rules list and editor, blocked ad hosts, user‑agent.
- **Backup**: export/import.

---

## 8. Error Handling & Edge Cases

| Case | Behavior |
|---|---|
| Network drop mid‑chapter | WorkManager retries; completed images are kept in temp and only missing ones are re‑downloaded |
| Site layout changed (0 images) | Chapter `FAILED`, suggest editing the site rule; generic fallback attempted first |
| Duplicate chapter numbers (e.g. notice posts) | Both kept; sorted by number, then by page order |
| Chapter removed from site | Local copy kept; marked "removed from source" on next update check |
| Root folder permission revoked | Banner prompting the user to re‑select the folder; downloads paused |
| Low storage (< 200 MB free) | Pause queue, notify |
| Book already exists (same source URL) | "Update Book" instead of creating a duplicate |
| Very large series (500+ chapters) | Chapter list paged/lazy; downloads queued in batches |

---

## 9. Performance Targets
- Reader opens a downloaded chapter in < 500 ms; smooth 60 fps scrolling on mid‑range devices.
- Reader preloads the next 3 pages and pre‑opens the next chapter's CBZ index near the end card.
- Memory: never decode a full‑resolution strip image larger than the screen width; use subsampling.
- A 14‑page chapter (like Asura chapter 135) downloads in < 15 s on a typical connection at default settings.

---

## 10. Permissions
- `INTERNET`, `ACCESS_NETWORK_STATE`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS` (Android 13+, requested on first download)
- `RECEIVE_BOOT_COMPLETED` (resume queue / periodic checks)
- No broad storage permission. The app uses SAF only.

---

## 11. Project Structure

```
app/
  src/main/java/.../
    data/
      db/            # Room entities, DAOs, database
      repo/          # BookRepository, ChapterRepository, DownloadRepository
      prefs/         # DataStore settings
      storage/       # SAF file manager, CBZ writer/reader, ComicInfo.xml
    extraction/
      SiteRule.kt, SiteRuleRepository.kt
      WebViewExtractor.kt      # hidden WebView + JS bridge
      js/extract.js            # injected script (series + chapter)
    download/
      DownloadWorker.kt, ImageDownloader.kt, ImageProcessor.kt (quality)
      UpdateCheckWorker.kt, RecompressWorker.kt
    net/             # OkHttp client, WebView CookieJar bridge
    ui/
      library/, book/, browser/, picker/, downloads/, reader/, settings/
      theme/
  src/main/assets/site_rules/asurascans.com.json
```

---

## 12. Milestones

1. **M1: Skeleton.** Compose app, navigation, Room schema, settings, SAF root folder picker.
2. **M2: Browser + detection.** In‑app WebView, JS extractor, Asura rule, Chapter Picker showing the chapter list.
3. **M3: Downloading.** WorkManager queue, OkHttp image downloads with cookies/referer, CBZ writer, notifications.
4. **M4: Reader.** Vertical reader from CBZ, progress saving, next/previous chapter with end card and auto‑continue.
5. **M5: Quality.** Presets, per‑Book override, re‑compress job.
6. **M6: Library polish.** Book detail, categories, update checking, share intent from Firefox.
7. **M7: Robustness.** Generic rule tuning, rule editor with "Test rule", anti‑bot pause/resume, backup/rescan.

### Acceptance test (reference site)
1. Share `https://asurascans.com/comics/reaper-of-the-drifting-moon-11fe7da3` from Firefox to the app.
2. Tap **Add as Book**. The picker lists Chapter 0 → Chapter 135 in ascending order, with the cover and title.
3. Select chapters 134–135 at **Medium** and download. Two CBZs appear in `<Root>/Reaper of the Drifting Moon/`, and Chapter 135 contains 14 images ≤ 1200 px wide.
4. Turn on airplane mode and open Chapter 134. Scrolling to the end shows "Next: Chapter 135" and opens it offline.
5. Re‑compress Chapter 134 to **Low**. The file size drops and it still reads correctly.

---

## 13. Legal / Usage Note
The app is a personal offline‑reading tool. It downloads only content the user can view in their own browser session, respects per‑site request delays, and includes no features for redistributing content.
