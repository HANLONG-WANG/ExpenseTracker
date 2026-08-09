from __future__ import annotations

import copy
import unittest

from scripts import validate_p29_export as validator


class P29ExportContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, old: str, new: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(old, sources[path])
        sources[path] = sources[path].replace(old, new, 1)
        return validator.validate_sources(sources)

    def test_missing_pdf_document_writer_is_rejected(self) -> None:
        self.assertTrue(self.mutate("StreamingExportEngine.kt", "val document = PdfDocument()", "val document = RemovedPdfWriter()"))

    def test_vault_table_reference_is_rejected(self) -> None:
        errors = self.mutate("SecureRoomLedgerExportQueryPort.kt", "SELECT local_revision", "SELECT card_vault_secret,local_revision")
        self.assertTrue(any("card_vault_secret" in error for error in errors))

    def test_unbounded_export_page_is_rejected(self) -> None:
        self.assertTrue(self.mutate("StreamingExportEngine.kt", "PAGE_SIZE = 256", "PAGE_SIZE = 100_000"))

    def test_saf_atomic_rename_is_rejected(self) -> None:
        self.assertTrue(self.mutate("SafExportDestination.kt", "providerTemporary.renameTo(descriptor.fileName)", "providerTemporary.removedRename(descriptor.fileName)"))

    def test_worker_payload_guard_is_rejected(self) -> None:
        self.assertTrue(self.mutate("ExportWorker.kt", "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)", "true"))

    def test_coordinate_opt_in_is_rejected(self) -> None:
        self.assertTrue(self.mutate("ExportModel.kt", "val defaultSelection", "val removedDefaultSelection"))

    def test_advisory_saf_can_write_gate_is_rejected(self) -> None:
        errors = self.mutate("ExportController.kt", "!root.exists()", "!root.canWrite()")
        self.assertTrue(any("canWrite" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
