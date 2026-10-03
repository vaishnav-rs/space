# Orion for Android

The native Android app (Kotlin, Jetpack Compose) is the Orion client. It pairs with an Orion gateway over the network (QR, setup code, or manual address) and is native for chat, sessions, approvals, automations, skills, channels, devices and the Orion admin screen.

## What Orion adds on top of the OpenClaw app

- **Orion theme**, the default family: night-sky ink with a periwinkle accent, light and dark. Android-local for now (it is not synced to the gateway's theme catalog).
- **Settings → Connections → Orion admin** (`OrionAdminScreen.kt`, `OrionAdmin.kt`): people and roles, connections, tasks, workspaces, activity. It uses the gateway methods `orion.admin.overview` and `orion.admin.apply`; the gateway decides what each person may see or change by Orion role.
- **Pairing from the phone**
  - **WhatsApp** (organization-wide): shows the gateway's QR (`web.login.start` / `web.login.wait`); scan it from WhatsApp → Linked devices.
  - **GitHub** (your own account): device-code sign-in (`users.github.authorize.*`): enter the code on github.com.
  - **Google, Resend, org GitHub token**: credential forms that go straight to the gateway's encrypted vault.

## Where the agent runs

This app does not run the gateway process itself. Point it at a gateway on your network, a VPS, or the same phone through Termux/proot (`127.0.0.1`). Running the Node gateway inside the APK would mean shipping an Android build of Node 24 and porting the gateway's native dependencies; that is not done.

## Still using a web view

Session dashboards, the terminal and desktop viewer, and rich chat blocks render through `WebView`. A native renderer for dashboards is not built.

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk     # platform 37, build-tools 37
./gradlew :app:assemblePlayDebug
./gradlew :app:testPlayDebugUnitTest --tests 'ai.openclaw.app.OrionAdminTest'
```

## The gateway on your phone

Settings → **On this phone** runs the whole Orion gateway inside the app, no computer needed.

How it works: the release APK carries Node 24, git and OpenSSH (arm64, from the Termux package repository) as native libraries, plus the gateway itself as an archive. Android 10+ only allows executing files from the app's read-only native library directory, so the executables live there and `bin/` holds symlinks to them. Supporting libraries and the gateway unpack into the app's private storage on first run. A foreground service (`OnDeviceGatewayService`) supervises the `node openclaw.mjs gateway run` process, restarts it with backoff, and connects the app to `ws://127.0.0.1:<port>` with a generated token. Secrets (vault key, webhook secret, token) are generated once and kept in the app's private storage.

Tap **Set up and start**, fill in your email and WhatsApp number, add Google/Resend/GitHub credentials under *Connections*, and use **Run self-test** to see whether Node, `node:sqlite`, git, ssh and the gateway start on your phone.

Build it locally: `node apps/android/scripts/fetch-termux-runtime.mjs && apps/android/scripts/bundle-gateway.sh`, then build the `thirdParty` variant. The release workflow does both.

Known limits: arm64 only; the terminal feature needs a PTY add-on that is not bundled for Android, so the in-app terminal may be unavailable on the phone gateway; Android may still stop background work on aggressive battery managers (use *Allow running in the background*); the Hewar loop (git, tests) runs on the phone's CPU and storage.
