-- Ledger primary database schema v1: subledgers, plans, recurrence and attachment operation state.
CREATE TABLE refund_allocation (
  id INTEGER PRIMARY KEY, refund_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  refund_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  original_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  original_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  original_currency_amount_minor INTEGER NOT NULL CHECK (original_currency_amount_minor >= 0),
  base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES refund_allocation(id) ON DELETE RESTRICT,
  CHECK (refund_transaction_id <> original_transaction_id)
)
--@@
CREATE TABLE refund_status_projection (
  original_transaction_id INTEGER PRIMARY KEY REFERENCES business_transaction(id) ON DELETE CASCADE,
  gross_refundable_minor INTEGER NOT NULL CHECK (gross_refundable_minor >= 0),
  refunded_minor INTEGER NOT NULL CHECK (refunded_minor >= 0), remaining_minor INTEGER NOT NULL CHECK (remaining_minor >= 0),
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  CHECK (gross_refundable_minor = refunded_minor + remaining_minor)
)
--@@
CREATE TABLE credit_account_profile (
  account_id INTEGER PRIMARY KEY REFERENCES user_account(id) ON DELETE RESTRICT,
  statement_rule_type INTEGER NOT NULL CHECK (statement_rule_type >= 0), statement_day INTEGER CHECK (statement_day BETWEEN 1 AND 31),
  due_rule_type INTEGER NOT NULL CHECK (due_rule_type >= 0), due_day INTEGER CHECK (due_day BETWEEN 1 AND 31),
  days_after_statement INTEGER CHECK (days_after_statement IS NULL OR days_after_statement > 0), zone_id TEXT NOT NULL,
  standard_limit_minor INTEGER CHECK (standard_limit_minor IS NULL OR standard_limit_minor >= 0),
  temporary_limit_minor INTEGER CHECK (temporary_limit_minor IS NULL OR temporary_limit_minor >= 0), temporary_limit_expires_on INTEGER,
  default_payment_account_id INTEGER REFERENCES user_account(id) ON DELETE SET NULL,
  auto_payment_mode INTEGER NOT NULL CHECK (auto_payment_mode >= 0), weekend_adjustment INTEGER NOT NULL CHECK (weekend_adjustment >= 0),
  last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  CHECK ((temporary_limit_minor IS NULL) = (temporary_limit_expires_on IS NULL))
)
--@@
CREATE TABLE credit_limit_period (
  id INTEGER PRIMARY KEY, credit_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  effective_from INTEGER NOT NULL, effective_to INTEGER, limit_minor INTEGER NOT NULL CHECK (limit_minor >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  UNIQUE (credit_account_id, effective_from), CHECK (effective_to IS NULL OR effective_to >= effective_from)
)
--@@
CREATE TABLE credit_statement (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  credit_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT,
  cycle_start INTEGER NOT NULL, cycle_end INTEGER NOT NULL, due_date INTEGER NOT NULL,
  current_revision_id INTEGER REFERENCES credit_statement_revision(id) ON DELETE RESTRICT, status INTEGER NOT NULL CHECK (status >= 0),
  UNIQUE (credit_account_id, cycle_start, cycle_end), CHECK (cycle_end >= cycle_start), CHECK (due_date >= cycle_end)
)
--@@
CREATE TABLE credit_statement_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  statement_id INTEGER NOT NULL REFERENCES credit_statement(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  estimated_amount_minor INTEGER NOT NULL CHECK (estimated_amount_minor >= 0),
  official_amount_minor INTEGER CHECK (official_amount_minor IS NULL OR official_amount_minor >= 0), official_recorded_at INTEGER,
  difference_minor INTEGER, statement_date INTEGER NOT NULL, due_date INTEGER NOT NULL,
  sealed INTEGER NOT NULL CHECK (sealed IN (0,1)), created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  UNIQUE (statement_id, revision_no), CHECK ((official_amount_minor IS NULL) = (official_recorded_at IS NULL))
)
--@@
CREATE TABLE credit_payment_allocation (
  id INTEGER PRIMARY KEY, payment_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  payment_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  statement_id INTEGER REFERENCES credit_statement(id) ON DELETE RESTRICT, amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  reversal_of_id INTEGER REFERENCES credit_payment_allocation(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE installment_plan (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  purchase_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  credit_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT, currency_code TEXT NOT NULL CHECK (length(currency_code) = 3),
  original_principal_minor INTEGER NOT NULL CHECK (original_principal_minor > 0), term_count INTEGER NOT NULL CHECK (term_count > 0),
  current_revision_id INTEGER REFERENCES installment_plan_revision(id) ON DELETE RESTRICT, status INTEGER NOT NULL CHECK (status >= 0)
)
--@@
CREATE TABLE installment_plan_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  plan_id INTEGER NOT NULL REFERENCES installment_plan(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  fee_rate_type INTEGER NOT NULL CHECK (fee_rate_type >= 0), fixed_fee_per_term_minor INTEGER CHECK (fixed_fee_per_term_minor IS NULL OR fixed_fee_per_term_minor >= 0),
  first_term_fee_minor INTEGER CHECK (first_term_fee_minor IS NULL OR first_term_fee_minor >= 0), remaining_principal_rate_decimal TEXT,
  effective_annual_rate_decimal TEXT, prepayment_policy INTEGER NOT NULL CHECK (prepayment_policy >= 0),
  prepayment_fee_minor INTEGER CHECK (prepayment_fee_minor IS NULL OR prepayment_fee_minor >= 0),
  refund_policy INTEGER NOT NULL CHECK (refund_policy >= 0), rounding_mode INTEGER NOT NULL CHECK (rounding_mode >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, UNIQUE (plan_id, revision_no)
)
--@@
CREATE TABLE installment_schedule_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  plan_id INTEGER NOT NULL REFERENCES installment_plan(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  reason INTEGER NOT NULL CHECK (reason >= 0), generated_at INTEGER NOT NULL,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, UNIQUE (plan_id, revision_no)
)
--@@
CREATE TABLE installment_schedule_item (
  id INTEGER PRIMARY KEY, schedule_revision_id INTEGER NOT NULL REFERENCES installment_schedule_revision(id) ON DELETE RESTRICT,
  installment_no INTEGER NOT NULL CHECK (installment_no > 0), statement_date INTEGER NOT NULL,
  principal_minor INTEGER NOT NULL CHECK (principal_minor >= 0), interest_minor INTEGER NOT NULL CHECK (interest_minor >= 0),
  fee_minor INTEGER NOT NULL CHECK (fee_minor >= 0), remaining_principal_minor INTEGER NOT NULL CHECK (remaining_principal_minor >= 0),
  UNIQUE (schedule_revision_id, installment_no)
)
--@@
CREATE TABLE installment_refund_allocation (
  id INTEGER PRIMARY KEY, refund_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  refund_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  plan_id INTEGER NOT NULL REFERENCES installment_plan(id) ON DELETE RESTRICT,
  principal_minor INTEGER NOT NULL CHECK (principal_minor >= 0), fee_minor INTEGER NOT NULL CHECK (fee_minor >= 0),
  reversal_of_id INTEGER REFERENCES installment_refund_allocation(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE loan_contract (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  display_account_id INTEGER NOT NULL REFERENCES user_account(id) ON DELETE RESTRICT, name TEXT NOT NULL CHECK (length(name) > 0), lender TEXT,
  currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), disbursement_date INTEGER NOT NULL,
  status INTEGER NOT NULL CHECK (status >= 0), last_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE loan_tranche (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE RESTRICT,
  ledger_account_id INTEGER NOT NULL UNIQUE REFERENCES ledger_account(id) ON DELETE RESTRICT, name TEXT NOT NULL CHECK (length(name) > 0),
  original_principal_minor INTEGER NOT NULL CHECK (original_principal_minor > 0), status INTEGER NOT NULL CHECK (status >= 0)
)
--@@
CREATE TABLE loan_terms_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  tranche_id INTEGER NOT NULL REFERENCES loan_tranche(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  repayment_method INTEGER NOT NULL CHECK (repayment_method >= 0), rate_type INTEGER NOT NULL CHECK (rate_type >= 0),
  payment_frequency INTEGER NOT NULL CHECK (payment_frequency >= 0), start_date INTEGER NOT NULL, end_date INTEGER,
  rounding_mode INTEGER NOT NULL CHECK (rounding_mode >= 0), prepayment_policy INTEGER NOT NULL CHECK (prepayment_policy >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  UNIQUE (tranche_id, revision_no), CHECK (end_date IS NULL OR end_date >= start_date)
)
--@@
CREATE TABLE loan_rate_period (
  id INTEGER PRIMARY KEY, terms_revision_id INTEGER NOT NULL REFERENCES loan_terms_revision(id) ON DELETE RESTRICT,
  effective_from INTEGER NOT NULL, effective_to INTEGER, annual_rate_decimal TEXT NOT NULL,
  benchmark TEXT, margin_decimal TEXT, UNIQUE (terms_revision_id, effective_from), CHECK (effective_to IS NULL OR effective_to >= effective_from)
)
--@@
CREATE TABLE loan_schedule_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  tranche_id INTEGER NOT NULL REFERENCES loan_tranche(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  terms_revision_id INTEGER NOT NULL REFERENCES loan_terms_revision(id) ON DELETE RESTRICT,
  reason INTEGER NOT NULL CHECK (reason >= 0), generated_at INTEGER NOT NULL,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, UNIQUE (tranche_id, revision_no)
)
--@@
CREATE TABLE loan_schedule_item (
  id INTEGER PRIMARY KEY, schedule_revision_id INTEGER NOT NULL REFERENCES loan_schedule_revision(id) ON DELETE RESTRICT,
  installment_no INTEGER NOT NULL CHECK (installment_no > 0), planned_date INTEGER NOT NULL,
  principal_minor INTEGER NOT NULL CHECK (principal_minor >= 0), interest_minor INTEGER NOT NULL CHECK (interest_minor >= 0),
  fee_minor INTEGER NOT NULL CHECK (fee_minor >= 0), remaining_principal_minor INTEGER NOT NULL CHECK (remaining_principal_minor >= 0),
  UNIQUE (schedule_revision_id, installment_no)
)
--@@
CREATE TABLE loan_actual_allocation (
  id INTEGER PRIMARY KEY, payment_transaction_id INTEGER NOT NULL REFERENCES business_transaction(id) ON DELETE RESTRICT,
  payment_revision_id INTEGER NOT NULL REFERENCES transaction_revision(id) ON DELETE RESTRICT,
  tranche_id INTEGER NOT NULL REFERENCES loan_tranche(id) ON DELETE RESTRICT,
  schedule_item_id INTEGER REFERENCES loan_schedule_item(id) ON DELETE RESTRICT, component INTEGER NOT NULL CHECK (component >= 0),
  amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0), base_amount_minor INTEGER NOT NULL CHECK (base_amount_minor >= 0),
  reversal_of_id INTEGER REFERENCES loan_actual_allocation(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE loan_simulation (
  id INTEGER PRIMARY KEY, contract_id INTEGER NOT NULL REFERENCES loan_contract(id) ON DELETE CASCADE,
  base_schedule_revision_id INTEGER NOT NULL REFERENCES loan_schedule_revision(id) ON DELETE RESTRICT,
  scenario_type INTEGER NOT NULL CHECK (scenario_type >= 0), parameters_blob BLOB NOT NULL, created_at INTEGER NOT NULL
)
--@@
CREATE TABLE loan_simulation_item (
  simulation_id INTEGER NOT NULL REFERENCES loan_simulation(id) ON DELETE CASCADE, installment_no INTEGER NOT NULL CHECK (installment_no > 0),
  planned_date INTEGER NOT NULL, principal_minor INTEGER NOT NULL CHECK (principal_minor >= 0),
  interest_minor INTEGER NOT NULL CHECK (interest_minor >= 0), fee_minor INTEGER NOT NULL CHECK (fee_minor >= 0),
  remaining_principal_minor INTEGER NOT NULL CHECK (remaining_principal_minor >= 0), PRIMARY KEY (simulation_id, installment_no)
)
--@@
CREATE TABLE settlement_payment_record (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  activity_id INTEGER NOT NULL REFERENCES settlement_activity(id) ON DELETE RESTRICT,
  payer_participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT,
  payee_participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT,
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0), currency_code TEXT NOT NULL CHECK (length(currency_code) = 3), occurred_at INTEGER NOT NULL,
  linked_transaction_id INTEGER REFERENCES business_transaction(id) ON DELETE RESTRICT,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES settlement_payment_record(id) ON DELETE RESTRICT, CHECK (payer_participant_id <> payee_participant_id)
)
--@@
CREATE TABLE goal_movement (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), goal_id INTEGER NOT NULL REFERENCES goal(id) ON DELETE RESTRICT,
  kind INTEGER NOT NULL CHECK (kind >= 0), amount_minor INTEGER NOT NULL CHECK (amount_minor > 0), occurred_at INTEGER NOT NULL,
  source_transaction_id INTEGER REFERENCES business_transaction(id) ON DELETE RESTRICT,
  source_recurrence_occurrence_id INTEGER REFERENCES recurrence_occurrence(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES goal_movement(id) ON DELETE RESTRICT,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE budget_template (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0),
  current_revision_id INTEGER REFERENCES budget_template_revision(id) ON DELETE RESTRICT, status INTEGER NOT NULL CHECK (status >= 0)
)
--@@
CREATE TABLE budget_template_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  template_id INTEGER NOT NULL REFERENCES budget_template(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  total_base_minor INTEGER NOT NULL CHECK (total_base_minor >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, UNIQUE (template_id, revision_no)
)
--@@
CREATE TABLE budget_template_category_limit (
  template_revision_id INTEGER NOT NULL REFERENCES budget_template_revision(id) ON DELETE RESTRICT,
  category_id INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT, amount_base_minor INTEGER NOT NULL CHECK (amount_base_minor >= 0),
  PRIMARY KEY (template_revision_id, category_id)
)
--@@
CREATE TABLE budget_month (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), year_month INTEGER NOT NULL UNIQUE,
  current_revision_id INTEGER REFERENCES budget_month_revision(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE budget_month_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  budget_month_id INTEGER NOT NULL REFERENCES budget_month(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  base_total_minor INTEGER NOT NULL CHECK (base_total_minor >= 0),
  source_template_revision_id INTEGER REFERENCES budget_template_revision(id) ON DELETE RESTRICT,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, UNIQUE (budget_month_id, revision_no)
)
--@@
CREATE TABLE budget_category_limit (
  budget_month_revision_id INTEGER NOT NULL REFERENCES budget_month_revision(id) ON DELETE RESTRICT,
  category_id INTEGER NOT NULL REFERENCES category(id) ON DELETE RESTRICT, amount_base_minor INTEGER NOT NULL CHECK (amount_base_minor >= 0),
  PRIMARY KEY (budget_month_revision_id, category_id)
)
--@@
CREATE TABLE budget_adjustment (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), year_month INTEGER NOT NULL,
  scope INTEGER NOT NULL CHECK (scope >= 0), category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT,
  amount_base_minor INTEGER NOT NULL, kind INTEGER NOT NULL CHECK (kind >= 0),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  reversal_of_id INTEGER REFERENCES budget_adjustment(id) ON DELETE RESTRICT
)
--@@
CREATE TABLE budget_rollover (
  from_year_month INTEGER NOT NULL, to_year_month INTEGER NOT NULL, scope INTEGER NOT NULL CHECK (scope >= 0),
  category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT, amount_base_minor INTEGER NOT NULL,
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  PRIMARY KEY (from_year_month, to_year_month, scope, category_id), CHECK (to_year_month > from_year_month)
)
--@@
CREATE TABLE transaction_blueprint (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), name TEXT NOT NULL CHECK (length(name) > 0),
  current_revision_id INTEGER REFERENCES transaction_blueprint_revision(id) ON DELETE RESTRICT,
  status INTEGER NOT NULL CHECK (status >= 0), icon_key TEXT NOT NULL, color_argb INTEGER NOT NULL
)
--@@
CREATE TABLE transaction_blueprint_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  blueprint_id INTEGER NOT NULL REFERENCES transaction_blueprint(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  target_kind INTEGER NOT NULL CHECK (target_kind BETWEEN 0 AND 10), category_id INTEGER REFERENCES category(id) ON DELETE RESTRICT,
  primary_account_id INTEGER REFERENCES user_account(id) ON DELETE RESTRICT, secondary_account_id INTEGER REFERENCES user_account(id) ON DELETE RESTRICT,
  card_id INTEGER REFERENCES payment_card(id) ON DELETE RESTRICT, merchant_id INTEGER REFERENCES merchant(id) ON DELETE RESTRICT,
  project_id INTEGER REFERENCES project(id) ON DELETE RESTRICT, goal_id INTEGER REFERENCES goal(id) ON DELETE RESTRICT,
  settlement_activity_id INTEGER REFERENCES settlement_activity(id) ON DELETE RESTRICT, amount_expression TEXT,
  currency_code TEXT CHECK (currency_code IS NULL OR length(currency_code) = 3), note_template TEXT,
  fixed_place_id INTEGER REFERENCES place(id) ON DELETE RESTRICT,
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT, UNIQUE (blueprint_id, revision_no)
)
--@@
CREATE TABLE blueprint_settlement_share_rule (
  blueprint_revision_id INTEGER NOT NULL REFERENCES transaction_blueprint_revision(id) ON DELETE RESTRICT,
  participant_id INTEGER NOT NULL REFERENCES participant(id) ON DELETE RESTRICT, rule_type INTEGER NOT NULL CHECK (rule_type >= 0),
  fixed_amount_minor INTEGER, percentage_decimal TEXT, weight_decimal TEXT, excluded INTEGER NOT NULL CHECK (excluded IN (0,1)),
  PRIMARY KEY (blueprint_revision_id, participant_id)
)
--@@
CREATE TABLE recurrence_series (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  blueprint_id INTEGER NOT NULL REFERENCES transaction_blueprint(id) ON DELETE RESTRICT,
  current_revision_id INTEGER REFERENCES recurrence_series_revision(id) ON DELETE RESTRICT, status INTEGER NOT NULL CHECK (status >= 0)
)
--@@
CREATE TABLE recurrence_series_revision (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  series_id INTEGER NOT NULL REFERENCES recurrence_series(id) ON DELETE RESTRICT, revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  frequency INTEGER NOT NULL CHECK (frequency >= 0), interval_value INTEGER NOT NULL CHECK (interval_value > 0),
  start_date INTEGER NOT NULL, end_date INTEGER, max_occurrences INTEGER CHECK (max_occurrences IS NULL OR max_occurrences > 0),
  zone_id TEXT NOT NULL, month_day INTEGER CHECK (month_day BETWEEN 1 AND 31), nth_week INTEGER CHECK (nth_week BETWEEN 1 AND 5),
  weekday INTEGER CHECK (weekday BETWEEN 1 AND 7), missing_day_policy INTEGER NOT NULL CHECK (missing_day_policy >= 0),
  weekend_policy INTEGER NOT NULL CHECK (weekend_policy >= 0), generation_mode INTEGER NOT NULL CHECK (generation_mode >= 0),
  notify_candidate INTEGER NOT NULL CHECK (notify_candidate IN (0,1)),
  created_commit_id INTEGER NOT NULL REFERENCES book_commit(id) ON DELETE RESTRICT,
  UNIQUE (series_id, revision_no), CHECK (end_date IS NULL OR end_date >= start_date)
)
--@@
CREATE TABLE recurrence_rule_weekday (
  series_revision_id INTEGER NOT NULL REFERENCES recurrence_series_revision(id) ON DELETE RESTRICT,
  weekday INTEGER NOT NULL CHECK (weekday BETWEEN 1 AND 7), PRIMARY KEY (series_revision_id, weekday)
)
--@@
CREATE TABLE recurrence_exception (
  series_id INTEGER NOT NULL REFERENCES recurrence_series(id) ON DELETE RESTRICT, occurrence_local_date INTEGER NOT NULL,
  action INTEGER NOT NULL CHECK (action >= 0), override_blueprint_revision_id INTEGER REFERENCES transaction_blueprint_revision(id) ON DELETE RESTRICT,
  override_instant INTEGER, PRIMARY KEY (series_id, occurrence_local_date)
)
--@@
CREATE TABLE recurrence_occurrence (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16), series_id INTEGER NOT NULL REFERENCES recurrence_series(id) ON DELETE RESTRICT,
  series_revision_id INTEGER NOT NULL REFERENCES recurrence_series_revision(id) ON DELETE RESTRICT,
  occurrence_instant INTEGER NOT NULL, local_date INTEGER NOT NULL, status INTEGER NOT NULL CHECK (status >= 0),
  candidate_id INTEGER REFERENCES recurrence_candidate(id) ON DELETE RESTRICT,
  transaction_id INTEGER REFERENCES business_transaction(id) ON DELETE RESTRICT, error_code TEXT,
  UNIQUE (series_id, series_revision_id, occurrence_instant)
)
--@@
CREATE TABLE recurrence_candidate (
  id INTEGER PRIMARY KEY, uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  occurrence_id INTEGER NOT NULL UNIQUE REFERENCES recurrence_occurrence(id) ON DELETE RESTRICT,
  blueprint_revision_id INTEGER NOT NULL REFERENCES transaction_blueprint_revision(id) ON DELETE RESTRICT,
  created_at INTEGER NOT NULL, status INTEGER NOT NULL CHECK (status >= 0), validation_error_code TEXT
)
--@@
CREATE TABLE blob_gc_candidate (
  blob_id INTEGER PRIMARY KEY REFERENCES encrypted_blob(id) ON DELETE CASCADE, eligible_after INTEGER NOT NULL,
  reason INTEGER NOT NULL CHECK (reason >= 0), last_checked_at INTEGER
)
