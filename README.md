# TraceView – Camera Drawing Assistant (Android)

Native Android app (Kotlin + CameraX). Live rear-camera preview with your reference image
floating on top as a transparent layer — like looking through tracing paper.

## Get the APK — option A: GitHub (no Android Studio needed)
1. Create a new repository on github.com and upload everything in this folder (keep `.github/`).
2. Open the repo's **Actions** tab → "Build TraceView APK" runs automatically (≈5 min).
3. Open the finished run → **Artifacts** → download `TraceView-APK` (zip containing the APK).
4. Copy `app-debug.apk` (or `app-release.apk`) to your phone, open it, allow "Install unknown apps".

## Option B: Android Studio
Open this folder in Android Studio (Koala or newer) → wait for Gradle sync →
Build ▸ Build App Bundle(s)/APK(s) ▸ Build APK(s), or press Run with your phone connected.

Requirements: Android 8.0+ (API 26), a camera.

## Using it
- **Import** (top bar): Gallery/Photos or device storage. JPG, JPEG, PNG, WEBP. Preview before tracing.
  You can also *Share* an image from Gallery to TraceView.
- **Gestures**: one finger moves; two fingers pinch-zoom and twist-rotate.
- **Right rail**: zoom + / −, rotate ⟲ ⟳ (tap = 5°, hold = fine), flip H / V, move (D-pad for 1-px nudges),
  fit to paper, reset.
- **Lock** (bottom left): freezes the image so your hand can't move it. Taps still focus the camera.
- **Opacity** slider: 10–100 %, default 50 %. **Volume up/down** also changes opacity by 5 %.
- **Top bar**: switch camera, flashlight, grid (large / small / off), full screen, hide controls (focus mode — only slider and lock stay).
- Tap anywhere on the paper to focus.
- Screen never sleeps while the app is open. Image position, opacity, grid, lock state
  and the last reference image are saved automatically.
- Portrait is locked on purpose: a phone lying flat above paper makes auto-rotate flip randomly.
  Use the rotate buttons to turn the image instead.

## Project layout
- `MainActivity.kt` – camera (CameraX), permissions, controls, saving settings
- `OverlayView.kt` – transparent reference layer: transform matrix, gestures, grid
- `ImageLoader.kt` – decode, EXIF orientation, downscale, save/restore last image
- `res/layout/activity_main.xml` – camera + overlay share one full-screen FrameLayout (same coordinate system)
