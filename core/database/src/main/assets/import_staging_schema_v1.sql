-- One-operation SQLCipher staging database schema v1. No table is part of the authoritative ledger.
CREATE TABLE staging_raw_row (
  row_number INTEGER PRIMARY KEY CHECK (row_number > 0), payload BLOB NOT NULL,
  source_hash BLOB NOT NULL CHECK (length(source_hash) = 32), ingestion_state INTEGER NOT NULL CHECK (ingestion_state >= 0)
)
--@@
CREATE TABLE staging_parsed_row (
  row_number INTEGER PRIMARY KEY REFERENCES staging_raw_row(row_number) ON DELETE CASCADE,
  field_set_blob BLOB NOT NULL, parser_version INTEGER NOT NULL CHECK (parser_version > 0), parsed_hash BLOB NOT NULL CHECK (length(parsed_hash) = 32)
)
--@@
CREATE TABLE staging_mapping (
  source_column TEXT NOT NULL CHECK (length(source_column) > 0), target_field INTEGER NOT NULL CHECK (target_field >= 0),
  transformation_type INTEGER NOT NULL CHECK (transformation_type >= 0), transformation_blob BLOB,
  PRIMARY KEY (source_column, target_field)
)
--@@
CREATE TABLE staging_validation_error (
  id INTEGER PRIMARY KEY, row_number INTEGER NOT NULL REFERENCES staging_raw_row(row_number) ON DELETE CASCADE,
  target_field INTEGER, error_code TEXT NOT NULL CHECK (length(error_code) > 0), error_detail_ciphertext BLOB,
  UNIQUE (row_number, target_field, error_code)
)
--@@
CREATE TABLE staging_duplicate_candidate (
  id INTEGER PRIMARY KEY, row_number INTEGER NOT NULL REFERENCES staging_raw_row(row_number) ON DELETE CASCADE,
  existing_transaction_uid BLOB NOT NULL CHECK (length(existing_transaction_uid) = 16), match_kind INTEGER NOT NULL CHECK (match_kind >= 0),
  confidence_basis_ciphertext BLOB NOT NULL, resolution INTEGER, UNIQUE (row_number, existing_transaction_uid, match_kind)
)
--@@
CREATE TABLE staging_prepared_command (
  row_number INTEGER PRIMARY KEY REFERENCES staging_raw_row(row_number) ON DELETE CASCADE,
  command_uid BLOB NOT NULL UNIQUE CHECK (length(command_uid) = 16), command_type INTEGER NOT NULL CHECK (command_type >= 0),
  command_blob BLOB NOT NULL, command_hash BLOB NOT NULL CHECK (length(command_hash) = 32), validation_state INTEGER NOT NULL CHECK (validation_state >= 0)
)
--@@
CREATE TABLE staging_attachment (
  id INTEGER PRIMARY KEY, row_number INTEGER NOT NULL REFERENCES staging_raw_row(row_number) ON DELETE CASCADE,
  source_handle_uid BLOB NOT NULL CHECK (length(source_handle_uid) = 16), imported_attachment_uid BLOB CHECK (imported_attachment_uid IS NULL OR length(imported_attachment_uid) = 16),
  content_hash BLOB CHECK (content_hash IS NULL OR length(content_hash) = 32), staging_storage_name TEXT,
  UNIQUE (row_number, source_handle_uid)
)
--@@
CREATE INDEX ix_staging_validation_error_row ON staging_validation_error(row_number, error_code)
--@@
CREATE INDEX ix_staging_duplicate_row ON staging_duplicate_candidate(row_number)
--@@
CREATE INDEX ix_staging_prepared_state ON staging_prepared_command(validation_state, row_number)
