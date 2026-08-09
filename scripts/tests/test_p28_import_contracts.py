from __future__ import annotations

import copy
import unittest

from scripts import validate_p28_import as validator


class P28ImportMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, before: str, after: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(before, sources[path])
        sources[path] = sources[path].replace(before, after)
        return validator.validate_sources(sources)

    def test_commons_csv_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("AndroidCsvImportReader.kt", "org.apache.commons.csv.CSVFormat", "local.csv.Parser"))

    def test_fast_excel_stream_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("FastExcelImportReader.kt", "sheet.openStream().use", "sheet.readAllRows().use"))

    def test_icu_detection_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("AndroidCsvImportReader.kt", "com.ibm.icu.text.CharsetDetector", "java.nio.charset.Charset"))

    def test_encrypted_staging_raw_table_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SqlCipherStagingRepository.kt", "staging_raw_row", "temporary_raw_row"))

    def test_atomic_shadow_exchange_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("SecureShadowLedgerAccess.kt", "ATOMIC_MOVE", "REPLACE_EXISTING"))

    def test_unresolved_duplicate_block_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ImportPreparationService.kt", "DUPLICATE_REQUIRES_RESOLUTION", "DUPLICATE_WARNING"))

    def test_split_rejection_cannot_be_removed(self) -> None:
        self.assertTrue(self.mutate("ImportWorkflow.kt", "IMPORT_SEPARATE_TRANSACTIONS_REQUIRED", "SPLIT_ACCEPTED"))

    def test_worker_input_privacy_cannot_be_weakened(self) -> None:
        self.assertTrue(self.mutate("ImportWorker.kt", "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)", "inputData.keyValueMap.isNotEmpty()"))

    def test_structured_type_coverage_cannot_be_reduced(self) -> None:
        self.assertTrue(self.mutate("ImportWorkflow.kt", 'BUDGET("budgets", 14)', 'ARCHIVE("archives", 14)'))

    def test_sensitive_preview_semantics_cannot_be_restored(self) -> None:
        self.assertTrue(self.mutate("ImportWizardScreen.kt", "clearAndSetSemantics", "semantics"))


if __name__ == "__main__":
    unittest.main()
