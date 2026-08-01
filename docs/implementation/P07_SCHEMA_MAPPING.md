# P07 Schema v1 Mapping

Generated from the checked-in SQL contracts by `scripts/generate_p07_schema_catalog.py`; do not edit rows manually.
The four excluded visual drafts are not generator inputs and were not accessed.

## Frozen §25 logical tables

| Family | Table | Columns | PK | FK | CHECK | UNIQUE / index |
|---|---|---:|---|---|---|---|
| SCHEMA-FAMILY-01 | `book` | 11 | YES | YES | YES | YES |
| SCHEMA-FAMILY-01 | `book_commit` | 8 | YES | N/A | YES | YES |
| SCHEMA-FAMILY-01 | `book_commit_parent` | 3 | YES | YES | YES | YES |
| SCHEMA-FAMILY-01 | `command_receipt` | 6 | YES | YES | YES | YES |
| SCHEMA-FAMILY-01 | `entity_change` | 7 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-01 | `entity_revision` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-01 | `purge_tombstone` | 5 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-02 | `ledger_account` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-02 | `user_account` | 17 | YES | YES | YES | YES |
| SCHEMA-FAMILY-02 | `payment_card` | 13 | YES | YES | YES | YES |
| SCHEMA-FAMILY-02 | `card_vault_secret` | 8 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-02 | `account_balance_checkpoint` | 11 | YES | YES | YES | YES |
| SCHEMA-FAMILY-03 | `category` | 17 | YES | YES | YES | YES |
| SCHEMA-FAMILY-03 | `merchant` | 8 | YES | YES | YES | YES |
| SCHEMA-FAMILY-03 | `merchant_alias` | 3 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-03 | `place` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-03 | `location_record` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-03 | `location_rtree` | 5 | YES | YES | YES | YES |
| SCHEMA-FAMILY-03 | `place_rtree` | 5 | YES | YES | YES | YES |
| SCHEMA-FAMILY-04 | `business_transaction` | 11 | YES | YES | YES | YES |
| SCHEMA-FAMILY-04 | `transaction_revision` | 23 | YES | YES | YES | YES |
| SCHEMA-FAMILY-04 | `revision_amount` | 9 | YES | YES | YES | YES |
| SCHEMA-FAMILY-04 | `fx_rate_snapshot` | 12 | YES | YES | YES | YES |
| SCHEMA-FAMILY-04 | `expense_revision_detail` | 7 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `income_revision_detail` | 2 | YES | YES | N/A | INDEXED |
| SCHEMA-FAMILY-04 | `transfer_revision_detail` | 4 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `refund_revision_detail` | 7 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `credit_payment_revision_detail` | 4 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `loan_disbursement_revision_detail` | 3 | YES | YES | N/A | INDEXED |
| SCHEMA-FAMILY-04 | `loan_payment_revision_detail` | 4 | YES | YES | N/A | INDEXED |
| SCHEMA-FAMILY-04 | `balance_adjustment_revision_detail` | 4 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `fx_exchange_revision_detail` | 4 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `settlement_payment_revision_detail` | 5 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `opening_balance_revision_detail` | 3 | YES | YES | N/A | INDEXED |
| SCHEMA-FAMILY-04 | `transaction_revision_attachment` | 3 | YES | YES | YES | YES |
| SCHEMA-FAMILY-04 | `transaction_revision_settlement_share` | 8 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-04 | `transaction_dependency` | 3 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-05 | `journal_entry` | 16 | YES | YES | YES | YES |
| SCHEMA-FAMILY-05 | `posting` | 13 | YES | YES | YES | YES |
| SCHEMA-FAMILY-05 | `economic_effect` | 15 | YES | YES | YES | YES |
| SCHEMA-FAMILY-05 | `budget_effect` | 10 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-05 | `project_effect` | 9 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-05 | `goal_effect` | 10 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-05 | `statement_effect` | 11 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-05 | `loan_effect` | 12 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-05 | `settlement_effect` | 10 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-06 | `refund_allocation` | 9 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-06 | `refund_status_projection` | 6 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-07 | `credit_account_profile` | 14 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-07 | `credit_limit_period` | 6 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `credit_statement` | 8 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `credit_statement_revision` | 12 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `credit_payment_allocation` | 6 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-07 | `installment_plan` | 9 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `installment_plan_revision` | 14 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `installment_schedule_revision` | 7 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `installment_schedule_item` | 8 | YES | YES | YES | YES |
| SCHEMA-FAMILY-07 | `installment_refund_allocation` | 7 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-08 | `loan_contract` | 9 | YES | YES | YES | YES |
| SCHEMA-FAMILY-08 | `loan_tranche` | 7 | YES | YES | YES | YES |
| SCHEMA-FAMILY-08 | `loan_terms_revision` | 12 | YES | YES | YES | YES |
| SCHEMA-FAMILY-08 | `loan_rate_period` | 7 | YES | YES | YES | YES |
| SCHEMA-FAMILY-08 | `loan_schedule_revision` | 8 | YES | YES | YES | YES |
| SCHEMA-FAMILY-08 | `loan_schedule_item` | 8 | YES | YES | YES | YES |
| SCHEMA-FAMILY-08 | `loan_actual_allocation` | 9 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-08 | `loan_simulation` | 6 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-08 | `loan_simulation_item` | 7 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-09 | `participant` | 6 | YES | YES | YES | YES |
| SCHEMA-FAMILY-09 | `settlement_activity` | 11 | YES | YES | YES | YES |
| SCHEMA-FAMILY-09 | `settlement_activity_participant` | 5 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-09 | `settlement_payment_record` | 11 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `project` | 12 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `goal` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `goal_movement` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `budget_template` | 5 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `budget_template_revision` | 6 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `budget_template_category_limit` | 3 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-10 | `budget_month` | 4 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `budget_month_revision` | 7 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `budget_category_limit` | 3 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-10 | `budget_adjustment` | 9 | YES | YES | YES | YES |
| SCHEMA-FAMILY-10 | `budget_rollover` | 6 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-11 | `transaction_blueprint` | 7 | YES | YES | YES | YES |
| SCHEMA-FAMILY-11 | `transaction_blueprint_revision` | 18 | YES | YES | YES | YES |
| SCHEMA-FAMILY-11 | `blueprint_settlement_share_rule` | 7 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-11 | `recurrence_series` | 5 | YES | YES | YES | YES |
| SCHEMA-FAMILY-11 | `recurrence_series_revision` | 18 | YES | YES | YES | YES |
| SCHEMA-FAMILY-11 | `recurrence_rule_weekday` | 2 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-11 | `recurrence_exception` | 5 | YES | YES | YES | INDEXED |
| SCHEMA-FAMILY-11 | `recurrence_occurrence` | 10 | YES | YES | YES | YES |
| SCHEMA-FAMILY-11 | `recurrence_candidate` | 7 | YES | YES | YES | YES |
| SCHEMA-FAMILY-12 | `encrypted_blob` | 11 | YES | N/A | YES | YES |
| SCHEMA-FAMILY-12 | `attachment` | 6 | YES | YES | YES | YES |
| SCHEMA-FAMILY-12 | `blob_gc_candidate` | 4 | YES | YES | YES | INDEXED |

## Additional schema-governed data

The primary catalog contains 140 declared tables in total: the 94 frozen §25 tables, `rule_set_version`, all §26 projections, §27 FTS5/R*Tree indexes, and §28 durable operation/import/backup/restore metadata.
The primary DDL also contains 39 named indexes, four diagnostic views, ten cross-row constraint triggers, and runtime-generated append-only update/delete guards for 63 immutable Revision/Fact tables.

## Independent encrypted import staging schema

| Table | Columns | PK | FK | CHECK |
|---|---:|---|---|---|
| `staging_raw_row` | 4 | YES | N/A | YES |
| `staging_parsed_row` | 4 | YES | YES | YES |
| `staging_mapping` | 4 | YES | N/A | YES |
| `staging_validation_error` | 5 | YES | YES | YES |
| `staging_duplicate_candidate` | 6 | YES | YES | YES |
| `staging_prepared_command` | 6 | YES | YES | YES |
| `staging_attachment` | 6 | YES | YES | YES |

## Version and migration policy

- Room primary schema: `app.ledger.core.database.LedgerDatabase/1.json`.
- Room staging schema: `app.ledger.core.database.ImportStagingDatabase/1.json`.
- Canonical raw-DDL exports: `ledger-primary-v1.json` and `import-staging-v1.json`.
- v1 has no predecessor. Future adjacent versions must register explicit migrations and follow Expand → Backfill → Switch → Contract.
- Formal builders have no destructive fallback; primary and staging versions are independent.
