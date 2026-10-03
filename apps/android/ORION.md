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
