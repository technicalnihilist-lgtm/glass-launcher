# Glass Launcher 2.2

An iOS‑style Liquid Glass home screen for Android — a real HOME app (launcher), not a
mockup. **The UI is your original `index.html`, unchanged, byte for byte**
(`md5 5793c8b6cebfc1bef77aa395773e1505`). A small native shell only does what a web page
cannot: read the installed apps, hand them to the page through `setApps()`, and launch
them when the page asks.

[**⬇ Download the APK**](https://github.com/technicalnihilist-lgtm/glass-launcher/releases/download/v2.2/GlassLauncher.apk) · [latest release](https://github.com/technicalnihilist-lgtm/glass-launcher/releases/latest) · 69 KB · Android 7.0+ (API 24) · no permissions

| Home | Second page | Liquid Glass controls |
|:--:|:--:|:--:|
| <img src="docs/screenshots/home.jpg" width="240"> | <img src="docs/screenshots/apps.jpg" width="240"> | <img src="docs/screenshots/glass.jpg" width="240"> |

*(Screenshots are this repo's `index.html` rendered at 393×851 @2×, fed through the same
`setApps()` call the APK uses.)*

## What the native shell does — nothing more

| Page calls / needs | Shell provides |
|---|---|
| `setApps(json)` | `{apps:[{n,p,i}], dock:[pkg,…]}` — every launchable app, real PNG icons, sorted with a locale-aware `Collator`, refreshed on install / uninstall |
| `Android.launch(pkg)` | starts that package |
| `goBack()` / `goHome()` / `onResumeL()` | Back key, Home key, and return-from-app |
| status bar | hidden, because the page draws its own clock and signal icons |

Nothing in `index.html` was restyled, renamed or re-laid-out.

## What was fixed in 2.2 (crash / glitch work)

The shell is now defensive, because a HOME app that dies leaves the phone with no launcher:

* **WebView renderer death** — heavy `backdrop-filter` plus low memory can kill the WebView
  process on its own, which used to take the whole app down. `onRenderProcessGone()` now
  rebuilds the WebView and reloads the page instead of crashing.
* **Icon memory blow‑up** — the icon payload is the biggest thing sent to the page. It is now
  scaled by app count (108 px up to 90 apps, 88 px to 180, 72 px beyond) and `largeHeap` is
  on, so a phone with 250 apps can no longer exhaust the WebView's memory mid‑load.
* **Nothing can escape `onCreate`** — window setup, WebView creation and receiver
  registration are each wrapped; if the WebView cannot start at all, a plain native app list
  ("safe mode") is shown so the phone is still usable.
* **Stuck "opening…" overlay** — if an app disappears between listing and tapping, the page
  used to sit on its launch overlay forever. The shell now calls `goHome()` to recover.
* **Crash log** — any uncaught exception is written to `files/last_crash.txt` before the
  process ends, so a remaining bug can be identified instead of guessed at.
* Missing icons no longer throw; every icon falls back to the system default icon.

## Install

1. Grab the APK from the [v2.2 release](https://github.com/technicalnihilist-lgtm/glass-launcher/releases/latest)
   (or `adb install -r GlassLauncher.apk`).
2. Allow "Install unknown apps" when Android asks.
3. Press **Home** → **Glass Launcher** → **Always**.

To switch back: Settings → Apps → Default apps → Home app.

> The APK is signed with a self-signed key, so Play Protect may show one
> "unknown developer" prompt. That is normal for a sideloaded app.

> **Coming from 2.0 or 2.1?** Uninstall the old Glass Launcher first — the signing key
> changed in 2.1, so Android refuses it as an in-place update ("App not installed").
> 2.2 installs over 2.1 normally.

## Known limits (inherent to the page, not the shell)

* `index.html` is a fixed 390×844 canvas scaled with `min(width/390, height/844)`.
  On a screen much taller than 390×844 (e.g. 21:9) that leaves thin black bands at the
  top and bottom. Filling them needs one line changed in the page
  (`Math.min` → `Math.max` plus a crop, or a `100dvh` canvas) — the page is untouched
  until you ask for that.
* The weather widget ("Atlanta 54°") is static text in the page, not live weather.

## Build it yourself

**Android Studio** — File → Open → this folder → Gradle sync → Run.

**Command line** (no Gradle, straight SDK build-tools):

```bash
export ANDROID_HOME=/path/to/android-sdk     # platforms;android-34 + build-tools;34.0.0
export JAVA_HOME=/path/to/jdk17
./build.sh                                   # → GlassLauncher.apk (signed, installable)
```

`build.sh` runs `aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner`.

## Project layout

```
app/src/main/assets/index.html          your UI - unchanged (md5 5793c8b6…)
app/src/main/java/com/glass/launcher/   WebView shell, app list, crash guards
app/src/main/AndroidManifest.xml        HOME intent filter, configChanges, largeHeap
app/src/main/res/                       adaptive icon, theme, strings
docs/screenshots/                       the images above
build.sh                                CLI build
```

## Changelog

**2.2** — `index.html` restored to the original, untouched. Crash work: WebView renderer
recovery, bounded icon payload + `largeHeap`, safe-mode fallback, stuck-overlay recovery,
crash log file.

**2.1** — Glass app icon, centred widgets, action-sheet tint, screenshots.

**2.0** — first public release.
