-- Ledger primary database schema v7: bounded reference-page ordering and usage-count joins.
CREATE INDEX ix_merchant_name_keyset ON merchant(name, uid)
--@@
CREATE INDEX ix_place_name_keyset ON place(name, uid)
--@@
CREATE INDEX ix_place_merchant ON place(merchant_id)
--@@
CREATE INDEX ix_location_record_captured_keyset ON location_record(captured_at DESC, uid)
--@@
CREATE INDEX ix_current_transaction_merchant ON current_transaction_projection(merchant_id)
--@@
CREATE INDEX ix_transaction_revision_location ON transaction_revision(location_record_id, id)
--@@
CREATE INDEX ix_current_transaction_revision ON current_transaction_projection(current_revision_id)
