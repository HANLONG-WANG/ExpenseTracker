-- Ledger schema v1 indices and deterministic diagnostic views.
CREATE UNIQUE INDEX uq_journal_entry_reverses_entry ON journal_entry(reverses_entry_id) WHERE reverses_entry_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_posting_reversal ON posting(reversal_of_posting_id) WHERE reversal_of_posting_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_economic_effect_reversal ON economic_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_budget_effect_reversal ON budget_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_project_effect_reversal ON project_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_goal_effect_reversal ON goal_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_statement_effect_reversal ON statement_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_loan_effect_reversal ON loan_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_settlement_effect_reversal ON settlement_effect(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_refund_allocation_reversal ON refund_allocation(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_credit_payment_allocation_reversal ON credit_payment_allocation(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_loan_actual_allocation_reversal ON loan_actual_allocation(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_settlement_payment_reversal ON settlement_payment_record(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_goal_movement_reversal ON goal_movement(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_budget_adjustment_reversal ON budget_adjustment(reversal_of_id) WHERE reversal_of_id IS NOT NULL
--@@
CREATE UNIQUE INDEX uq_participant_single_self ON participant(is_self) WHERE is_self = 1
--@@
CREATE INDEX ix_business_transaction_state_current ON business_transaction(lifecycle_state, current_revision_id)
--@@
CREATE INDEX ix_transaction_revision_history ON transaction_revision(transaction_id, revision_no DESC)
--@@
CREATE INDEX ix_transaction_revision_timeline ON transaction_revision(local_date DESC, occurred_at DESC, id DESC)
--@@
CREATE INDEX ix_current_transaction_page ON current_transaction_projection(state, local_date DESC, occurred_at DESC, transaction_id DESC)
--@@
CREATE INDEX ix_current_transaction_keyset ON current_transaction_projection(occurred_at DESC, transaction_id DESC)
--@@
CREATE INDEX ix_posting_account_entry ON posting(ledger_account_id, journal_entry_id)
--@@
CREATE INDEX ix_journal_effective ON journal_entry(effective_at, id)
--@@
CREATE INDEX ix_account_balance_daily ON account_balance_daily(account_id, local_date)
--@@
CREATE INDEX ix_economic_effect_date_nature ON economic_effect(accrual_local_date, nature, is_consumption)
--@@
CREATE INDEX ix_economic_effect_category_date ON economic_effect(category_id, accrual_local_date)
--@@
CREATE INDEX ix_economic_effect_merchant_date ON economic_effect(merchant_id, accrual_local_date)
--@@
CREATE INDEX ix_economic_effect_project_date ON economic_effect(project_id, accrual_local_date)
--@@
CREATE INDEX ix_budget_effect_month_category ON budget_effect(target_year_month, category_id)
--@@
CREATE INDEX ix_credit_statement_cycle ON credit_statement(credit_account_id, cycle_start, cycle_end)
--@@
CREATE INDEX ix_statement_effect_statement_kind ON statement_effect(statement_id, kind)
--@@
CREATE INDEX ix_loan_schedule_item_number ON loan_schedule_item(schedule_revision_id, installment_no)
--@@
CREATE INDEX ix_loan_actual_allocation_target ON loan_actual_allocation(tranche_id, schedule_item_id)
--@@
CREATE INDEX ix_settlement_effect_position ON settlement_effect(activity_id, participant_id)
--@@
CREATE INDEX ix_recurrence_occurrence_time ON recurrence_occurrence(series_id, occurrence_instant)
--@@
CREATE INDEX ix_transaction_revision_attachment_order ON transaction_revision_attachment(revision_id, sort_order)
--@@
CREATE INDEX ix_location_record_place ON location_record(place_id, captured_at)
--@@
CREATE INDEX ix_background_operation_state ON background_operation(state, updated_at)
--@@
CREATE INDEX ix_backup_snapshot_repository_date ON backup_snapshot(repository_id, created_at DESC)
--@@
CREATE VIEW journal_balance_audit AS
SELECT id AS journal_entry_id, base_debit_total_minor, base_credit_total_minor,
       CASE WHEN base_debit_total_minor = base_credit_total_minor THEN 1 ELSE 0 END AS is_balanced
FROM journal_entry
--@@
CREATE VIEW current_transaction_subtype_audit AS
SELECT bt.id AS transaction_id, bt.kind, bt.current_revision_id,
  CASE bt.kind
    WHEN 0 THEN EXISTS(SELECT 1 FROM expense_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 1 THEN EXISTS(SELECT 1 FROM income_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 2 THEN EXISTS(SELECT 1 FROM transfer_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 3 THEN EXISTS(SELECT 1 FROM refund_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 4 THEN EXISTS(SELECT 1 FROM credit_payment_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 5 THEN EXISTS(SELECT 1 FROM loan_disbursement_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 6 THEN EXISTS(SELECT 1 FROM loan_payment_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 7 THEN EXISTS(SELECT 1 FROM balance_adjustment_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 8 THEN EXISTS(SELECT 1 FROM fx_exchange_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 9 THEN EXISTS(SELECT 1 FROM settlement_payment_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    WHEN 10 THEN EXISTS(SELECT 1 FROM opening_balance_revision_detail d WHERE d.revision_id = bt.current_revision_id)
    ELSE 0
  END AS has_matching_detail
FROM business_transaction bt
--@@
CREATE VIEW account_running_balance AS
SELECT account_id, local_date, opening_minor, inflow_minor, outflow_minor, closing_minor,
       SUM(inflow_minor - outflow_minor) OVER (
         PARTITION BY account_id ORDER BY local_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
       ) AS cumulative_net_minor,
       as_of_local_revision
FROM account_balance_daily
--@@
CREATE VIEW projection_revision_audit AS
SELECT 'current_transaction_projection' AS projection_name, MIN(as_of_local_revision) AS min_revision,
       MAX(as_of_local_revision) AS max_revision, (SELECT local_revision FROM book WHERE id = 1) AS book_revision
FROM current_transaction_projection
UNION ALL
SELECT 'account_balance_current', MIN(as_of_local_revision), MAX(as_of_local_revision),
       (SELECT local_revision FROM book WHERE id = 1) FROM account_balance_current
UNION ALL
SELECT 'budget_usage_projection', MIN(as_of_local_revision), MAX(as_of_local_revision),
       (SELECT local_revision FROM book WHERE id = 1) FROM budget_usage_projection
--@@
CREATE TRIGGER book_base_currency_frozen BEFORE UPDATE OF base_currency ON book
WHEN OLD.first_financial_commit_at IS NOT NULL AND NEW.base_currency <> OLD.base_currency
BEGIN SELECT RAISE(ABORT, 'base currency is frozen after the first financial commit'); END
--@@
CREATE TRIGGER user_account_currency_matches_ledger_insert BEFORE INSERT ON user_account
WHEN NEW.currency_code <> (SELECT currency_code FROM ledger_account WHERE id = NEW.ledger_account_id)
BEGIN SELECT RAISE(ABORT, 'user account currency must match ledger account currency'); END
--@@
CREATE TRIGGER user_account_currency_matches_ledger_update BEFORE UPDATE OF currency_code, ledger_account_id ON user_account
WHEN NEW.currency_code <> (SELECT currency_code FROM ledger_account WHERE id = NEW.ledger_account_id)
BEGIN SELECT RAISE(ABORT, 'user account currency must match ledger account currency'); END
--@@
CREATE TRIGGER user_account_used_currency_frozen BEFORE UPDATE OF currency_code ON user_account
WHEN NEW.currency_code <> OLD.currency_code AND EXISTS(
  SELECT 1 FROM posting p JOIN ledger_account la ON la.id = p.ledger_account_id WHERE la.id = OLD.ledger_account_id
)
BEGIN SELECT RAISE(ABORT, 'account currency is frozen after its first posting'); END
--@@
CREATE TRIGGER posting_account_currency_insert BEFORE INSERT ON posting
WHEN NEW.account_currency <> (SELECT currency_code FROM ledger_account WHERE id = NEW.ledger_account_id)
BEGIN SELECT RAISE(ABORT, 'posting currency must match ledger account currency'); END
--@@
CREATE TRIGGER category_hierarchy_insert BEFORE INSERT ON category
WHEN NEW.depth = 2 AND NOT EXISTS(
  SELECT 1 FROM category parent WHERE parent.id = NEW.parent_id AND parent.depth = 1 AND parent.direction = NEW.direction
)
BEGIN SELECT RAISE(ABORT, 'child category requires a same-direction first-level parent'); END
--@@
CREATE TRIGGER category_hierarchy_update BEFORE UPDATE OF parent_id, depth, direction ON category
WHEN (OLD.depth = 2 AND NEW.parent_id IS NOT OLD.parent_id) OR
     (NEW.depth = 2 AND NOT EXISTS(
       SELECT 1 FROM category parent WHERE parent.id = NEW.parent_id AND parent.depth = 1 AND parent.direction = NEW.direction
     ))
BEGIN SELECT RAISE(ABORT, 'category hierarchy mutation rejected'); END
--@@
CREATE TRIGGER current_transaction_revision_insert BEFORE INSERT ON business_transaction
WHEN NEW.current_revision_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM transaction_revision revision WHERE revision.id = NEW.current_revision_id AND revision.transaction_id = NEW.id
)
BEGIN SELECT RAISE(ABORT, 'current revision must belong to the transaction'); END
--@@
CREATE TRIGGER current_transaction_revision_update BEFORE UPDATE OF current_revision_id ON business_transaction
WHEN NEW.current_revision_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM transaction_revision revision WHERE revision.id = NEW.current_revision_id AND revision.transaction_id = NEW.id
)
BEGIN SELECT RAISE(ABORT, 'current revision must belong to the transaction'); END
--@@
CREATE TRIGGER settlement_self_payment_link_insert BEFORE INSERT ON settlement_payment_record
WHEN (
  EXISTS(SELECT 1 FROM participant WHERE id = NEW.payer_participant_id AND is_self = 1) OR
  EXISTS(SELECT 1 FROM participant WHERE id = NEW.payee_participant_id AND is_self = 1)
) AND NEW.linked_transaction_id IS NULL
BEGIN SELECT RAISE(ABORT, 'a self-involved settlement payment requires a linked transaction'); END
