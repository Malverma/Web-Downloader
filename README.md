# Web Downloader

An Android app for downloading manga chapters, or entire series, from websites and reading them offline.

- **Find manga**: use the **Search** tab (or share a link from Firefox). Tap a result to see its full chapter list and pick which chapters to download: tap rows, use All / None / Not downloaded, or **hold and drag** to select many at once (the list auto-scrolls at the edges).
- **Library**: shows only manga you have downloaded. Opening one lists just its downloaded chapters, and chapters you have opened are shaded darker. **Download** opens the full chapter list to get more.
- **Downloads tab**: one folder per manga with its queued, active, failed and finished chapters, matching the folders on disk.
- **Offline reader**: vertical continuous scroll, like the website. The end of each chapter has a **Next chapter** card, with optional auto-continue. Reading progress is saved. Buttons in the bottom-right auto-scroll down (speed set in Settings) and jump to the previous/next downloaded chapter. Very tall webtoon strips are shown in slices so they always render.
- **Quality**: Original / High (1600px) / Medium (1200px) / Low (900px) / Custom. Set it globally, per book, or per download. Existing downloads can be re-compressed later.
- **Portable files**: each chapter is saved as a `.cbz` (with `ComicInfo.xml`) in `<Library>/<Book Title>/`. Other readers such as Mihon, Perfect Viewer and Kavita can open these files.
- **Firefox**: in Firefox for Android, use **Share → Web Downloader** on any manga page to open it in the app.
- **Search**: the **Search** tab searches Asura Scans, Arena Scans, Flame Comics, MangaFire and MangaDex together. Filter by genre (tap once to include, twice to exclude, three times to clear; e.g. Action + Fantasy but not Romance), **Completed** works only, minimum rating, and minimum chapter count, and sort by relevance, chapters or title. Tap a result to pick chapters to download.
- **Themes**: Settings → Themes has 8 dark themes: Nebula (default), Purple Haze, Pink Galaxy, Enchanted Forest, Moonlit Abyss, Deep Ocean, Ember and Void (true black).
- **Site rules**: sites work through automatic detection. A small JSON rule (Settings → Sites) can make detection exact. Rules for `asurascans.com`, `arenascan.com` and `flamecomics.xyz` are built in. MangaFire and MangaDex are read through their APIs instead.

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

If a site shows a browser check (e.g. Cloudflare), downloads pause and a notification asks you to open the site once in the in-app browser. The cookies are shared, so downloads continue after you **Resume**.

## Search sources

| Site | How results are fetched | Rating | Chapters | Completed filter |
|---|---|---|---|---|
| Asura Scans | `/browse?search=&genres=&status=` (server-rendered cards) | ✓ | ✓ | on the site |
| Arena Scans | `/?s=` for text, `/manga/?title=&genre[]=&status=` otherwise | ✓ | latest chapter number | on the site |
| Flame Comics | full catalog from `/browse` page data, filtered in the app | — | read from each series page | in the app |
| MangaFire | `/api/titles`, called from a hidden WebView (the API only accepts requests signed by the site's own script) | read from each title | latest chapter number | on the site |
| MangaDex | public API (`api.mangadex.org`), English chapters only | ✓ | ✓ | on the site |

Some MangaDex titles only link to official sites for their English chapters; those can't be downloaded, and the chapter picker says so.

Flame Comics doesn't show ratings, so its results are hidden when a minimum rating is set.

## Not implemented yet (from the spec)

- Paged (left-to-right / right-to-left) reader mode and pinch-zoom
- Periodic background update checks and auto-download of new chapters. "Check for new chapters" on a book works.
- Library categories, backup/export, and rebuilding the library from the folder
- "Test rule" preview in the site-rule editor
- Low-storage pause
