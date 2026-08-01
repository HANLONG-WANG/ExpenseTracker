-- Ledger primary database schema v1: projections, encrypted operation metadata, search and geo indexes.
CREATE TABLE current_transaction_projection (
  transaction_id INTEGER PRIMARY KEY REFERENCES business_transaction(id) ON DELETE CASCADE,
  transaction_uid BLOB NOT NULL UNIQUE CHECK (length(transaction_uid) = 16), kind INTEGER NOT NULL CHECK (kind BETWEEN 0 AND 10),
  state INTEGER NOT NULL CHECK (state BETWEEN 0 AND 1), current_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE CASCADE,
  occurred_at INTEGER NOT NULL, local_date INTEGER NOT NULL, primary_account_id INTEGER REFERENCES user_account(id) ON DELETE SET NULL,
  secondary_account_id INTEGER REFERENCES user_account(id) ON DELETE SET NULL, card_id INTEGER REFERENCES payment_card(id) ON DELETE SET NULL,
  category_id INTEGER REFERENCES category(id) ON DELETE SET NULL, merchant_id INTEGER REFERENCES merchant(id) ON DELETE SET NULL,
  project_id INTEGER REFERENCES project(id) ON DELETE SET NULL, goal_id INTEGER REFERENCES goal(id) ON DELETE SET NULL,
  settlement_activity_id INTEGER REFERENCES settlement_activity(id) ON DELETE SET NULL,
  payer_participant_id INTEGER REFERENCES participant(id) ON DELETE SET NULL,
  input_amount_minor INTEGER NOT NULL CHECK (input_amount_minor > 0), input_currency TEXT NOT NULL CHECK (length(input_currency) = 3),
  account_amount_minor INTEGER NOT NULL CHECK (account_amount_minor > 0), account_currency TEXT NOT NULL CHECK (length(account_currency) = 3),
  economic_base_minor INTEGER, note_preview TEXT, has_attachment INTEGER NOT NULL CHECK (has_attachment IN (0,1)),
  has_location INTEGER NOT NULL CHECK (has_location IN (0,1)), is_refund INTEGER NOT NULL CHECK (is_refund IN (0,1)),
  is_refunded INTEGER NOT NULL CHECK (is_refunded IN (0,1)), has_installment INTEGER NOT NULL CHECK (has_installment IN (0,1)),
  source_type INTEGER NOT NULL CHECK (source_type >= 0), as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE account_balance_current (
  account_id INTEGER PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE, normal_balance_minor INTEGER NOT NULL,
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), total_debit_minor INTEGER NOT NULL CHECK (total_debit_minor >= 0),
  total_credit_minor INTEGER NOT NULL CHECK (total_credit_minor >= 0), as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE account_valuation_current (
  account_id INTEGER PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE, balance_minor INTEGER NOT NULL,
  current_base_value_minor INTEGER NOT NULL, rate_decimal TEXT NOT NULL, rate_quoted_at INTEGER,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  as_of_valuation_revision INTEGER NOT NULL CHECK (as_of_valuation_revision >= 0)
)
--@@
CREATE TABLE account_balance_daily (
  account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE CASCADE, local_date INTEGER NOT NULL,
  opening_minor INTEGER NOT NULL, inflow_minor INTEGER NOT NULL CHECK (inflow_minor >= 0), outflow_minor INTEGER NOT NULL CHECK (outflow_minor >= 0),
  closing_minor INTEGER NOT NULL, currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (account_id, local_date)
)
--@@
CREATE TABLE budget_usage_projection (
  year_month INTEGER NOT NULL, category_id INTEGER REFERENCES category(id) ON DELETE CASCADE,
  base_budget_minor INTEGER NOT NULL CHECK (base_budget_minor >= 0), rollover_minor INTEGER NOT NULL,
  adjustment_minor INTEGER NOT NULL, used_minor INTEGER NOT NULL, remaining_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, category_id)
)
--@@
CREATE TABLE budget_future_reservation (
  year_month INTEGER NOT NULL, recurrence_series_id INTEGER NOT NULL REFERENCES recurrence_series(id) ON DELETE CASCADE,
  occurrence_date INTEGER NOT NULL, reserved_base_minor INTEGER NOT NULL CHECK (reserved_base_minor >= 0),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  PRIMARY KEY (year_month, recurrence_series_id, occurrence_date)
)
--@@
CREATE TABLE project_usage_projection (
  project_id INTEGER PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE, budget_base_minor INTEGER CHECK (budget_base_minor IS NULL OR budget_base_minor >= 0),
  used_base_minor INTEGER NOT NULL, restored_base_minor INTEGER NOT NULL, remaining_base_minor INTEGER,
  cash_inflow_base_minor INTEGER NOT NULL, cash_outflow_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE goal_balance_projection (
  goal_id INTEGER PRIMARY KEY REFERENCES goal(id) ON DELETE CASCADE, balance_minor INTEGER NOT NULL,
  target_minor INTEGER NOT NULL CHECK (target_minor > 0), currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE credit_statement_projection (
  statement_id INTEGER PRIMARY KEY REFERENCES credit_statement(id) ON DELETE CASCADE,
  estimated_amount_minor INTEGER NOT NULL CHECK (estimated_amount_minor >= 0), official_amount_minor INTEGER,
  paid_amount_minor INTEGER NOT NULL CHECK (paid_amount_minor >= 0), remaining_amount_minor INTEGER NOT NULL,
  status INTEGER NOT NULL CHECK (status >= 0), as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE credit_account_projection (
  account_id INTEGER PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE, debt_minor INTEGER NOT NULL CHECK (debt_minor >= 0),
  available_limit_minor INTEGER, estimated_unbilled_minor INTEGER NOT NULL, overdue_minor INTEGER NOT NULL CHECK (overdue_minor >= 0),
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE installment_progress_projection (
  plan_id INTEGER PRIMARY KEY REFERENCES installment_plan(id) ON DELETE CASCADE, principal_minor INTEGER NOT NULL CHECK (principal_minor >= 0),
  posted_principal_minor INTEGER NOT NULL CHECK (posted_principal_minor >= 0),
  unposted_committed_principal_minor INTEGER NOT NULL CHECK (unposted_committed_principal_minor >= 0),
  fees_minor INTEGER NOT NULL CHECK (fees_minor >= 0), next_statement_date INTEGER,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE loan_progress_projection (
  contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE CASCADE,
  tranche_id INTEGER NOT NULL REFERENCES loan_tranche(id) ON DELETE CASCADE,
  original_principal_minor INTEGER NOT NULL CHECK (original_principal_minor > 0), repaid_principal_minor INTEGER NOT NULL CHECK (repaid_principal_minor >= 0),
  remaining_principal_minor INTEGER NOT NULL CHECK (remaining_principal_minor >= 0), accrued_interest_minor INTEGER NOT NULL CHECK (accrued_interest_minor >= 0),
  next_payment_date INTEGER, as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (contract_id, tranche_id)
)
--@@
CREATE TABLE loan_future_cashflow_projection (
  contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE CASCADE,
  tranche_id INTEGER NOT NULL REFERENCES loan_tranche(id) ON DELETE CASCADE, planned_date INTEGER NOT NULL,
  principal_minor INTEGER NOT NULL CHECK (principal_minor >= 0), interest_minor INTEGER NOT NULL CHECK (interest_minor >= 0),
  fee_minor INTEGER NOT NULL CHECK (fee_minor >= 0), currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  schedule_revision_id INTEGER NOT NULL REFERENCES loan_schedule_revision(id) ON DELETE CASCADE,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  PRIMARY KEY (contract_id, tranche_id, planned_date, schedule_revision_id)
)
--@@
CREATE TABLE settlement_position_projection (
  activity_id INTEGER NOT NULL REFERENCES settlement_activity(id) ON DELETE CASCADE,
  participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE CASCADE,
  paid_minor INTEGER NOT NULL CHECK (paid_minor >= 0), owed_minor INTEGER NOT NULL CHECK (owed_minor >= 0),
  settled_paid_minor INTEGER NOT NULL CHECK (settled_paid_minor >= 0), settled_received_minor INTEGER NOT NULL CHECK (settled_received_minor >= 0),
  net_position_minor INTEGER NOT NULL, as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  PRIMARY KEY (activity_id, participant_id)
)
--@@
CREATE TABLE analytics_daily_total (
  local_date INTEGER NOT NULL, metric INTEGER NOT NULL CHECK (metric >= 0), amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (local_date, metric)
)
--@@
CREATE TABLE analytics_daily_category (
  local_date INTEGER NOT NULL, category_id INTEGER NOT NULL REFERENCES category(id) ON DELETE CASCADE,
  nature INTEGER NOT NULL CHECK (nature >= 0), amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (local_date, category_id, nature)
)
--@@
CREATE TABLE analytics_daily_account (
  local_date INTEGER NOT NULL, account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  inflow_base_minor INTEGER NOT NULL, outflow_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (local_date, account_id)
)
--@@
CREATE TABLE analytics_daily_merchant (
  local_date INTEGER NOT NULL, merchant_id INTEGER NOT NULL REFERENCES merchant(id) ON DELETE CASCADE, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (local_date, merchant_id)
)
--@@
CREATE TABLE analytics_daily_project (
  local_date INTEGER NOT NULL, project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (local_date, project_id)
)
--@@
CREATE TABLE analytics_daily_place (
  local_date INTEGER NOT NULL, place_id INTEGER NOT NULL REFERENCES place(id) ON DELETE CASCADE, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (local_date, place_id)
)
--@@
CREATE TABLE analytics_monthly_total (
  year_month INTEGER NOT NULL, metric INTEGER NOT NULL CHECK (metric >= 0), amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, metric)
)
--@@
CREATE TABLE analytics_monthly_category (
  year_month INTEGER NOT NULL, category_id INTEGER NOT NULL REFERENCES category(id) ON DELETE CASCADE,
  nature INTEGER NOT NULL CHECK (nature >= 0), amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, category_id, nature)
)
--@@
CREATE TABLE analytics_monthly_account (
  year_month INTEGER NOT NULL, account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
  inflow_base_minor INTEGER NOT NULL, outflow_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, account_id)
)
--@@
CREATE TABLE analytics_monthly_merchant (
  year_month INTEGER NOT NULL, merchant_id INTEGER NOT NULL REFERENCES merchant(id) ON DELETE CASCADE, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, merchant_id)
)
--@@
CREATE TABLE analytics_monthly_project (
  year_month INTEGER NOT NULL, project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, project_id)
)
--@@
CREATE TABLE analytics_monthly_place (
  year_month INTEGER NOT NULL, place_id INTEGER NOT NULL REFERENCES place(id) ON DELETE CASCADE, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0), PRIMARY KEY (year_month, place_id)
)
--@@
CREATE TABLE widget_book_snapshot (
  id INTEGER PRIMARY KEY CHECK (id = 1), core_net_financial_assets_base_minor INTEGER NOT NULL,
  adjusted_net_financial_position_base_minor INTEGER NOT NULL, base_currency TEXT NOT NULL CHECK (length(base_currency) = 3),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  as_of_valuation_revision INTEGER NOT NULL CHECK (as_of_valuation_revision >= 0)
)
--@@
CREATE TABLE widget_account_snapshot (
  account_id INTEGER PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE, balance_minor INTEGER NOT NULL,
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE widget_credit_snapshot (
  account_id INTEGER PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE, debt_minor INTEGER NOT NULL CHECK (debt_minor >= 0),
  available_limit_minor INTEGER, currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE widget_goal_snapshot (
  goal_id INTEGER PRIMARY KEY REFERENCES goal(id) ON DELETE CASCADE, balance_minor INTEGER NOT NULL,
  target_minor INTEGER NOT NULL CHECK (target_minor > 0), currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0)
)
--@@
CREATE TABLE background_operation (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), type INTEGER NOT NULL CHECK (type >= 0),
  state INTEGER NOT NULL CHECK (state BETWEEN 0 AND 9), created_at INTEGER NOT NULL, started_at INTEGER, updated_at INTEGER NOT NULL,
  progress_current INTEGER NOT NULL CHECK (progress_current >= 0), progress_total INTEGER CHECK (progress_total IS NULL OR progress_total >= progress_current),
  checkpoint_version INTEGER NOT NULL CHECK (checkpoint_version >= 0), error_code TEXT,
  cancel_requested INTEGER NOT NULL CHECK (cancel_requested IN (0,1)), parameters_ciphertext BLOB NOT NULL
)
--@@
CREATE TABLE operation_checkpoint (
  operation_id INTEGER NOT NULL REFERENCES background_operation(id) ON DELETE CASCADE, sequence INTEGER NOT NULL CHECK (sequence >= 0),
  phase INTEGER NOT NULL CHECK (phase BETWEEN 0 AND 9), checkpoint_ciphertext BLOB NOT NULL, created_at INTEGER NOT NULL,
  PRIMARY KEY (operation_id, sequence)
)
--@@
CREATE TABLE import_record (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  operation_id INTEGER NOT NULL UNIQUE REFERENCES background_operation(id) ON DELETE RESTRICT,
  format INTEGER NOT NULL CHECK (format >= 0), source_fingerprint BLOB NOT NULL CHECK (length(source_fingerprint) = 32),
  imported_at INTEGER, committed_local_revision INTEGER CHECK (committed_local_revision IS NULL OR committed_local_revision >= 0)
)
--@@
CREATE TABLE import_batch_commit (
  import_record_id INTEGER NOT NULL REFERENCES import_record(id) ON DELETE RESTRICT,
  batch_uid BLOB NOT NULL CHECK (length(batch_uid) = 16), commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  first_row_number INTEGER NOT NULL CHECK (first_row_number > 0), last_row_number INTEGER NOT NULL CHECK (last_row_number >= first_row_number),
  PRIMARY KEY (import_record_id, batch_uid)
)
--@@
CREATE TABLE import_source_reference (
  import_record_id INTEGER NOT NULL REFERENCES import_record(id) ON DELETE RESTRICT, row_number INTEGER NOT NULL CHECK (row_number > 0),
  transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  source_row_hash BLOB NOT NULL CHECK (length(source_row_hash) = 32), PRIMARY KEY (import_record_id, row_number)
)
--@@
CREATE TABLE backup_repository (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), kind INTEGER NOT NULL CHECK (kind >= 0),
  handle_uid BLOB NOT NULL CHECK (length(handle_uid) = 16), enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  created_at INTEGER NOT NULL, last_verified_at INTEGER
)
--@@
CREATE TABLE backup_snapshot (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  repository_id INTEGER NOT NULL REFERENCES backup_repository(id) ON DELETE RESTRICT,
  head_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, local_revision INTEGER NOT NULL CHECK (local_revision >= 0),
  created_at INTEGER NOT NULL, state INTEGER NOT NULL CHECK (state >= 0), manifest_hash BLOB CHECK (manifest_hash IS NULL OR length(manifest_hash) = 32)
)
--@@
CREATE TABLE backup_object (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  repository_id INTEGER NOT NULL REFERENCES backup_repository(id) ON DELETE RESTRICT,
  content_hash BLOB NOT NULL CHECK (length(content_hash) = 32), size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
  kind INTEGER NOT NULL CHECK (kind >= 0), created_at INTEGER NOT NULL, UNIQUE (repository_id, content_hash, size_bytes)
)
--@@
CREATE TABLE backup_snapshot_object (
  snapshot_id INTEGER NOT NULL REFERENCES backup_snapshot(id) ON DELETE CASCADE,
  object_id INTEGER NOT NULL REFERENCES backup_object(id) ON DELETE RESTRICT, ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  PRIMARY KEY (snapshot_id, object_id), UNIQUE (snapshot_id, ordinal)
)
--@@
CREATE TABLE drive_upload_session (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  snapshot_id INTEGER NOT NULL REFERENCES backup_snapshot(id) ON DELETE RESTRICT,
  repository_id INTEGER NOT NULL REFERENCES backup_repository(id) ON DELETE RESTRICT, state INTEGER NOT NULL CHECK (state >= 0),
  remote_session_ciphertext BLOB NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
)
--@@
CREATE TABLE restore_record (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  operation_id INTEGER NOT NULL UNIQUE REFERENCES background_operation(id) ON DELETE RESTRICT,
  mode INTEGER NOT NULL CHECK (mode >= 0), snapshot_id INTEGER NOT NULL REFERENCES backup_snapshot(id) ON DELETE RESTRICT,
  state INTEGER NOT NULL CHECK (state >= 0), source_head_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  live_head_at_start INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  resulting_head_commit_id INTEGER REFERENCES book_commit(id) ON DELETE RESTRICT, validated_at INTEGER
)
--@@
CREATE TABLE merge_session (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  operation_id INTEGER NOT NULL UNIQUE REFERENCES background_operation(id) ON DELETE RESTRICT,
  common_ancestor_commit_id INTEGER REFERENCES book_commit(id) ON DELETE RESTRICT,
  local_head_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  incoming_head_commit_uid BLOB NOT NULL CHECK (length(incoming_head_commit_uid) = 16), state INTEGER NOT NULL CHECK (state >= 0)
)
--@@
CREATE TABLE merge_conflict (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  session_id INTEGER NOT NULL REFERENCES merge_session(id) ON DELETE CASCADE, kind INTEGER NOT NULL CHECK (kind >= 0),
  entity_type INTEGER NOT NULL CHECK (entity_type >= 0), entity_uid BLOB NOT NULL CHECK (length(entity_uid) = 16),
  ancestor_hash BLOB CHECK (ancestor_hash IS NULL OR length(ancestor_hash) = 32),
  local_hash BLOB CHECK (local_hash IS NULL OR length(local_hash) = 32),
  incoming_hash BLOB CHECK (incoming_hash IS NULL OR length(incoming_hash) = 32),
  purge_generation INTEGER, resolution INTEGER, UNIQUE (session_id, entity_type, entity_uid)
)
--@@
CREATE TABLE merge_resolution (
  conflict_id INTEGER PRIMARY KEY REFERENCES merge_conflict(id) ON DELETE CASCADE,
  resolution INTEGER NOT NULL CHECK (resolution >= 0), resolved_at INTEGER NOT NULL,
  resulting_commit_id INTEGER REFERENCES book_commit(id) ON DELETE RESTRICT
)
--@@
CREATE VIRTUAL TABLE transaction_fts USING fts5(
  transaction_id UNINDEXED, category_name, merchant_name, merchant_aliases, note,
  project_name, settlement_activity_name, participant_names, attachment_names,
  lifecycle_state UNINDEXED, tokenize='trigram case_sensitive 0'
)
--@@
CREATE VIRTUAL TABLE location_rtree USING rtree(location_id, min_lat, max_lat, min_lon, max_lon)
--@@
CREATE VIRTUAL TABLE place_rtree USING rtree(place_id, min_lat, max_lat, min_lon, max_lon)
