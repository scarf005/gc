# Repository Rules

## Widget layout changes

- Before changing contribution widget layout, measure the reference and current screenshots: card bounds, grid bounds, U/D/L/R padding, square size, and gap.
- Convert measurements into target ratios/bounds and encode them in tests or renderer previews before changing layout code.
- Do not make another layout commit from visual guesses after a failed attempt; remeasure first and update the target ratios.
- Keep README user-facing; put development notes in `docs/development.md`.
