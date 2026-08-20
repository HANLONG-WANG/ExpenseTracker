from __future__ import annotations

import unittest

from scripts import validate_p10_files_geo as validator


class P10FilesGeoContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutated(self, filename: str, old: str, new: str) -> dict[str, str]:
        sources = dict(self.sources)
        path = next(path for path in sources if path.endswith(filename))
        sources[path] = sources[path].replace(old, new)
        return sources

    def test_rejects_non_streaming_attachment_encryption(self) -> None:
        errors = validator.validate_sources(
            self.mutated("EncryptedAttachmentObjectStore.kt", "LedgerTink::streamingAead", "LedgerTink::aead")
        )
        self.assertTrue(any("streamingAead" in error for error in errors))

    def test_rejects_reusable_external_open_grant(self) -> None:
        errors = validator.validate_sources(
            self.mutated("SecureAttachmentProvider.kt", "grants.remove(token)", "grants[token]")
        )
        self.assertTrue(any("grants.remove" in error for error in errors))

    def test_rejects_disconnected_attachment_session(self) -> None:
        errors = validator.validate_sources(
            self.mutated("SecureBookAttachmentObjectPort.kt", "SecureAttachmentProviderProcess.install", "DetachedProvider.install")
        )
        self.assertTrue(any("ProviderProcess.install" in error for error in errors))

    def test_rejects_waiting_longer_than_three_seconds(self) -> None:
        errors = validator.validate_sources(
            self.mutated("ForegroundLocationClient.kt", "MAXIMUM_SAVE_WAIT_MILLIS: Long = 3_000L", "MAXIMUM_SAVE_WAIT_MILLIS: Long = 4_000L")
        )
        self.assertTrue(any("3_000L" in error for error in errors))

    def test_rejects_background_location_in_production(self) -> None:
        errors = validator.validate_sources(
            self.mutated("ForegroundLocationClient.kt", "enum class LocationInfrastructureError", "val permission = ACCESS_BACKGROUND_LOCATION\n\nenum class LocationInfrastructureError")
        )
        self.assertTrue(any("background location" in error for error in errors))

    def test_rejects_map_without_accessible_fallback(self) -> None:
        errors = validator.validate_sources(
            self.mutated("LedgerMap.kt", "AccessibleMapRows", "HiddenMapRows")
        )
        self.assertTrue(any("AccessibleMapRows" in error for error in errors))

    def test_rejects_removing_cross_transaction_attachment_reuse(self) -> None:
        errors = validator.validate_sources(
            self.mutated("OrdinaryRecordScreens.kt", "actions.onReuseAttachment(attachment.id)", "Unit")
        )
        self.assertTrue(any("cross-transaction attachment reuse" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
