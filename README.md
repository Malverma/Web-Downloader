# Web Downloader

An Android app for downloading manga chapters, or entire series, from websites and reading them offline.

- **Books**: open a manga's homepage in the built-in browser and tap **Add as Book**. You get the full chapter list and can pick which chapters to download: tap rows, use All / None / Not downloaded, or **hold and drag** to select many at once (the list auto-scrolls at the edges).
- **Downloads tab**: one folder per manga with its queued, active, failed and finished chapters, matching the folders on disk.
- **Offline reader**: vertical continuous scroll, like the website. The end of each chapter has a **Next chapter** card, with optional auto-continue. Reading progress is saved.
- **Quality**: Original / High (1600px) / Medium (1200px) / Low (900px) / Custom. Set it globally, per book, or per download. Existing downloads can be re-compressed later.
- **Portable files**: each chapter is saved as a `.cbz` (with `ComicInfo.xml`) in `<Library>/<Book Title>/`. Other readers such as Mihon, Perfect Viewer and Kavita can open these files.
- **Firefox**: in Firefox for Android, use **Share → Web Downloader** on any manga page to open it in the app.
- **Search**: the **Search** tab searches Asura Scans, Arena Scans and Flame Comics together. Filter by genre (pick several, e.g. Action + Fantasy), minimum rating, and minimum chapter count, and sort by relevance, rating, chapters or title. Tap a result to pick chapters to download.
- **Site rules**: sites work through automatic detection. A small JSON rule (Settings → Sites) can make detection exact. Rules for `asurascans.com`, `arenascan.com` and `flamecomics.xyz` are built in.

See [`spec.md`](spec.md) for the full design.

## Install

Download **[WebDownloader.apk](https://github.com/Malverma/Web-Downloader/releases/latest/download/WebDownloader.apk)** from the latest release and open it on your phone. Android will ask you to allow installs from your browser or file manager. Requires Android 8.0+.

To publish a new release, bump `versionCode`/`versionName` in `app/build.gradle.kts` and push a tag:

```sh
git tag v0.1.1 && git push origin v0.1.1
```

The **Release APK** workflow builds a signed APK and attaches it to a GitHub Release. Every build uses the same key (`app/release.keystore`), so new versions install as updates.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME isn't set
./gradlew assembleDebug
```

## How it works

| Step | What happens |
|---|---|
| Detect | `assets/extract.js` runs in the WebView on every page. It finds chapter links (series page) or the ordered page images (chapter page). The page type controls the floating button. |
| Chapter list | Links are deduplicated and sorted by chapter number, so dates and labels like `{S3 END}` don't affect order. |
| Download | A background `WorkManager` job downloads 2 chapters at a time. For sites with a rule, it reads the page image URLs straight from the chapter HTML, which takes well under a second. Otherwise it falls back to a hidden WebView that scrolls the page to trigger lazy loading. Images are fetched with OkHttp using the browser's cookies and referer. |
| Save | Each image is resized and re-encoded to the chosen quality, packed into a CBZ, and written to the library folder. |
| Read | The reader unpacks the CBZ into cache and shows pages in a `LazyColumn`. Next/previous comes from the saved chapter order, so it works offline. |

If a site shows a browser check (e.g. Cloudflare), downloads pause and a notification asks you to open the site once in **Browse**. The cookies are shared, so downloads continue after you **Resume**.

## Search sources

| Site | How results are fetched | Rating | Chapters |
|---|---|---|---|
| Asura Scans | `/browse?search=&genres=` (server-rendered cards) | ✓ | ✓ |
| Arena Scans | `/?s=` for text, `/manga/?title=&genre[]=` with genres | ✓ | latest chapter number |
| Flame Comics | full catalog from `/browse` page data, filtered in the app | — | read from each series page |

Flame Comics doesn't show ratings, so its results are hidden when a minimum rating is set.

## Not implemented yet (from the spec)

- Paged (left-to-right / right-to-left) reader mode and pinch-zoom
- Periodic background update checks and auto-download of new chapters. "Check for new chapters" on a book works.
- Library categories, backup/export, and rebuilding the library from the folder
- "Test rule" preview in the site-rule editor
- Low-storage pause
