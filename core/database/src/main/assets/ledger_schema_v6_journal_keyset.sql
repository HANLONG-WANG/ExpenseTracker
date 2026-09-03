-- P37: satisfy the active/trash Journal keyset filter and ordering without a 500,000-row temp sort.
CREATE INDEX ix_current_transaction_state_keyset
ON current_transaction_projection(state, occurred_at DESC, transaction_id DESC)
