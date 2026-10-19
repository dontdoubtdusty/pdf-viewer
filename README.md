# Lean PDF

A PDF viewer for Android that only opens and reads PDFs.

- **Zero dependencies.** No AndroidX, no Kotlin stdlib, no bundled PDF engine. Rendering uses Android's built-in `PdfRenderer`, so the release APK is tiny (expect well under 100 KB).
- **Two source files.** `MainActivity.java` (open/pick a file) and `PdfView.java` (draw, scroll, zoom).
- **Fast start.** No splash screen, no layout inflation, window background matches the reader so there's no white flash.

## What it does

- Opens PDFs from "Open with" (Files, Gmail, Drive, Downloads…) or a file picker when launched from the home screen
- Continuous vertical scroll, pages fit to width
- Pinch to zoom (1×–6×), double-tap to zoom in/out, fling
- Re-renders sharp after zooming; renders off the UI thread with a memory-capped cache
- Brief "3 / 20" page indicator while scrolling
- Keeps your place on rotation
- Shows "Opened in X ms" for 2 seconds once the first page is on screen

That's the whole feature list, on purpose.

## Build

1. Open the `lean-pdf` folder in Android Studio (File → Open). Let it sync; it downloads Gradle 8.9 and Android Gradle Plugin 8.7.3. If Studio offers to upgrade AGP, accepting is fine.
2. Plug in a phone (or start an emulator) and press Run.
3. For the small optimized APK: Build → Generate Signed App Bundle / APK → APK → release, or run `gradle :app:assembleRelease`. The release build is signed with your debug key so you can sideload it immediately; use your own key before publishing.

The project has no `gradlew` script/jar (it couldn't be generated offline). Android Studio doesn't need them; if you want command-line builds, run `gradle wrapper` once in the project folder.

## Requirements and limits

- Android 8.0+ (API 26).
- Password-protected PDFs aren't supported (shows a message).
- No text selection, search, annotations, or forms — `PdfRenderer` draws pages as images. Adding those would mean bundling a PDF engine like PDFium/MuPDF, which is several MB.
