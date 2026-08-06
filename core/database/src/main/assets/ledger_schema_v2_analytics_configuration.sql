CREATE TABLE analytics_report_definition (
  id INTEGER PRIMARY KEY,
  uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  current_revision_id INTEGER REFERENCES analytics_report_revision(id) ON DELETE RESTRICT,
  archived INTEGER NOT NULL CHECK (archived IN (0,1)),
  row_version INTEGER NOT NULL CHECK (row_version > 0)
)
--@@
CREATE TABLE analytics_report_revision (
  id INTEGER PRIMARY KEY,
  uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  report_id INTEGER NOT NULL REFERENCES analytics_report_definition(id) ON DELETE RESTRICT,
  revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  granularity INTEGER NOT NULL CHECK (granularity BETWEEN 0 AND 4),
  comparison INTEGER CHECK (comparison IS NULL OR comparison BETWEEN 0 AND 4),
  visualization INTEGER NOT NULL CHECK (visualization BETWEEN 0 AND 8),
  algorithm_version INTEGER NOT NULL CHECK (algorithm_version > 0),
  created_at INTEGER NOT NULL,
  UNIQUE (report_id, revision_no)
)
--@@
CREATE TABLE analytics_report_measure (
  report_revision_id INTEGER NOT NULL REFERENCES analytics_report_revision(id) ON DELETE RESTRICT,
  sort_order INTEGER NOT NULL CHECK (sort_order BETWEEN 0 AND 7),
  measure INTEGER NOT NULL CHECK (measure BETWEEN 0 AND 21),
  PRIMARY KEY (report_revision_id, sort_order),
  UNIQUE (report_revision_id, measure)
)
--@@
CREATE TABLE analytics_report_dimension (
  report_revision_id INTEGER NOT NULL REFERENCES analytics_report_revision(id) ON DELETE RESTRICT,
  sort_order INTEGER NOT NULL CHECK (sort_order BETWEEN 0 AND 2),
  dimension INTEGER NOT NULL CHECK (dimension BETWEEN 0 AND 11),
  PRIMARY KEY (report_revision_id, sort_order),
  UNIQUE (report_revision_id, dimension)
)
--@@
CREATE TABLE analytics_report_sort (
  report_revision_id INTEGER NOT NULL REFERENCES analytics_report_revision(id) ON DELETE RESTRICT,
  sort_order INTEGER NOT NULL CHECK (sort_order BETWEEN 0 AND 3),
  sort_kind INTEGER NOT NULL CHECK (sort_kind IN (0,1)),
  target_key INTEGER NOT NULL CHECK (target_key >= 0),
  direction INTEGER NOT NULL CHECK (direction IN (0,1)),
  PRIMARY KEY (report_revision_id, sort_order)
)
--@@
CREATE TABLE analytics_report_filter_node (
  id INTEGER PRIMARY KEY,
  report_revision_id INTEGER NOT NULL REFERENCES analytics_report_revision(id) ON DELETE RESTRICT,
  parent_node_id INTEGER REFERENCES analytics_report_filter_node(id) ON DELETE RESTRICT,
  child_order INTEGER NOT NULL CHECK (child_order >= 0),
  node_kind INTEGER NOT NULL CHECK (node_kind BETWEEN 0 AND 4),
  filter_field INTEGER CHECK (filter_field IS NULL OR filter_field BETWEEN 0 AND 22),
  filter_operator INTEGER CHECK (filter_operator IS NULL OR filter_operator BETWEEN 0 AND 8),
  CHECK ((node_kind = 4 AND filter_field IS NOT NULL AND filter_operator IS NOT NULL) OR
         (node_kind <> 4 AND filter_field IS NULL AND filter_operator IS NULL)),
  UNIQUE (report_revision_id, parent_node_id, child_order)
)
--@@
CREATE UNIQUE INDEX analytics_report_one_filter_root
ON analytics_report_filter_node(report_revision_id)
WHERE parent_node_id IS NULL
--@@
CREATE TABLE analytics_report_filter_value (
  filter_node_id INTEGER NOT NULL REFERENCES analytics_report_filter_node(id) ON DELETE RESTRICT,
  value_order INTEGER NOT NULL CHECK (value_order >= 0),
  value_kind INTEGER NOT NULL CHECK (value_kind BETWEEN 0 AND 5),
  first_long INTEGER,
  second_long INTEGER,
  stable_uid BLOB CHECK (stable_uid IS NULL OR length(stable_uid) = 16),
  text_value TEXT,
  flag_value INTEGER CHECK (flag_value IS NULL OR flag_value IN (0,1)),
  PRIMARY KEY (filter_node_id, value_order)
)
--@@
CREATE TABLE analytics_dashboard (
  id INTEGER PRIMARY KEY,
  uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  current_revision_id INTEGER REFERENCES analytics_dashboard_revision(id) ON DELETE RESTRICT,
  archived INTEGER NOT NULL CHECK (archived IN (0,1)),
  row_version INTEGER NOT NULL CHECK (row_version > 0)
)
--@@
CREATE TABLE analytics_dashboard_revision (
  id INTEGER PRIMARY KEY,
  uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  dashboard_id INTEGER NOT NULL REFERENCES analytics_dashboard(id) ON DELETE RESTRICT,
  revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  created_at INTEGER NOT NULL,
  UNIQUE (dashboard_id, revision_no)
)
--@@
CREATE TABLE analytics_dashboard_item (
  dashboard_revision_id INTEGER NOT NULL REFERENCES analytics_dashboard_revision(id) ON DELETE RESTRICT,
  report_id INTEGER NOT NULL REFERENCES analytics_report_definition(id) ON DELETE RESTRICT,
  sort_order INTEGER NOT NULL CHECK (sort_order BETWEEN 0 AND 23),
  width INTEGER NOT NULL CHECK (width IN (0,1)),
  PRIMARY KEY (dashboard_revision_id, sort_order),
  UNIQUE (dashboard_revision_id, report_id)
)
--@@
CREATE TABLE analytics_anomaly_rule (
  id INTEGER PRIMARY KEY,
  uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  current_revision_id INTEGER REFERENCES analytics_anomaly_rule_revision(id) ON DELETE RESTRICT,
  enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  row_version INTEGER NOT NULL CHECK (row_version > 0)
)
--@@
CREATE TABLE analytics_anomaly_rule_revision (
  id INTEGER PRIMARY KEY,
  uid BLOB NOT NULL UNIQUE CHECK (length(uid) = 16),
  anomaly_rule_id INTEGER NOT NULL REFERENCES analytics_anomaly_rule(id) ON DELETE RESTRICT,
  revision_no INTEGER NOT NULL CHECK (revision_no > 0),
  rule_type INTEGER NOT NULL CHECK (rule_type BETWEEN 0 AND 4),
  threshold_decimal TEXT NOT NULL CHECK (length(threshold_decimal) BETWEEN 1 AND 64),
  lookback_periods INTEGER NOT NULL CHECK (lookback_periods > 0),
  algorithm_version INTEGER NOT NULL CHECK (algorithm_version > 0),
  created_at INTEGER NOT NULL,
  UNIQUE (anomaly_rule_id, revision_no)
)
--@@
CREATE INDEX analytics_report_definition_active_order ON analytics_report_definition(archived, name, uid)
--@@
CREATE INDEX analytics_dashboard_active_order ON analytics_dashboard(archived, name, uid)
--@@
CREATE INDEX analytics_anomaly_rule_active_order ON analytics_anomaly_rule(enabled, uid)
--@@
CREATE TRIGGER analytics_report_revision_reject_update BEFORE UPDATE ON analytics_report_revision
BEGIN SELECT RAISE(ABORT, 'immutable analytics report revision update rejected'); END
--@@
CREATE TRIGGER analytics_report_revision_reject_delete BEFORE DELETE ON analytics_report_revision
BEGIN SELECT RAISE(ABORT, 'immutable analytics report revision delete rejected'); END
--@@
CREATE TRIGGER analytics_dashboard_revision_reject_update BEFORE UPDATE ON analytics_dashboard_revision
BEGIN SELECT RAISE(ABORT, 'immutable analytics dashboard revision update rejected'); END
--@@
CREATE TRIGGER analytics_dashboard_revision_reject_delete BEFORE DELETE ON analytics_dashboard_revision
BEGIN SELECT RAISE(ABORT, 'immutable analytics dashboard revision delete rejected'); END
--@@
CREATE TRIGGER analytics_anomaly_rule_revision_reject_update BEFORE UPDATE ON analytics_anomaly_rule_revision
BEGIN SELECT RAISE(ABORT, 'immutable anomaly rule revision update rejected'); END
--@@
CREATE TRIGGER analytics_anomaly_rule_revision_reject_delete BEFORE DELETE ON analytics_anomaly_rule_revision
BEGIN SELECT RAISE(ABORT, 'immutable anomaly rule revision delete rejected'); END
