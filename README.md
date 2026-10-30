# Glass Launcher 2.0 — real Android home screen (HTML + WebView)

A working Android launcher (HOME app) whose UI is a single HTML file, wrapped in a
native WebView shell. Install the APK, press Home, pick "Glass Launcher" → Always.

## Install the APK
1. Copy `GlassLauncher.apk` to the phone (or `adb install -r GlassLauncher.apk`).
2. Allow "Install unknown apps" for your file manager when Android asks.
3. Press the Home button → choose **Glass Launcher** → **Always**.
   To go back: Settings → Apps → Default apps → Home app.

The APK is signed with a self-signed key, so Play Protect may show a
"scan/unknown developer" prompt once. That is expected for a sideloaded app.

## What "adjusts to the system" means here
Everything the UI measures comes from the device, not from a fixed 390×844 canvas:

* **Real insets** — window/cutout/navigation-bar insets are pushed from Java
  (`readInsets`) and drive the top status row, the dock and every sheet.
* **Density-aware sizing** — WebView CSS pixels equal dp, so an icon is physically
  the same size on a 320 dp phone and an 800 dp tablet.
* **Auto grid** — columns = `clamp(floor((width-2·pad)/92), 4, 7)` in portrait and
  `clamp(…/86, 6, 10)` in landscape; rows are recomputed from the space that is
  actually left above the dock, so nothing ever overlaps.
* **Rotation / foldables / multi-window** — `configChanges` keeps the activity alive,
  and the page re-lays-out on every resize.
* **Real system data** — system wallpaper (read via `WallpaperManager`), battery
  level + charging state, clock, locale (`Collator` sorts app names in your language),
  device model / Android version.
* **Real app list** — `PackageManager.queryIntentActivities`, icons rasterised at
  density×56 px, live refresh on install/uninstall.
* **Real navigation** — Back → `goBack()` (close app → close sheet → page 0 →
  wobble), Home → `goHome()`, edge-swipe back, long-press for app info.

## Build it yourself
**Android Studio:** File → Open → this folder → let Gradle sync → Run.

**Command line (no Gradle, uses the SDK build-tools directly):**
```bash
export ANDROID_HOME=/path/to/android-sdk     # needs platforms;android-34 + build-tools;34.0.0
export JAVA_HOME=/path/to/jdk17
./build.sh          # → GlassLauncher.apk (signed, installable)
```

## Files
```
app/src/main/assets/index.html            the whole UI (edit freely)
app/src/main/java/com/glass/launcher/…    WebView shell + system bridge
app/src/main/AndroidManifest.xml          HOME intent filter, configChanges
app/src/main/res/…                        icon, theme, strings
build.sh                                  aapt2 → javac → d8 → zipalign → apksigner
```

## Customising
* Glass look: long-press an empty area → Wallpaper / Grid columns / Icon size / Blur.
  The "Glass" app on page 1 exposes transparency, blur, saturation, brightness, edge.
* Preferences persist in `localStorage` (`glass_launcher_v1`).
