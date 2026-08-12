CREATE TABLE projection_family_state (
  family INTEGER PRIMARY KEY CHECK (family BETWEEN 0 AND 14),
  as_of_local_revision INTEGER NOT NULL CHECK (as_of_local_revision >= 0),
  as_of_valuation_revision INTEGER NOT NULL CHECK (as_of_valuation_revision >= 0)
)
