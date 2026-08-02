from __future__ import annotations

import unittest

from scripts import validate_p09_security as validator


class P09SecurityContractMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = validator.load_sources()

    def mutated(self, filename: str, old: str, new: str) -> dict[str, str]:
        sources = dict(self.sources)
        sources[filename] = sources[filename].replace(old, new)
        return sources

    def test_rejects_device_kek_requiring_user_authentication(self) -> None:
        source = self.sources["AndroidKeystoreKeys.kt"]
        first = source.find(".setUserAuthenticationRequired(false)")
        sources = dict(self.sources)
        sources["AndroidKeystoreKeys.kt"] = source[:first] + source[first:].replace(
            ".setUserAuthenticationRequired(false)", ".setUserAuthenticationRequired(true)", 1
        )
        errors = validator.validate_sources(sources)
        self.assertTrue(any("setUserAuthenticationRequired(false)" in error for error in errors))

    def test_rejects_vault_auth_window_instead_of_per_action(self) -> None:
        errors = validator.validate_sources(
            self.mutated("AndroidKeystoreKeys.kt", "setUserAuthenticationParameters(\n                0,", "setUserAuthenticationParameters(\n                30,")
        )
        self.assertTrue(any("auth-per-use" in error for error in errors))

    def test_rejects_biometric_enrollment_dropping_the_device_credential_fallback(self) -> None:
        errors = validator.validate_sources(
            self.mutated(
                "AndroidKeystoreKeys.kt",
                ".setInvalidatedByBiometricEnrollment(false)",
                ".setInvalidatedByBiometricEnrollment(true)",
            )
        )
        self.assertTrue(any("setInvalidatedByBiometricEnrollment(false)" in error for error in errors))

    def test_rejects_preauthenticated_associated_data_use(self) -> None:
        errors = validator.validate_sources(
            self.mutated(
                "AndroidKeystoreKeys.kt",
                "fun prepareVaultWrapCipher(bookAliasSuffix: String)",
                "fun prepareVaultWrapCipher(bookAliasSuffix: String, associatedData: ByteArray)",
            )
        )
        self.assertTrue(any("before CryptoObject" in error for error in errors))

    def test_rejects_database_authority_on_headless_lease(self) -> None:
        errors = validator.validate_sources(
            self.mutated(
                "BookSessionManager.kt",
                "class HeadlessBookLease internal constructor(",
                "class HeadlessBookLease(val database: LedgerDatabase) internal constructor(",
            )
        )
        self.assertTrue(any("exposes database" in error for error in errors))

    def test_rejects_security_code_copy_action(self) -> None:
        errors = validator.validate_sources(
            self.mutated("VaultAuthentication.kt", "COPY_PAN,", "COPY_PAN,\n    COPY_SECURITY_CODE,")
        )
        self.assertTrue(any("security-code copy" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
