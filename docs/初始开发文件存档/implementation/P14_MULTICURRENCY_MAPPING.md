# P14 Specialized Transactions and Multicurrency Mapping

P14 is `VERIFIED`. This mapping is limited to internal transfer, balance adjustment, FX exchange, opening balance, rate caching/current valuation, and currency visibility. Later journal detail/editing, refund, credit, loan, import, analytics, and release acceptance remain in their owning stages.

## Frozen inputs and coverage

Implementation uses only the frozen requirements/architecture/domain text, the UI main contract, token JSON, screen YAML, and traceability CSV. The four excluded visual PNG/HTML drafts were not opened, parsed, sampled, measured, compared, or used as golden inputs.

| Screen | Route | Exact required states | P14 realization |
|---|---|---|---|
| `REC-013` | `record/transfer/{transactionId?}` | editing, sameAccountError, fxRequired, saving | Same-currency single authoritative amount; cross-currency dual authoritative account amounts; same-account rejection; exact implied rate; time/note/encrypted attachment; fixed Save FAB |
| `REC-020` | `record/balance-adjustment/{accountId?}` | editing, saving | Increase/decrease direction, optional immutable checkpoint association, and explicit no-income/expense/consumption/budget explanation |
| `REC-021` | `record/fx-exchange` | editing, sameCurrencyInfo, rateMismatch, saving | Dual amounts, effective/reference rates, exact base spread/cost, HALF_EVEN rounding evidence, and different-currency enforcement |
| `REC-022` | `record/opening-balance/{accountId}` | editing, immutableCurrency, saving | Unused-account-only opening fact, immutable account currency, date and no-statistics explanation |
| `SETG-004` | `settings/currencies` | content, searching | Legal-tender search, visible/hidden toggle, persistent order, and non-hideable base/account currencies |

The five screens cover 15 required states. Specialized recording reuses the P04 governed components and P11 fixed root action; it does not introduce a local Material theme, application numeric keyboard, dropdown category, feature DAO, or route-carried amount/note/account name.

## Exact amount and evidence chain

| Layer | P14 mapping |
|---|---|
| User/account authority | `MoneyExpressionEvaluator` produces checked integer minor units. `SpecializedAccountAmountDraft` carries the authoritative account amount and an explicit base amount; no Float/Double is present in the write path. |
| Same-currency transfer | Outgoing and incoming account minors must be equal. P06 produces balanced transfer Journals and the P14 SQLCipher test proves the widget net-financial-asset delta is zero. |
| Cross-currency transfer | Both account amounts are authoritative. The outgoing account-to-base evidence and incoming implied-from-actual-amounts evidence freeze one equal base amount, so the Journal is balanced without changing either account amount. |
| FX exchange | Outgoing and incoming account/base evidence are independently frozen. Their exact base-minor difference is the explicit spread cost; P06 emits the clearing/cost/gain/rounding Journal and economic effect only when the difference is nonzero. |
| Opening/adjustment | Dedicated payloads have no classification. Device queries prove they create no EconomicEffect or BudgetEffect and therefore do not pollute income, expense, consumption, or budget statistics. |
| Historical immutability | `revision_amount` and `fx_rate_snapshot` are appended by `RoomFinancialPlanWriter`. Later online/cache refresh changes only current valuation projection rows and never updates historical facts. |

All four formal writes terminate at `FinancialMutationCoordinator`, then the P08 repository writes commit/revision/facts/effects/current pointers/receipt/projections/`book.localRevision` in one SQLCipher transaction. ViewModel and feature code create typed application requests only; they cannot create Journal, Posting, DAO, Entity, or SQL.

## Offline rate and valuation policy

- The OkHttp adapter sends only source currency, target currency, and effective date to the HTTPS rate service. It has bounded timeouts, cancellation, one explicit retry, strict decimal parsing, and no logging interceptor.
- A quote for the current UTC date may replace `account_valuation_current`; that transaction advances only `book.valuationRevision`, restamps valuation-dependent widget rows, and preserves `book.localRevision`.
- A historical-date response is labeled `HISTORICAL_FALLBACK`, may be frozen into the submitted revision, and cannot replace current valuation.
- Offline lookup derives a pair from encrypted `account_valuation_current` rates and preserves the quote timestamp. Stale cache is visibly marked. If no cache exists, Save fails closed until the user enters a positive manual account-to-base rate; no zero or guessed rate is synthesized.
- Manual, cached, latest, historical, and implied evidence retain distinct `FxRateSource`, provider, quoted/fetched time, rate, source/target currency, account amount, and base amount fields.

## Checkpoint fact decision

`account_balance_checkpoint` is an immutable Fact under the frozen schema. P14 therefore never updates its nullable association column. `balance_adjustment_revision_detail.checkpoint_id` is the immutable authoritative link, and the checkpoint read model derives the reverse transaction association from the current revision. This keeps checkpoint creation balance-neutral and preserves all append-only triggers.

## Automated evidence map

| Acceptance | Evidence |
|---|---|
| Exact YAML/source/privacy/ledger boundary | `P14-E001`, six mutation tests |
| Exact rates, dual amounts, spread and no fabricated zero | `P14-E002`, `P14-E003` |
| SQLCipher atomicity, idempotency, balanced Journals, statistics exclusion, immutable history | `P14-E004` |
| 13 REC states plus SETG-004 content/search, width/font/locale/theme/accessibility | `P14-E005` |
| Four contract/token-only exact goldens | `P14-E006` |
| Architecture, source policy, Lint, formatting and dependency checks | `P14-E007` |
| Frozen-source/repository hygiene and compiled application integration | `P14-E008` |

No later stage is promoted by P14. JRN detail/history/edit behavior begins at P15; other specialized entries remain P15/P16/P19/P21; global internationalization/settings acceptance remains P33/P34/P36.
