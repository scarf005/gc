set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

default:
    just --list

clean:
    ./gradlew clean

android-init:
    android init

android-sdk:
    android sdk install platform-tools platforms/android-35 build-tools/36.0.0

test:
    ./gradlew testDebugUnitTest

debug:
    ./gradlew assembleDebug

install-debug: debug
    if command -v android >/dev/null; then android run --apks=app/build/outputs/apk/debug/app-debug.apk; else adb install -r app/build/outputs/apk/debug/app-debug.apk; fi

release:
    ./gradlew assembleRelease

sign-release: release
    SDK_ROOT="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"; \
    "$SDK_ROOT/build-tools/36.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release-aligned.apk; \
    "$SDK_ROOT/build-tools/36.0.0/apksigner" sign --ks ~/.config/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app/build/outputs/apk/release/app-release-signed.apk app/build/outputs/apk/release/app-release-aligned.apk

install-release: sign-release
    if command -v android >/dev/null; then android run --apks=app/build/outputs/apk/release/app-release-signed.apk; else adb install -r app/build/outputs/apk/release/app-release-signed.apk; fi
