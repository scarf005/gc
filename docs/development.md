# Development notes

## Widget layout contract

For an `n x 1` launcher layout, the graph is one week row tall:

- Graph padding is a fixed ratio of the launcher-reported widget height, matching the Redmi Note 10 Pro reference screenshot.
- Each row is exactly 7 squares.
- Square size is auto-calculated from available height after ratio-based U/D/L/R graph padding.
- Gap size keeps the existing square-to-gap ratio.
- Columns auto-fill the current portrait widget width (`OPTION_APPWIDGET_MIN_WIDTH`) using that square size and gap; do not use the landscape maximum width for portrait rendering.
- Left/right leftover padding must not exceed up/down leftover padding when another column can fit.
- The ImageView must center the rendered bitmap without scaling it; scaling changes the square/padding ratio across launcher-reported bounds.

Before changing widget layout, measure the reference and current screenshots first: card bounds, grid bounds, U/D/L/R padding, square size, and gap. Convert those to target ratios, update tests/previews against those ratios, and do not add another layout commit until the measured output matches the target bounds.

## Project commands

```bash
just test
just debug
just release
```

## Releases

Tag the commit with `v<versionName>` from `app/build.gradle.kts`, or dispatch the Android workflow with that existing tag. Branch names and tags with a different version are rejected before signing.

Every locale under `fastlane/metadata/android/` needs a nonempty `changelogs/<versionCode>.txt`. GitHub release notes use the English file. Reruns replace blank bodies or the exact `Release <tag>` placeholder and preserve other existing notes. Tooling and changelogs come from the workflow revision so manual dispatch can rebuild older tags.

The workflow verifies the APK's application ID, versionCode, and versionName before signing and publication. Signing secrets are required only for releases.

Check release tooling locally with `deno task test`, `deno fmt --check`, `deno lint scripts/`, and `deno check scripts/`. `@david/dax` handles commands; Valibot validates the stage and GitHub response. The workflow resolves `GIT_PATH` and `GH_PATH`, granting reads and execution only for those binaries and the SDK's `aapt`. Commands start with a cleared environment and retain `PATH`, `HOME`, `XDG_CONFIG_HOME`, and the GitHub token only when invoking `gh`. Deno's Node compatibility layer also needs `NODE_V8_COVERAGE` read permission. Subprocesses retain their normal filesystem and network access.

## Android CLI / adb workflow

```bash
# Optional Android CLI setup
android init
android sdk install platform-tools platforms/android-35 build-tools/36.0.0

# Install via Android CLI if available, otherwise adb
just install-debug
just install-release

# Pull fresh Android guidance for an agent task
android docs search 'app widget debugging'
```

If Android CLI reports no online devices while `adb devices -l` shows a connected device, run `android update` and retry. Older CLI versions can miss wireless device identifiers containing spaces.

Project-local Android agent instructions live in `.skills/gc-android-workflow/SKILL.md`.

## Contribution palettes

GitHub palettes use the [Primer contribution tokens](https://github.com/primer/primitives/blob/main/src/tokens/component/contribution.json5), resolved from [`@primer/primitives` 11.10.0 CSS](https://unpkg.com/@primer/primitives@11.10.0/dist/css/functional/themes/).

`app/src/main/res/values/widget_palettes.xml` defines theme metadata, palettes, order, default, and legacy aliases. Kotlin only loads these resources.

- Expose the six distinct default graph palettes and Halloween/winter light/dark palettes. Colorblind and tritanopia modes share their corresponding default graph colors, so they are not duplicate choices.
- Seasonal themes are manually selectable year-round; they do not change automatically with the date.
- Theme selection persists immediately and redraws cached data without fetching or saving the edited handle. Saving an unchanged handle with cached data also skips fetching; new handles or missing caches fetch once.
- Resolve the legacy `blue` theme key to winter light without a duplicate choice. Transparent uses `#39d353` with increasing alpha (`20`, `54`, `88`, `c4`, `ff`), as [requested in review](https://github.com/scarf005/gc/pull/3#issuecomment-5968457903).

## Renderer-level previews

Generate widget graph previews without a device or launcher:

```bash
just widget-previews
```

This runs `ContributionWidgetPreviewTest` with `GC_WIDGET_PREVIEWS=1` and writes SVG files for every theme to `build/widget-previews/`, using the production palettes and card background resources. The preview renderer mirrors the widget layout contract from Kotlin: 7 rows for `n x 1`, square size from available height, unchanged gap ratio, and maximum auto-fit columns while keeping L/R leftover padding no larger than U/D leftover padding.

## CLI screenshots

Current device screen:

```bash
adb exec-out screencap -p > /tmp/gc-widget.png
# or, when Android CLI is available:
android screen capture --output=/tmp/gc-widget.png
```

For multiple resolutions, use an emulator or a test device and override display size/density before each capture:

```bash
adb shell wm size 1080x2400
adb shell wm density 440
adb exec-out screencap -p > /tmp/gc-widget-1080x2400.png
adb shell wm size reset
adb shell wm density reset
```

Launcher widget placement and resizing are launcher-dependent, so fully automated widget screenshots across sizes may need either emulator UI scripting or renderer-level screenshot tests.
