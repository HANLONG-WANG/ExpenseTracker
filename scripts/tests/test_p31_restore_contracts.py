from __future__ import annotations

import copy
import unittest

from scripts import validate_p31_restore as validator


class P31RestoreContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.source_map()

    def mutate(self, filename: str, old: str, new: str) -> list[str]:
        sources = copy.deepcopy(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        self.assertIn(old, sources[path])
        sources[path] = sources[path].replace(old, new)
        return validator.validate_sources(sources)

    def test_streaming_hash_verification_is_required(self) -> None:
        self.assertTrue(self.mutate("RestoreMaterializer.kt", "RestoreObjectStreamVerifier.copyAndVerify", "removedVerifier"))

    def test_atomic_exchange_is_required(self) -> None:
        self.assertTrue(self.mutate("SecureRoomRestoreLedgerApplicationPort.kt", "StandardCopyOption.ATOMIC_MOVE", "StandardCopyOption.REPLACE_EXISTING"))

    def test_non_cancellable_exchange_is_required(self) -> None:
        self.assertTrue(self.mutate("RestoreCoordinator.kt", "withContext(NonCancellable)", "withContext(Dispatchers.IO)"))

    def test_merge_must_use_financial_coordinator(self) -> None:
        self.assertTrue(self.mutate("SecureRoomMergeRestoreApplicationPort.kt", "DefaultFinancialMutationCoordinator", "RemovedCoordinator"))

    def test_purge_revalidation_is_required(self) -> None:
        self.assertTrue(self.mutate("RoomLogicalPurgeValidator.kt", "nonZeroAccountNets", "removedAccountNetCheck"))

    def test_purge_tombstone_priority_is_required(self) -> None:
        self.assertTrue(self.mutate("CommitGraphMergePlanner.kt", "KeepPurgeTombstone", "KeepIncoming"))

    def test_password_semantics_redaction_is_required(self) -> None:
        self.assertTrue(self.mutate("RestoreFlowScreen.kt", "hideValueFromSemantics = true", "hideValueFromSemantics = false"))

    def test_local_artifact_cleanup_is_required(self) -> None:
        self.assertTrue(self.mutate("LocalBookArtifactCleaner.kt", "pre-restore-safety-v1", "removed-safety-snapshots"))


if __name__ == "__main__":
    unittest.main()
