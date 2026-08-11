ALTER TABLE widget_book_snapshot ADD COLUMN snapshot_local_date INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN month_key INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN month_consumption_base_minor INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN previous_month_consumption_base_minor INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN month_budget_available_base_minor INTEGER
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN month_budget_used_base_minor INTEGER
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN today_available_base_minor INTEGER
--@@
ALTER TABLE widget_book_snapshot ADD COLUMN previous_core_net_financial_assets_base_minor INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE widget_account_snapshot ADD COLUMN account_uid BLOB
--@@
ALTER TABLE widget_account_snapshot ADD COLUMN display_name TEXT NOT NULL DEFAULT ''
--@@
ALTER TABLE widget_account_snapshot ADD COLUMN available_minor INTEGER NOT NULL DEFAULT 0
--@@
ALTER TABLE widget_credit_snapshot ADD COLUMN account_uid BLOB
--@@
ALTER TABLE widget_credit_snapshot ADD COLUMN display_name TEXT NOT NULL DEFAULT ''
--@@
ALTER TABLE widget_credit_snapshot ADD COLUMN statement_remaining_minor INTEGER
--@@
ALTER TABLE widget_credit_snapshot ADD COLUMN statement_due_date INTEGER
--@@
ALTER TABLE widget_goal_snapshot ADD COLUMN goal_uid BLOB
--@@
ALTER TABLE widget_goal_snapshot ADD COLUMN display_name TEXT NOT NULL DEFAULT ''
