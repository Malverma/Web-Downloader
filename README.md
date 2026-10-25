# Web Downloader

An Android app for downloading manga chapters, or entire series, from websites and reading them offline.

- **Books**: open a manga's homepage in the built-in browser and tap **Add as Book**. You get the full chapter list and can pick which chapters to download (all, none, not downloaded, or a range with long-press).
- **Offline reader**: vertical continuous scroll, like the website. The end of each chapter has a **Next chapter** card, with optional auto-continue. Reading progress is saved.
- **Quality**: Original / High (1600px) / Medium (1200px) / Low (900px) / Custom. Set it globally, per book, or per download. Existing downloads can be re-compressed later.
- **Portable files**: each chapter is saved as a `.cbz` (with `ComicInfo.xml`) in `<Library>/<Book Title>/`. Other readers such as Mihon, Perfect Viewer and Kavita can open these files.
- **Firefox**: in Firefox for Android, use **Share → Web Downloader** on any manga page to open it in the app.
- **Site rules**: sites work through automatic detection. A small JSON rule (Settings → Sites) can make detection exact. A rule for `asurascans.com` is built in.

See [`spec.md`](spec.md) for the full design.

## Install for testing

1. Open the repo's **Actions** tab and select the latest **Build APK** run.
2. Download the `web-downloader-debug-apk` artifact and unzip it.
3. Copy `app-debug.apk` to your phone and open it. Android will ask you to allow installing from that source.

Or build it locally (see below) and run `adb install app/build/outputs/apk/debug/app-debug.apk`.

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
| Download | A background `WorkManager` job opens each chapter in a hidden WebView and scrolls it to trigger lazy loading. It collects the images, then downloads them with OkHttp using the browser's cookies and referer. |
| Save | Each image is resized and re-encoded to the chosen quality, packed into a CBZ, and written to the library folder. |
| Read | The reader unpacks the CBZ into cache and shows pages in a `LazyColumn`. Next/previous comes from the saved chapter order, so it works offline. |

If a site shows a browser check (e.g. Cloudflare), downloads pause and a notification asks you to open the site once in **Browse**. The cookies are shared, so downloads continue after you **Resume**.

## Not implemented yet (from the spec)

- Paged (left-to-right / right-to-left) reader mode and pinch-zoom
- Periodic background update checks and auto-download of new chapters. "Check for new chapters" on a book works.
- Library categories, backup/export, and rebuilding the library from the folder
- "Test rule" preview in the site-rule editor
- Low-storage pause
