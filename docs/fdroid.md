# F-Droid submission notes

## Current status

- `./gradlew testDebugUnitTest assembleRelease` passes locally.
- Android Studio is not required for F-Droid submission.
- Official F-Droid will usually build from source and sign the APK with its own key.
- This app depends on `https://github.com/users/<handle>/contributions`, so it will likely need the `NonFreeNet` anti-feature in official F-Droid metadata.

## Missing before official submission

- A public git remote for the source repository.
- A release tag for the published version, for example `v1.0.0`.
- At least one screenshot in `fastlane/metadata/android/en-US/images/phoneScreenshots/` if you want richer store metadata.

## Notes about signing

- You do not need your own signing key just to get into official F-Droid.
- You only need your own key if you also want to distribute APKs yourself and keep the same upgrade path outside F-Droid.
- If you later publish APKs signed by your own key, users cannot upgrade in place from the F-Droid-signed package without reinstalling.

## Suggested fdroiddata metadata outline

Use `dev.scarf.gc` as the package ID.

```yml
Categories: [Development, Internet]
License: AGPL-3.0-only
AntiFeatures:
  NonFreeNet: {}
SourceCode: <public repo url>
IssueTracker: <public issue tracker url>

RepoType: git
Repo: <public repo url>

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    gradle: [yes]

AutoUpdateMode: Version v%v
UpdateCheckMode: Tags
CurrentVersion: 1.0.0
CurrentVersionCode: 1
```
