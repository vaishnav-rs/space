# PerfectFrame

An Android camera that helps you take a better-composed photo. It captures at the highest
resolution your sensor will give a third-party app, keeps exposure fully automatic but *legible*,
and — the headline feature — analyzes the live scene on-device and shows you the **perfect frame**:
where to put the subject, when the horizon is level, and the moment the shot is worth taking.

No accounts, no network, no cloud. All vision runs on-device.

## What it does

- **Max-resolution capture.** Asks the camera HAL for the largest still it will expose
  (high-resolution / sensor mode included) and reports honestly what it actually got.
- **Legible auto exposure.** ISO · shutter · aperture · white balance are shown live, with a plain
  line explaining *why* the auto system settled there ("ISO capped to limit noise", "shutter slowed
  for low light — hold steady"), computed against the sensor's real ISO/exposure ranges.
- **On-device composition guidance.** ML Kit face + object detection feeds a composition engine
  that proposes a rule-of-thirds crop with headroom for faces and lead room in the direction a
  subject faces. The suggestion is drawn as a bracketed frame over a dimmed surround.
- **Level horizon.** Sensor-fusion (accelerometer + magnetometer) roll indicator that snaps flat
  and glows when you're level.
- **The "ideal" moment.** When confidence, level, and framing all line up, the frame turns mint,
  breathes, and gives a single haptic tick — the reward for a well-composed shot.
- **One honest manual override.** Everything is automatic by default; the single settings sheet
  offers EV compensation and an AE/AWB lock. Aperture is never a control because it is physically
  fixed — no fake dials.

## Design principles

1. **Honesty over flash.** Never fabricate precision (white balance is shown as an explicit
   estimate; capability reporting says what the device actually captured).
2. **Guidance earns trust.** Low-confidence suggestions are hidden rather than nagging.
3. **Automatic, but legible.** The user should understand what the camera is doing, not just accept
   a black box.
4. **One screen.** A full-bleed viewfinder with overlays — no navigation, no tabs, one settings
   sheet.

## Architecture

```
com.perfectframe.camera
├── camera/        CameraX use-case graph, max-res capture, Camera2Interop exposure pipeline
├── vision/        ML Kit subject detection (ImageAnalysis, throttled, KEEP_ONLY_LATEST)
├── composition/   CompositionEngine — pure Kotlin, JVM-unit-tested framing scorer
├── sensors/       Sensor-fusion level detector
└── ui/            Compose viewfinder: framing overlay, horizon, glass HUD, guidance, settings
```

The **composition engine is deliberately framework-free Kotlin** so it can be unit-tested on the
JVM — the one part of a camera pipeline you *can* verify without a device. See
`app/src/test/java/.../CompositionEngineTest.kt`.

The analysis stream runs at 720p, throttled to ~8fps, with `STRATEGY_KEEP_ONLY_LATEST` on a
dedicated single-thread executor, so slow ML frames are dropped rather than queued and never starve
the preview.

## Build

Requires the Android SDK and JDK 17.

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run the composition-engine unit tests
```

The dependencies (CameraX, ML Kit, Compose) come from Google's Maven repository, so a network that
can reach `dl.google.com` is required to resolve them.

## Continuous integration

`.github/workflows/android.yml` builds the debug APK and runs the unit tests on every push and PR,
and uploads the APK and test reports as build artifacts. CI is the source of truth for "does it
build", since it has the network access to resolve the Google-hosted dependencies.

## Requirements

- minSdk 28 (Android 9), targetSdk 35
- CAMERA permission (requested at runtime)
- A back camera; sensors are optional (the level indicator hides itself if absent)
