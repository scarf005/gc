# gc

## Debug

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

```bash
./gradlew assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/app-release-aligned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks ~/.config/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app/build/outputs/apk/release/app-release-signed.apk app/build/outputs/apk/release/app-release-aligned.apk
adb install -r app/build/outputs/apk/release/app-release-signed.apk
```
