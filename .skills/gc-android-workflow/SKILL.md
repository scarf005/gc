---
name: gc-android-workflow
description: Use this skill when building, installing, or debugging this widget app with Android CLI or adb.
metadata:
  author: scarf
  version: "1.0"
---

## When to use

Use this skill for Android development work in this repository, especially when you need to:

- install SDK packages
- deploy debug or release APKs
- inspect the widget on a connected device
- fetch current Android guidance for agent work

## Project commands

- Build debug APK: `./gradlew assembleDebug`
- Build release APK: `./gradlew assembleRelease`
- Run unit tests: `./gradlew testDebugUnitTest`

## Preferred Android CLI flow

If `android` is available, prefer these commands:

- Initialize agent support: `android init`
- Install SDK pieces: `android sdk install platform-tools platforms/android-35 build-tools/36.0.0`
- Install and launch debug APK: `android run --apks=app/build/outputs/apk/debug/app-debug.apk`
- Install and launch release APK: `android run --apks=app/build/outputs/apk/release/app-release-signed.apk`
- Search Android guidance: `android docs search '<query>'`
- Fetch a knowledge-base result: `android docs fetch <kb-url>`
- Inspect the current device UI: `android layout --pretty`
- Capture the current device screen: `android screen capture --output=/tmp/gc-widget.png`

## adb fallback

If Android CLI is not installed yet, use adb directly:

- `adb devices -l`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- `adb shell dumpsys appwidget | rg 'dev\\.scarf\\.gc|id='`
- `adb shell logcat -d -s ContributionWidget`

## Widget-specific notes

- The app package is `dev.scarf.gc`.
- The widget provider is `dev.scarf.gc.ContributionWidgetProvider`.
- Graph refresh issues usually need both `logcat` and `dumpsys appwidget` output.
