from __future__ import annotations

import copy
import unittest
from unittest.mock import patch

from scripts import validate_p07_database as validator
from scripts.validate_p06_accounting import validate_project_state


class P07DatabaseContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.primary = validator.load_catalog(validator.PRIMARY_CATALOG)
        cls.staging = validator.load_catalog(validator.STAGING_CATALOG)

    def test_rejects_missing_frozen_table(self) -> None:
        mutated = copy.deepcopy(self.primary)
        mutated["tables"] = [table for table in mutated["tables"] if table["name"] != "journal_entry"]
        with self.assertRaisesRegex(validator.ValidationError, "missing frozen tables"):
            validator.validate_inventory(mutated, self.staging)

    def test_rejects_missing_fts5(self) -> None:
        mutated = copy.deepcopy(self.primary)
        table = next(table for table in mutated["tables"] if table["name"] == "transaction_fts")
        table["createSql"] = str(table["createSql"]).replace("USING fts5", "USING fts4")
        with self.assertRaisesRegex(validator.ValidationError, "FTS5"):
            validator.validate_sql_contract(mutated, self.staging)

    def test_rejects_generic_json_storage(self) -> None:
        mutated = copy.deepcopy(self.primary)
        table = next(table for table in mutated["tables"] if table["name"] == "business_transaction")
        table["createSql"] = str(table["createSql"]).replace(
            "content_hash BLOB",
            "universal_json TEXT, content_hash BLOB",
        )
        with self.assertRaisesRegex(validator.ValidationError, "generic JSON"):
            validator.validate_sql_contract(mutated, self.staging)

    def test_rejects_upsert_annotation(self) -> None:
        original_read = validator.read

        def mutated_read(path):
            value = original_read(path)
            if path.name == "SchemaRegistry.kt":
                return value + "\n@Upsert suspend fun forbidden(): Unit\n"
            return value

        with patch.object(validator, "read", side_effect=mutated_read):
            with self.assertRaisesRegex(validator.ValidationError, "@Upsert"):
                validator.validate_kotlin_and_build()

    def test_rejects_destructive_migration_fallback(self) -> None:
        original_read = validator.read

        def mutated_read(path):
            value = original_read(path)
            if path == validator.ROOT / "build.gradle.kts":
                return value + "\nfallbackToDestructiveMigration()\n"
            return value

        with patch.object(validator, "read", side_effect=mutated_read):
            with self.assertRaisesRegex(validator.ValidationError, "destructive migration"):
                validator.validate_kotlin_and_build()

    def test_rejects_file_backed_temporary_storage(self) -> None:
        original_read = validator.read

        def mutated_read(path):
            value = original_read(path)
            if path.name == "LedgerDatabase.kt":
                return value.replace("temp_store = MEMORY", "temp_store = FILE")
            return value

        with patch.object(validator, "read", side_effect=mutated_read):
            with self.assertRaisesRegex(validator.ValidationError, "temp_store"):
                validator.validate_kotlin_and_build()

    def test_rejects_unregistered_primary_migrations(self) -> None:
        original_read = validator.read

        def mutated_read(path):
            value = original_read(path)
            if path.name == "LedgerDatabase.kt":
                return value.replace(
                    "LedgerMigrations.registered(context.applicationContext).forEach { migration -> addMigrations(migration) }",
                    "emptyList<androidx.room.migration.Migration>().forEach { migration -> addMigrations(migration) }",
                )
            return value

        with patch.object(validator, "read", side_effect=mutated_read):
            with self.assertRaisesRegex(validator.ValidationError, "primary migration registry"):
                validator.validate_kotlin_and_build()

    def test_p06_validator_retains_verified_stage_during_p07(self) -> None:
        project_state = validator.read(validator.ROOT / "docs" / "implementation" / "PROJECT_STATE.md")
        self.assertEqual([], validate_project_state(project_state))
        self.assertTrue(validate_project_state(project_state.replace("| P06 | VERIFIED |", "| P06 | IN_PROGRESS |")))

    def test_p07_validator_retains_verified_stage_during_later_stage(self) -> None:
        validator.validate_ledgers()
        original_read = validator.read

        def mutated_read(path):
            value = original_read(path)
            if path.name == "PROJECT_STATE.md":
                return value.replace("| P07 | VERIFIED |", "| P07 | IN_PROGRESS |")
            return value

        with patch.object(validator, "read", side_effect=mutated_read):
            with self.assertRaisesRegex(validator.ValidationError, "P07 project state"):
                validator.validate_ledgers()

    def test_p07_validator_rejects_screen_promotion_beyond_current_stage(self) -> None:
        original_read = validator.read

        def mutated_read(path):
            value = original_read(path)
            if path.name == "SCREEN_COVERAGE.csv":
                rows = value.splitlines()
                row_index = next(index for index, row in enumerate(rows) if row.startswith("SETG-001,"))
                rows[row_index] = rows[row_index].replace(",VERIFIED,", ",IN_PROGRESS,", 1)
                return "\n".join(rows) + "\n"
            return value

        with patch.object(validator, "read", side_effect=mutated_read):
            with self.assertRaisesRegex(validator.ValidationError, "promotion outside"):
                validator.validate_ledgers()


if __name__ == "__main__":
    unittest.main()
