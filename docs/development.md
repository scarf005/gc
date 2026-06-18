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
