ALTER TABLE posting ADD COLUMN valuation_source INTEGER NOT NULL DEFAULT 0 CHECK (valuation_source BETWEEN 0 AND 2)
--@@
UPDATE posting SET valuation_source=CASE
  WHEN account_currency=base_currency THEN 0
  WHEN valuation_rate_decimal IS NOT NULL THEN 1
  ELSE 2
END
--@@
ALTER TABLE payment_card ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE category ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE merchant ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE place ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE project ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE goal ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE participant ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE participant ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE settlement_activity ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE settlement_activity ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE transaction_blueprint ADD COLUMN last_commit_id INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE transaction_blueprint ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE transaction_blueprint ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE recurrence_series ADD COLUMN last_commit_id INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE recurrence_series ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE recurrence_series ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE budget_template ADD COLUMN last_commit_id INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE budget_template ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE budget_template ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE budget_month ADD COLUMN last_commit_id INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE budget_month ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE budget_month ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE credit_statement ADD COLUMN last_commit_id INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE credit_statement ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE credit_statement ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE installment_plan ADD COLUMN last_commit_id INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE installment_plan ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE installment_plan ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE loan_contract ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE loan_contract ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
ALTER TABLE credit_account_profile ADD COLUMN row_version INTEGER NOT NULL DEFAULT 1 CHECK (row_version > 0)
--@@
ALTER TABLE credit_account_profile ADD COLUMN content_hash BLOB NOT NULL DEFAULT X'0000000000000000000000000000000000000000000000000000000000000000' CHECK (length(content_hash)=32)
--@@
UPDATE payment_card SET content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=2 AND entity_uid=payment_card.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE category SET content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=3 AND entity_uid=category.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE merchant SET content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=4 AND entity_uid=merchant.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE place SET content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=5 AND entity_uid=place.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE project SET content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=7 AND entity_uid=project.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE goal SET content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=8 AND entity_uid=goal.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE participant SET row_version=COALESCE((SELECT MAX(revision_no) FROM entity_revision WHERE entity_type=13 AND entity_uid=participant.uid),row_version),content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=13 AND entity_uid=participant.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE settlement_activity SET row_version=COALESCE((SELECT MAX(revision_no) FROM entity_revision WHERE entity_type=14 AND entity_uid=settlement_activity.uid),row_version),content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=14 AND entity_uid=settlement_activity.uid ORDER BY revision_no DESC LIMIT 1),content_hash)
--@@
UPDATE transaction_blueprint SET
  last_commit_id=COALESCE((SELECT commit_id FROM entity_revision WHERE entity_type=15 AND entity_uid=transaction_blueprint.uid ORDER BY revision_no DESC LIMIT 1),(SELECT created_commit_id FROM transaction_blueprint_revision WHERE id=transaction_blueprint.current_revision_id),last_commit_id),
  row_version=COALESCE((SELECT MAX(revision_no) FROM entity_revision WHERE entity_type=15 AND entity_uid=transaction_blueprint.uid),(SELECT revision_no FROM transaction_blueprint_revision WHERE id=transaction_blueprint.current_revision_id),row_version),
  content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=15 AND entity_uid=transaction_blueprint.uid ORDER BY revision_no DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM transaction_blueprint_revision r JOIN book_commit bc ON bc.id=r.created_commit_id LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE r.id=transaction_blueprint.current_revision_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE recurrence_series SET
  last_commit_id=COALESCE((SELECT commit_id FROM entity_revision WHERE entity_type=16 AND entity_uid=recurrence_series.uid ORDER BY revision_no DESC LIMIT 1),(SELECT created_commit_id FROM recurrence_series_revision WHERE id=recurrence_series.current_revision_id),last_commit_id),
  row_version=COALESCE((SELECT MAX(revision_no) FROM entity_revision WHERE entity_type=16 AND entity_uid=recurrence_series.uid),(SELECT revision_no FROM recurrence_series_revision WHERE id=recurrence_series.current_revision_id),row_version),
  content_hash=COALESCE((SELECT content_hash FROM entity_revision WHERE entity_type=16 AND entity_uid=recurrence_series.uid ORDER BY revision_no DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM recurrence_series_revision r JOIN book_commit bc ON bc.id=r.created_commit_id LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE r.id=recurrence_series.current_revision_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE budget_month SET
  last_commit_id=COALESCE((SELECT ec.commit_id FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=9 AND ec.entity_uid=budget_month.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT created_commit_id FROM budget_month_revision WHERE id=budget_month.current_revision_id),last_commit_id),
  row_version=COALESCE(NULLIF((SELECT COUNT(*) FROM entity_change ec WHERE ec.entity_type=9 AND ec.entity_uid=budget_month.uid AND ec.after_hash IS NOT NULL),0),(SELECT revision_no FROM budget_month_revision WHERE id=budget_month.current_revision_id),1),
  content_hash=COALESCE((SELECT ec.after_hash FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=9 AND ec.entity_uid=budget_month.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM budget_month_revision r JOIN book_commit bc ON bc.id=r.created_commit_id LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE r.id=budget_month.current_revision_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE credit_statement SET
  last_commit_id=COALESCE((SELECT ec.commit_id FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=10 AND ec.entity_uid=credit_statement.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT created_commit_id FROM credit_statement_revision WHERE id=credit_statement.current_revision_id),last_commit_id),
  row_version=COALESCE(NULLIF((SELECT COUNT(*) FROM entity_change ec WHERE ec.entity_type=10 AND ec.entity_uid=credit_statement.uid AND ec.after_hash IS NOT NULL),0),(SELECT revision_no FROM credit_statement_revision WHERE id=credit_statement.current_revision_id),1),
  content_hash=COALESCE((SELECT ec.after_hash FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=10 AND ec.entity_uid=credit_statement.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM credit_statement_revision r JOIN book_commit bc ON bc.id=r.created_commit_id LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE r.id=credit_statement.current_revision_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE installment_plan SET
  last_commit_id=COALESCE((SELECT ec.commit_id FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=11 AND ec.entity_uid=installment_plan.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT created_commit_id FROM installment_plan_revision WHERE id=installment_plan.current_revision_id),last_commit_id),
  row_version=COALESCE(NULLIF((SELECT COUNT(*) FROM entity_change ec WHERE ec.entity_type=11 AND ec.entity_uid=installment_plan.uid AND ec.after_hash IS NOT NULL),0),(SELECT revision_no FROM installment_plan_revision WHERE id=installment_plan.current_revision_id),1),
  content_hash=COALESCE((SELECT ec.after_hash FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=11 AND ec.entity_uid=installment_plan.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM installment_plan_revision r JOIN book_commit bc ON bc.id=r.created_commit_id LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE r.id=installment_plan.current_revision_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE loan_contract SET
  row_version=COALESCE(NULLIF((SELECT COUNT(*) FROM entity_change ec WHERE ec.entity_type=12 AND ec.entity_uid=loan_contract.uid AND ec.after_hash IS NOT NULL),0),1),
  content_hash=COALESCE((SELECT ec.after_hash FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=12 AND ec.entity_uid=loan_contract.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM book_commit bc LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE bc.id=loan_contract.last_commit_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE credit_account_profile SET
  row_version=COALESCE(NULLIF((SELECT COUNT(*) FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id
    WHERE ec.entity_type=1 AND ec.entity_uid=(SELECT uid FROM user_account WHERE id=credit_account_profile.account_id)
      AND ec.after_hash IS NOT NULL AND bc.local_revision<=(SELECT local_revision FROM book_commit WHERE id=credit_account_profile.last_commit_id)),0),1),
  content_hash=COALESCE((SELECT ec.after_hash FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id
    WHERE ec.entity_type=1 AND ec.entity_uid=(SELECT uid FROM user_account WHERE id=credit_account_profile.account_id)
      AND ec.after_hash IS NOT NULL AND bc.local_revision<=(SELECT local_revision FROM book_commit WHERE id=credit_account_profile.last_commit_id)
    ORDER BY bc.local_revision DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM book_commit bc LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE bc.id=credit_account_profile.last_commit_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
UPDATE budget_template SET
  last_commit_id=COALESCE((SELECT ec.commit_id FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=20 AND ec.entity_uid=budget_template.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT created_commit_id FROM budget_template_revision WHERE id=budget_template.current_revision_id),last_commit_id),
  row_version=COALESCE(NULLIF((SELECT COUNT(*) FROM entity_change ec WHERE ec.entity_type=20 AND ec.entity_uid=budget_template.uid AND ec.after_hash IS NOT NULL),0),(SELECT revision_no FROM budget_template_revision WHERE id=budget_template.current_revision_id),1),
  content_hash=COALESCE((SELECT ec.after_hash FROM entity_change ec JOIN book_commit bc ON bc.id=ec.commit_id WHERE ec.entity_type=20 AND ec.entity_uid=budget_template.uid AND ec.after_hash IS NOT NULL ORDER BY bc.local_revision DESC LIMIT 1),(SELECT COALESCE(cr.payload_hash,bc.root_hash) FROM budget_template_revision r JOIN book_commit bc ON bc.id=r.created_commit_id LEFT JOIN command_receipt cr ON cr.commit_id=bc.id WHERE r.id=budget_template.current_revision_id ORDER BY cr.executed_at DESC LIMIT 1),content_hash)
--@@
CREATE TABLE projection_contract_state (
  id INTEGER PRIMARY KEY CHECK (id=1),
  contract_version INTEGER NOT NULL CHECK (contract_version>0),
  rebuilt_at_local_revision INTEGER NOT NULL CHECK (rebuilt_at_local_revision>=0)
)
--@@
INSERT INTO projection_contract_state(id,contract_version,rebuilt_at_local_revision)
VALUES(1,2,COALESCE((SELECT local_revision FROM book WHERE id=1),0))
--@@
CREATE VIEW budget_effect_line AS
SELECT 0 source_type,id source_id,source_revision_id,NULL budget_adjustment_id,polarity,kind,target_year_month,
  category_id,root_category_id,base_amount_minor,rule_set_version
FROM budget_effect
UNION ALL
SELECT 1 source_type,id source_id,NULL source_revision_id,id budget_adjustment_id,
  CASE WHEN amount_base_minor<0 THEN -1 ELSE 1 END polarity,2 kind,year_month target_year_month,
  category_id,category_id root_category_id,
  CASE WHEN amount_base_minor<0 THEN -amount_base_minor ELSE amount_base_minor END base_amount_minor,
  (SELECT rule_set_version FROM book WHERE id=1) rule_set_version
FROM budget_adjustment
