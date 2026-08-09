from __future__ import annotations

import copy
import unittest

from scripts import validate_p30_backup as validator


class P30BackupContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, old: str, new: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(old, sources[path])
        sources[path] = sources[path].replace(old, new, 1)
        return validator.validate_sources(sources)

    def test_streaming_aead_is_required(self) -> None:
        self.assertTrue(self.mutate("PortableBackupContainer.kt", "newEncryptingStream", "removedEncryptingStream"))

    def test_zip64_is_required(self) -> None:
        self.assertTrue(self.mutate("PortableBackupContainer.kt", "Zip64Mode.Always", "Zip64Mode.Never"))

    def test_final_complete_insert_is_required(self) -> None:
        self.assertTrue(self.mutate("SqlCipherBackupCatalog.kt", "INSERT INTO backup_snapshot", "UPDATE backup_snapshot"))

    def test_drive_resumable_range_is_required(self) -> None:
        self.assertTrue(self.mutate("DriveResumableBackupClient.kt", 'header("Range"', 'removedHeader("Range"'))

    def test_drive_repository_isolation_is_required(self) -> None:
        self.assertTrue(self.mutate("DriveResumableBackupClient.kt", "ensureRepositoryFolder", "removedRepositoryFolder"))

    def test_drive_reference_gc_is_required(self) -> None:
        self.assertTrue(self.mutate("DriveBackupRepositoryPublisher.kt", "pruneUnreferenced", "removedReferencePrune"))

    def test_worker_payload_guard_is_required(self) -> None:
        self.assertTrue(self.mutate("BackupWorker.kt", "inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)", "true"))

    def test_password_semantics_redaction_is_required(self) -> None:
        self.assertTrue(self.mutate("BackupFlowScreen.kt", "hideValueFromSemantics = true", "hideValueFromSemantics = false"))


if __name__ == "__main__":
    unittest.main()
