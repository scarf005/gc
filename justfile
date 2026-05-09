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

fdroid-build:
    #!/usr/bin/env bash
    set -euo pipefail
    command -v uvx >/dev/null || { echo "uvx is required" >&2; exit 1; }
    test -z "$(git status --porcelain)" || { echo "Commit or stash changes before fdroid-build" >&2; exit 1; }
    root="$(pwd -P)"
    sdk="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
    app_id="$(awk -F'"' '/applicationId = / { print $2; exit }' app/build.gradle.kts)"
    version_code="$(awk -F' = ' '/versionCode = / { print $2; exit }' app/build.gradle.kts)"
    version_name="$(awk -F'"' '/versionName = / { print $2; exit }' app/build.gradle.kts)"
    commit="$(git rev-parse HEAD)"
    work="$root/build/fdroid"
    rm -rf "$work"
    mkdir -p "$work/data/metadata" "$work/bin"
    cat > "$work/bin/gradlew-fdroid" <<'SH'
    #!/usr/bin/env bash
    set -euo pipefail
    root="$PWD"
    while [ ! -f "$root/gradle/wrapper/gradle-wrapper.properties" ] && [ "$root" != / ]; do
        root="$(dirname "$root")"
    done
    prop="$root/gradle/wrapper/gradle-wrapper.properties"
    url=$(python3 - "$prop" <<'PY'
    import sys
    from pathlib import Path
    for line in Path(sys.argv[1]).read_text().splitlines():
        if line.startswith('distributionUrl='):
            print(line.split('=', 1)[1].replace('\\:', ':'))
            break
    PY
    )
    dist_zip="${url##*/}"
    dist="${dist_zip%.zip}"
    dist_dir="${dist%-bin}"
    gradle_bin=$(find "$HOME/.gradle/wrapper/dists/$dist" -path "*/$dist_dir/bin/gradle" -type f -print -quit 2>/dev/null || true)
    if [ -z "$gradle_bin" ]; then
        cache="${GRADLE_VERSION_DIR:-$HOME/.cache/fdroid/gradle}"
        mkdir -p "$cache/$dist"
        zip="$cache/$dist_zip"
        if [ ! -f "$zip" ]; then
            curl -fsSL "$url" -o "$zip"
        fi
        unzip -q -o "$zip" -d "$cache/$dist"
        gradle_bin=$(find "$cache/$dist" -path "*/$dist_dir/bin/gradle" -type f -print -quit)
    fi
    exec "$gradle_bin" -p "$root" "$@"
    SH
    chmod +x "$work/bin/gradlew-fdroid"
    cat > "$work/data/config.yml" <<EOF
    sdk_path: $sdk
    gradle: $work/bin/gradlew-fdroid
    EOF
    cat > "$work/data/metadata/$app_id.yml" <<EOF
    Categories:
      - Internet
    License: AGPL-3.0-only
    AutoName: gc
    Summary: GitHub Contribution graph widget
    Description: |-
      Tiny Android widget for viewing a GitHub contribution graph.
    RepoType: git
    Repo: file://$root
    Builds:
      - versionName: $version_name
        versionCode: $version_code
        commit: $commit
        subdir: app
        gradle:
          - yes
    AutoUpdateMode: None
    UpdateCheckMode: None
    CurrentVersion: $version_name
    CurrentVersionCode: $version_code
    EOF
    cd "$work/data"
    git init -q
    git add config.yml "metadata/$app_id.yml"
    git -c user.name=fdroid -c user.email=fdroid@example.invalid commit -q -m init
    uvx --from fdroidserver fdroid build --verbose --no-tarball --stop "$app_id:$version_code"

sign-release: release
    SDK_ROOT="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"; \
    "$SDK_ROOT/build-tools/36.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release-aligned.apk; \
    "$SDK_ROOT/build-tools/36.0.0/apksigner" sign --ks ~/.config/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app/build/outputs/apk/release/app-release-signed.apk app/build/outputs/apk/release/app-release-aligned.apk

install-release: sign-release
    if command -v android >/dev/null; then android run --apks=app/build/outputs/apk/release/app-release-signed.apk; else adb install -r app/build/outputs/apk/release/app-release-signed.apk; fi
