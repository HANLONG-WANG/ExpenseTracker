# P34 UI Contract Closure

Last updated: 2026-08-11 (Asia/Tokyo)

P34 is `VERIFIED`. This review closes the UI acceptance surface without changing any frozen specification and without opening, parsing, sampling, measuring, screenshotting or comparing any excluded visual draft. All rendered baselines are generated from production Compose code derived from the textual UI contract, token JSON, screen YAML and traceability CSV.

## Exact contract inventory

| Inventory | Closed result | Evidence |
|---|---:|---|
| Screen IDs and unique routes | 215 / 215 `VERIFIED` | `P34-E001`; every row in `SCREEN_COVERAGE.csv` retains its owning-stage evidence and adds the P34 whole-product replay |
| Required states | 646 / 646 | `validate_p34_ui_closure.py` compares every ordered YAML state list with the ledger; owning-stage screen tests render the row states and the P34 device aggregate replays all Android suites |
| Frozen requirements | 89 `VERIFIED`, 1 `IN_PROGRESS` | `REQ-084` remains in P35 for target-scale performance; all UI acceptance items are closed by `P34-E001`—`P34-E005` |
| Localized Android modules | 17 / 17 | Complete Simplified Chinese, Japanese and English key/type/format-argument parity plus production plurals consumption |

The validator also proves that all 215 IDs are referenced by named production destinations, routes and results remain equal to YAML, and no anonymous `@Composable` page exists. `SCREEN_COVERAGE.csv` is the per-screen index from each ID and its required states to named implementation and test evidence; P34 does not replace the stronger owning-stage evidence with a superficial screenshot claim.

## Language, region and adaptive layout

- Simplified Chinese, Japanese and English resources are complete in all 17 UI-bearing modules. Format arguments and plural resources are structurally checked, while existing locale-aware money/date/time formatters retain currency scale, region and zone evidence.
- Production and test matrices cover 320dp, 360dp, 480dp and 600dp widths; 600dp is the foldable/tablet boundary. Font scales cover 100%, 130%, 160% and 200%, including a 480dp/160% English case and a 600dp/200% Japanese case.
- Light, dark and dynamic-color boundaries are exercised. Dynamic color changes only the Material shell; ledger semantic/category/chart colors retain token authority. Long-text and 200% cases use scroll/wrap/adaptive arrangements instead of clipping critical controls.

## Accessibility and privacy semantics

- A real installed Google TalkBack service is enabled before the API 36 instrumentation process. The critical category → complete form → save flow is completed with Android `AccessibilityNodeInfo.ACTION_CLICK` operations, with explicit scaffold traversal order and no Compose-test/touch-only shortcut. This replay found and fixed the previously unlabeled extended Save FAB.
- All governed actions retain the 48dp target, localized selection and paging state descriptions, non-color meaning, grayscale-readable transaction signs/statuses and reduced-motion behavior.
- Charts provide a localized summary and accessible data table; maps preserve the same list/table alternative when rendering or location is unavailable.
- Hidden amounts expose only the localized “amount hidden” state. Vault/account/location-sensitive values remain absent from merged and unmerged semantics, routes, SavedState, widgets, diagnostics and ordinary export surfaces.

## Golden and component governance

Critical account, recording, journal, liability, planning, analytics/map, import, backup, Vault, settings/clear/system, global and onboarding surfaces have exact production pixel baselines. P34 adds an account-home baseline derived directly from the token/YAML contract and replays the component matrix across size, font, locale, theme, dynamic-color, grayscale and reduced-motion boundaries. Static governance continues to reject raw feature Material components, visual literals, external icon families, swipe deletion, sensitive tags and unnamed/anonymous page declarations.

## Cross-stage repair retained by P34

Date-sensitive widget snapshots are Cache projections. P34 found that including them in the canonical financial projection hash made an otherwise identical ledger hash change after a local-date refresh. They remain rebuildable and are still checked for schema version, local/valuation revision, row count and stale-date behavior, but are excluded from the authoritative fact/projection hash. `P34-E006` verifies the corrected boundary on SQLCipher and the P34 mutation gate rejects reintroduction.

P35 remains the next stage and owns only the frozen target-scale performance/fault/security audit, including final closure of `REQ-084`. P36 release evidence is not promoted here.
