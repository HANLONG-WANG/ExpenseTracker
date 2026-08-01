# P04 token golden provenance

The checked-in `p04_token_palette.png` is a machine-only regression fixture, not a page design reference.

- Sole value source: `docs/UI设计稿与实现契约_v1.0/android_ledger_ui_tokens_v1.json`
- Generator: `python3 scripts/generate_p04_contracts.py`
- Verification: `python3 scripts/generate_p04_contracts.py --check`
- Content: all 208 color scalar occurrences selected by the explicit light/dark, semantic, 16-category and chart token traversal; translucent tokens are deterministically composited over the light background token before PNG encoding
- Geometry: 16 columns, 8 × 8 pixels per scalar, 128 × 104 pixels
- SHA-256: `25abe0640e33215c2f7d8c9a1cb30025459511761fc7c842c2ba7a655a64d991`
- Device comparison: exact dimensions and placement, with at most one 8-bit channel level of renderer rounding tolerance after deterministic alpha compositing

The generator uses an explicit allowlist of the token JSON and screen YAML. It does not enumerate the UI delivery directory and does not use any review-rendering artifact as an input or pixel oracle.
