-- Ledger primary database schema v1: normalized Current/Revision/Fact tables.
-- Statements are split by the fail-closed loader only on the marker below.
CREATE TABLE book (
  id INTEGER PRIMARY KEY CHECK (id = 1), uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  base_currency TEXT NOT NULL CHECK (length(base_currency) = 3), default_zone_id TEXT NOT NULL,
  head_commit_id INTEGER REFERENCES book_commit(id) ON DELETE RESTRICT, local_revision INTEGER NOT NULL CHECK (local_revision >= 0),
  valuation_revision INTEGER NOT NULL CHECK (valuation_revision >= 0), rule_set_version INTEGER NOT NULL CHECK (rule_set_version > 0),
  created_at INTEGER NOT NULL, first_financial_commit_at INTEGER, state INTEGER NOT NULL CHECK (state BETWEEN 0 AND 2)
)
--@@
CREATE TABLE rule_set_version (
  version INTEGER PRIMARY KEY CHECK (version > 0), algorithm_hash BLOB NOT NULL CHECK (length(algorithm_hash) = 32),
  activated_at INTEGER NOT NULL, retired_at INTEGER, CHECK (retired_at IS NULL OR retired_at >= activated_at)
)
--@@
CREATE TABLE book_commit (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), local_revision INTEGER NOT NULL UNIQUE CHECK (local_revision > 0),
  kind INTEGER NOT NULL CHECK (kind >= 0), command_uid BLOB UNIQUE CHECK (command_uid IS NULL OR length(command_uid) = 16),
  device_instance_uid BLOB NOT NULL CHECK (length(device_instance_uid) = 16), created_at INTEGER NOT NULL,
  root_hash BLOB NOT NULL CHECK (length(root_hash) = 32)
)
--@@
CREATE TABLE book_commit_parent (
  commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  parent_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0), PRIMARY KEY (commit_id, parent_commit_id), UNIQUE (commit_id, ordinal),
  CHECK (commit_id <> parent_commit_id)
)
--@@
CREATE TABLE command_receipt (
  command_uid BLOB PRIMARY KEY CHECK (length(command_uid) = 16), command_type INTEGER NOT NULL CHECK (command_type >= 0),
  payload_hash BLOB NOT NULL CHECK (length(payload_hash) = 32), commit_id INTEGER NOT NULL UNIQUE REFERENCES book_commit(id) ON DELETE RESTRICT,
  primary_entity_uid BLOB CHECK (primary_entity_uid IS NULL OR length(primary_entity_uid) = 16), executed_at INTEGER NOT NULL
)
--@@
CREATE TABLE entity_change (
  commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, entity_type INTEGER NOT NULL CHECK (entity_type >= 0),
  entity_uid BLOB NOT NULL CHECK (length(entity_uid) = 16), operation INTEGER NOT NULL CHECK (operation >= 0),
  before_hash BLOB CHECK (before_hash IS NULL OR length(before_hash) = 32), after_hash BLOB CHECK (after_hash IS NULL OR length(after_hash) = 32),
  entity_revision_uid BLOB CHECK (entity_revision_uid IS NULL OR length(entity_revision_uid) = 16),
  PRIMARY KEY (commit_id, entity_type, entity_uid)
)
--@@
CREATE TABLE entity_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), entity_type INTEGER NOT NULL CHECK (entity_type >= 0),
  entity_uid BLOB NOT NULL CHECK (length(entity_uid) = 16), revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  action INTEGER NOT NULL CHECK (action >= 0), commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  content_hash BLOB NOT NULL CHECK (length(content_hash) = 32), canonical_snapshot_blob BLOB NOT NULL,
  schema_version INTEGER NOT NULL CHECK (schema_version > 0), UNIQUE (entity_type, entity_uid, revision_no)
)
--@@
CREATE TABLE purge_tombstone (
  entity_type INTEGER NOT NULL CHECK (entity_type >= 0), entity_uid BLOB NOT NULL CHECK (length(entity_uid) = 16),
  purge_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, purged_at INTEGER NOT NULL,
  purge_generation INTEGER NOT NULL CHECK (purge_generation > 0), PRIMARY KEY (entity_type, entity_uid)
)
--@@
CREATE TABLE ledger_account (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), owner_type INTEGER NOT NULL CHECK (owner_type >= 0),
  account_class INTEGER NOT NULL CHECK (account_class >= 0), normal_side INTEGER NOT NULL CHECK (normal_side IN (0,1)),
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), parent_ledger_account_id INTEGER REFERENCES ledger_account(id) ON DELETE RESTRICT,
  system_code TEXT UNIQUE, status INTEGER NOT NULL CHECK (status >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE user_account (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  ledger_account_id INTEGER NOT NULL UNIQUE REFERENCES ledger_account(id) ON DELETE RESTRICT, type INTEGER NOT NULL CHECK (type BETWEEN 0 AND 3),
  name TEXT NOT NULL CHECK (length(name) > 0), currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  institution_name TEXT, branch_name TEXT, account_number TEXT, opened_date INTEGER, status INTEGER NOT NULL CHECK (status >= 0),
  icon_key TEXT NOT NULL, color_argb INTEGER NOT NULL, sort_order INTEGER NOT NULL,
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0),
  content_hash BLOB NOT NULL CHECK (length(content_hash) = 32)
)
--@@
CREATE TABLE payment_card (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  card_type INTEGER NOT NULL CHECK (card_type >= 0), display_name TEXT NOT NULL CHECK (length(display_name) > 0),
  last_four TEXT CHECK (last_four IS NULL OR (length(last_four) = 4 AND last_four NOT GLOB '*[^0-9]*')),
  status INTEGER NOT NULL CHECK (status >= 0), replacement_of_card_id INTEGER REFERENCES payment_card(id) ON DELETE RESTRICT,
  icon_key TEXT NOT NULL, color_argb INTEGER NOT NULL, sort_order INTEGER NOT NULL,
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0)
)
--@@
CREATE TABLE card_vault_secret (
  card_id INTEGER PRIMARY KEY REFERENCES payment_card(id) ON DELETE CASCADE,
  holder_name_ciphertext BLOB, pan_ciphertext BLOB, expiry_ciphertext BLOB, security_code_ciphertext BLOB, custom_fields_ciphertext BLOB,
  key_version INTEGER NOT NULL CHECK (key_version > 0), updated_at INTEGER NOT NULL
)
--@@
CREATE TABLE account_balance_checkpoint (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  as_of_instant INTEGER NOT NULL, as_of_local_date INTEGER NOT NULL, observed_amount_minor INTEGER NOT NULL,
  calculated_amount_minor INTEGER NOT NULL, difference_minor INTEGER NOT NULL,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  adjustment_transaction_id INTEGER REFERENCES business_transaction(id) ON DELETE RESTRICT, note TEXT
)
--@@
CREATE TABLE category (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), direction INTEGER NOT NULL CHECK (direction IN (0,1)),
  parent_id INTEGER REFERENCES category(id) ON DELETE RESTRICT, depth INTEGER NOT NULL CHECK (depth IN (1,2)),
  name TEXT NOT NULL CHECK (length(name) > 0), normalized_name TEXT NOT NULL CHECK (length(normalized_name) > 0),
  icon_key TEXT NOT NULL, color_argb INTEGER NOT NULL, sort_order INTEGER NOT NULL, status INTEGER NOT NULL CHECK (status >= 0),
  statistical_nature INTEGER NOT NULL CHECK (statistical_nature >= 0), default_account_id INTEGER REFERENCES user_account(id) ON DELETE SET NULL,
  default_card_id INTEGER REFERENCES payment_card(id) ON DELETE SET NULL, default_merchant_id INTEGER REFERENCES merchant(id) ON DELETE SET NULL,
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0),
  CHECK ((depth = 1 AND parent_id IS NULL) OR (depth = 2 AND parent_id IS NOT NULL))
)
--@@
CREATE TABLE merchant (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0),
  normalized_name TEXT NOT NULL CHECK (length(normalized_name) > 0), status INTEGER NOT NULL CHECK (status >= 0),
  merged_into_id INTEGER REFERENCES merchant(id) ON DELETE RESTRICT,
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0),
  CHECK (merged_into_id IS NULL OR merged_into_id <> id)
)
--@@
CREATE TABLE merchant_alias (
  merchant_id INTEGER NOT NULL REFERENCES merchant(id) ON DELETE CASCADE, alias TEXT NOT NULL CHECK (length(alias) > 0),
  normalized_alias TEXT NOT NULL CHECK (length(normalized_alias) > 0), PRIMARY KEY (merchant_id, normalized_alias)
)
--@@
CREATE TABLE place (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0),
  center_lat_e7 INTEGER NOT NULL CHECK (center_lat_e7 BETWEEN -900000000 AND 900000000),
  center_lon_e7 INTEGER NOT NULL CHECK (center_lon_e7 BETWEEN -1800000000 AND 1800000000),
  merchant_id INTEGER REFERENCES merchant(id) ON DELETE SET NULL, status INTEGER NOT NULL CHECK (status >= 0),
  merged_into_id INTEGER REFERENCES place(id) ON DELETE RESTRICT,
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0),
  CHECK (merged_into_id IS NULL OR merged_into_id <> id)
)
--@@
CREATE TABLE location_record (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  lat_e7 INTEGER NOT NULL CHECK (lat_e7 BETWEEN -900000000 AND 900000000),
  lon_e7 INTEGER NOT NULL CHECK (lon_e7 BETWEEN -1800000000 AND 1800000000),
  accuracy_mm INTEGER CHECK (accuracy_mm IS NULL OR accuracy_mm >= 0), captured_at INTEGER NOT NULL,
  source INTEGER NOT NULL CHECK (source >= 0), provider TEXT, place_id INTEGER REFERENCES place(id) ON DELETE SET NULL,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE project (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0), description TEXT,
  start_date INTEGER, end_date INTEGER, budget_base_minor INTEGER CHECK (budget_base_minor IS NULL OR budget_base_minor >= 0),
  included_in_monthly_budget INTEGER NOT NULL CHECK (included_in_monthly_budget IN (0,1)), goal_id INTEGER REFERENCES goal(id) ON DELETE SET NULL,
  status INTEGER NOT NULL CHECK (status >= 0), last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  row_version INTEGER NOT NULL CHECK (row_version > 0), CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
)
--@@
CREATE TABLE goal (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  name TEXT NOT NULL CHECK (length(name) > 0), target_amount_minor INTEGER NOT NULL CHECK (target_amount_minor > 0), due_date INTEGER,
  suggested_monthly_minor INTEGER CHECK (suggested_monthly_minor IS NULL OR suggested_monthly_minor >= 0), status INTEGER NOT NULL CHECK (status >= 0),
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0)
)
--@@
CREATE TABLE participant (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0),
  is_self INTEGER NOT NULL CHECK (is_self IN (0,1)), status INTEGER NOT NULL CHECK (status >= 0),
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE settlement_activity (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0), description TEXT,
  settlement_currency TEXT NOT NULL CHECK (length(settlement_currency) = 3), project_id INTEGER REFERENCES project(id) ON DELETE SET NULL,
  start_date INTEGER NOT NULL, end_date INTEGER, status INTEGER NOT NULL CHECK (status >= 0),
  requires_additional_settlement INTEGER NOT NULL CHECK (requires_additional_settlement IN (0,1)),
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, CHECK (end_date IS NULL OR end_date >= start_date)
)
--@@
CREATE TABLE settlement_activity_participant (
  activity_id INTEGER NOT NULL REFERENCES settlement_activity(id) ON DELETE RESTRICT,
  participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT, sort_order INTEGER NOT NULL,
  joined_at INTEGER NOT NULL, left_at INTEGER, PRIMARY KEY (activity_id, participant_id), CHECK (left_at IS NULL OR left_at >= joined_at)
)
--@@
CREATE TABLE business_transaction (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), kind INTEGER NOT NULL CHECK (kind BETWEEN 0 AND 10),
  current_revision_id INTEGER REFERENCES transaction_revision(id) ON DELETE RESTRICT, lifecycle_state INTEGER NOT NULL CHECK (lifecycle_state BETWEEN 0 AND 1),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, row_version INTEGER NOT NULL CHECK (row_version > 0),
  trashed_at INTEGER, purge_after INTEGER, content_hash BLOB NOT NULL CHECK (length(content_hash) = 32),
  CHECK ((lifecycle_state = 0 AND trashed_at IS NULL AND purge_after IS NULL) OR (lifecycle_state = 1 AND trashed_at IS NOT NULL AND purge_after IS NOT NULL))
)
--@@
CREATE TABLE fx_rate_snapshot (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  source_currency TEXT NOT NULL CHECK (length(source_currency) = 3), target_currency TEXT NOT NULL CHECK (length(target_currency) = 3),
  rate_decimal TEXT NOT NULL CHECK (CAST(rate_decimal AS REAL) > 0), provider TEXT NOT NULL, quoted_at INTEGER NOT NULL, fetched_at INTEGER,
  source_type INTEGER NOT NULL CHECK (source_type >= 0), manual_override INTEGER NOT NULL CHECK (manual_override IN (0,1)),
  stale_at_use INTEGER NOT NULL CHECK (stale_at_use IN (0,1)), created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  CHECK (source_currency <> target_currency), CHECK (fetched_at IS NULL OR fetched_at >= quoted_at)
)
--@@
CREATE TABLE transaction_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  action INTEGER NOT NULL CHECK (action >= 0), resulting_state INTEGER NOT NULL CHECK (resulting_state BETWEEN 0 AND 1),
  previous_revision_id INTEGER REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, created_at INTEGER NOT NULL, occurred_at INTEGER NOT NULL,
  zone_id TEXT NOT NULL, local_date INTEGER NOT NULL, category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT,
  statistical_nature_snapshot INTEGER, merchant_id INTEGER REFERENCES merchant(id) ON DELETE RESTRICT,
  project_id INTEGER REFERENCES project(id) ON DELETE RESTRICT, goal_id INTEGER REFERENCES goal(id) ON DELETE RESTRICT,
  location_record_id INTEGER REFERENCES location_record(id) ON DELETE RESTRICT, note TEXT, amount_expression TEXT,
  source_type INTEGER NOT NULL CHECK (source_type >= 0), source_reference_uid BLOB CHECK (source_reference_uid IS NULL OR length(source_reference_uid) = 16),
  content_hash BLOB NOT NULL CHECK (length(content_hash) = 32), UNIQUE (transaction_id, revision_no)
)
--@@
CREATE TABLE revision_amount (
  id INTEGER PRIMARY KEY, revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  component_index INTEGER NOT NULL CHECK (component_index >= 0), role INTEGER NOT NULL CHECK (role >= 0),
  representation INTEGER NOT NULL CHECK (representation BETWEEN 0 AND 2), amount_minor INTEGER NOT NULL,
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), related_account_id INTEGER REFERENCES user_account(id) ON DELETE RESTRICT,
  fx_rate_snapshot_id INTEGER REFERENCES fx_rate_snapshot(id) ON DELETE RESTRICT,
  UNIQUE (revision_id, component_index, role, representation),
  CHECK ((representation = 1 AND related_account_id IS NOT NULL) OR (representation <> 1 AND related_account_id IS NULL))
)
--@@
CREATE TABLE expense_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  payer_kind INTEGER NOT NULL CHECK (payer_kind >= 0), payer_account_id INTEGER REFERENCES user_account(id) ON DELETE RESTRICT,
  payer_card_id INTEGER REFERENCES payment_card(id) ON DELETE RESTRICT, payer_participant_id INTEGER REFERENCES participant(id) ON DELETE RESTRICT,
  settlement_activity_id INTEGER REFERENCES settlement_activity(id) ON DELETE RESTRICT,
  installment_plan_id INTEGER REFERENCES installment_plan(id) ON DELETE RESTRICT,
  CHECK ((payer_account_id IS NOT NULL) + (payer_participant_id IS NOT NULL) = 1)
)
--@@
CREATE TABLE income_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  receiving_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE transfer_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  from_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  to_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  source_card_id INTEGER REFERENCES payment_card(id) ON DELETE RESTRICT, CHECK (from_account_id <> to_account_id)
)
--@@
CREATE TABLE refund_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  receiving_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  receiving_card_id INTEGER REFERENCES payment_card(id) ON DELETE RESTRICT, independent INTEGER NOT NULL CHECK (independent IN (0,1)),
  budget_policy INTEGER NOT NULL CHECK (budget_policy >= 0), target_month INTEGER, allow_excess INTEGER NOT NULL CHECK (allow_excess IN (0,1))
)
--@@
CREATE TABLE credit_payment_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  payment_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  credit_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  generation_mode INTEGER NOT NULL CHECK (generation_mode >= 0), CHECK (payment_account_id <> credit_account_id)
)
--@@
CREATE TABLE loan_disbursement_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  loan_contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE RESTRICT,
  receiving_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE loan_payment_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  loan_contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE RESTRICT,
  payment_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  schedule_revision_id INTEGER REFERENCES loan_schedule_revision(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE balance_adjustment_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT, direction INTEGER NOT NULL CHECK (direction IN (0,1)),
  checkpoint_id INTEGER REFERENCES account_balance_checkpoint(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE fx_exchange_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  from_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  to_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  valuation_policy INTEGER NOT NULL CHECK (valuation_policy >= 0), CHECK (from_account_id <> to_account_id)
)
--@@
CREATE TABLE settlement_payment_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  activity_id INTEGER NOT NULL REFERENCES settlement_activity(id) ON DELETE RESTRICT,
  payer_participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT,
  payee_participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT,
  local_account_id INTEGER REFERENCES user_account(id) ON DELETE RESTRICT,
  CHECK (payer_participant_id <> payee_participant_id)
)
--@@
CREATE TABLE opening_balance_revision_detail (
  revision_id INTEGER PRIMARY KEY REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT, balance_date INTEGER NOT NULL
)
--@@
CREATE TABLE encrypted_blob (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), storage_name TEXT NOT NULL UNIQUE CHECK (length(storage_name) > 0),
  plaintext_sha256 BLOB NOT NULL CHECK (length(plaintext_sha256) = 32), plaintext_size INTEGER NOT NULL CHECK (plaintext_size >= 0),
  mime_type TEXT NOT NULL, extension TEXT, wrapped_data_key BLOB NOT NULL, encryption_version INTEGER NOT NULL CHECK (encryption_version > 0),
  reference_count_projection INTEGER NOT NULL CHECK (reference_count_projection >= 0), created_at INTEGER NOT NULL,
  UNIQUE (plaintext_sha256, plaintext_size)
)
--@@
CREATE TABLE attachment (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), blob_id INTEGER NOT NULL REFERENCES encrypted_blob(id) ON DELETE RESTRICT,
  display_name TEXT NOT NULL CHECK (length(display_name) > 0), imported_at INTEGER NOT NULL, status INTEGER NOT NULL CHECK (status >= 0)
)
--@@
CREATE TABLE transaction_revision_attachment (
  revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  attachment_id INTEGER NOT NULL REFERENCES attachment(id) ON DELETE RESTRICT,
  sort_order INTEGER NOT NULL CHECK (sort_order >= 0), PRIMARY KEY (revision_id, attachment_id), UNIQUE (revision_id, sort_order)
)
--@@
CREATE TABLE transaction_revision_settlement_share (
  revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  activity_id INTEGER NOT NULL REFERENCES settlement_activity(id) ON DELETE RESTRICT,
  participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT,
  paid_minor INTEGER NOT NULL CHECK (paid_minor >= 0), owed_minor INTEGER NOT NULL CHECK (owed_minor >= 0),
  settlement_currency TEXT NOT NULL CHECK (length(settlement_currency) = 3), weight_decimal TEXT,
  rounding_adjustment_minor INTEGER NOT NULL, PRIMARY KEY (revision_id, participant_id)
)
--@@
CREATE TABLE transaction_dependency (
  parent_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  child_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  dependency_type INTEGER NOT NULL CHECK (dependency_type >= 0), PRIMARY KEY (parent_transaction_id, child_transaction_id, dependency_type),
  CHECK (parent_transaction_id <> child_transaction_id)
)
--@@
CREATE TABLE journal_entry (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  applies_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  entry_role INTEGER NOT NULL CHECK (entry_role IN (0,1)), reverses_entry_id INTEGER REFERENCES journal_entry(id) ON DELETE RESTRICT,
  effective_at INTEGER NOT NULL, zone_id TEXT NOT NULL, local_date INTEGER NOT NULL,
  base_currency TEXT NOT NULL CHECK (length(base_currency) = 3), base_debit_total_minor INTEGER NOT NULL CHECK (base_debit_total_minor >= 0),
  base_credit_total_minor INTEGER NOT NULL CHECK (base_credit_total_minor >= 0), posting_count INTEGER NOT NULL CHECK (posting_count >= 2),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  content_hash BLOB NOT NULL CHECK (length(content_hash) = 32), CHECK (base_debit_total_minor = base_credit_total_minor)
)
--@@
CREATE TABLE posting (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), journal_entry_id INTEGER NOT NULL REFERENCES journal_entry(id) ON DELETE RESTRICT,
  line_no INTEGER NOT NULL CHECK (line_no > 0), ledger_account_id INTEGER NOT NULL REFERENCES ledger_account(id) ON DELETE RESTRICT,
  side INTEGER NOT NULL CHECK (side IN (0,1)), account_amount_minor INTEGER NOT NULL CHECK (account_amount_minor >= 0),
  account_currency TEXT NOT NULL CHECK (length(account_currency) = 3), base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  base_currency TEXT NOT NULL CHECK (length(base_currency) = 3), valuation_rate_decimal TEXT,
  posting_role INTEGER NOT NULL CHECK (posting_role >= 0), reversal_of_posting_id INTEGER REFERENCES posting(id) ON DELETE RESTRICT,
  UNIQUE (journal_entry_id, line_no)
)
--@@
CREATE TABLE economic_effect (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), source_entry_id INTEGER REFERENCES journal_entry(id) ON DELETE RESTRICT,
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT, reversal_of_id INTEGER REFERENCES economic_effect(id) ON DELETE RESTRICT,
  polarity INTEGER NOT NULL CHECK (polarity IN (-1,1)), nature INTEGER NOT NULL CHECK (nature >= 0), component INTEGER NOT NULL CHECK (component >= 0),
  is_consumption INTEGER NOT NULL CHECK (is_consumption IN (0,1)), base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  accrual_local_date INTEGER NOT NULL, category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT,
  merchant_id INTEGER REFERENCES merchant(id) ON DELETE RESTRICT, project_id INTEGER REFERENCES project(id) ON DELETE RESTRICT,
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
--@@
CREATE TABLE budget_effect (
  id INTEGER PRIMARY KEY, source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES budget_effect(id) ON DELETE RESTRICT, polarity INTEGER NOT NULL CHECK (polarity IN (-1,1)),
  kind INTEGER NOT NULL CHECK (kind >= 0), target_year_month INTEGER NOT NULL, category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT,
  root_category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT, base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
--@@
CREATE TABLE project_effect (
  id INTEGER PRIMARY KEY, project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES project_effect(id) ON DELETE RESTRICT, polarity INTEGER NOT NULL CHECK (polarity IN (-1,1)),
  kind INTEGER NOT NULL CHECK (kind >= 0), base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  monthly_budget_inclusion_snapshot INTEGER NOT NULL CHECK (monthly_budget_inclusion_snapshot IN (0,1)),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
--@@
CREATE TABLE goal_effect (
  id INTEGER PRIMARY KEY, goal_id INTEGER NOT NULL REFERENCES goal(id) ON DELETE RESTRICT,
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT, goal_movement_id INTEGER,
  reversal_of_id INTEGER REFERENCES goal_effect(id) ON DELETE RESTRICT, polarity INTEGER NOT NULL CHECK (polarity IN (-1,1)),
  kind INTEGER NOT NULL CHECK (kind >= 0), amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
--@@
CREATE TABLE statement_effect (
  id INTEGER PRIMARY KEY, credit_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  statement_id INTEGER REFERENCES credit_statement(id) ON DELETE RESTRICT,
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES statement_effect(id) ON DELETE RESTRICT, kind INTEGER NOT NULL CHECK (kind >= 0),
  polarity INTEGER NOT NULL CHECK (polarity IN (-1,1)), amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), manual_assignment INTEGER NOT NULL CHECK (manual_assignment IN (0,1)),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
--@@
CREATE TABLE loan_effect (
  id INTEGER PRIMARY KEY, loan_contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE RESTRICT,
  loan_tranche_id INTEGER REFERENCES loan_tranche(id) ON DELETE RESTRICT,
  schedule_item_id INTEGER REFERENCES loan_schedule_item(id) ON DELETE RESTRICT,
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES loan_effect(id) ON DELETE RESTRICT, kind INTEGER NOT NULL CHECK (kind >= 0),
  polarity INTEGER NOT NULL CHECK (polarity IN (-1,1)), amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
--@@
CREATE TABLE settlement_effect (
  id INTEGER PRIMARY KEY, activity_id INTEGER NOT NULL REFERENCES settlement_activity(id) ON DELETE RESTRICT,
  participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT,
  source_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  settlement_payment_record_id INTEGER REFERENCES settlement_payment_record(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES settlement_effect(id) ON DELETE RESTRICT, kind INTEGER NOT NULL CHECK (kind >= 0),
  signed_delta_minor INTEGER NOT NULL, currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  rule_set_version INTEGER NOT NULL REFERENCES rule_set_version(version) ON DELETE RESTRICT
)
