package app.ledger.core.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AttachmentMetadataPolicyTest {
    @Test
    fun displayNamesMimeTypesAndExtensionsAreNormalizedWithoutPaths() {
        assertEquals("private name.pdf", AttachmentMetadataPolicy.sanitizeDisplayName("../private/name.pdf"))
        assertEquals("application/pdf", AttachmentMetadataPolicy.normalizeMimeType(" Application/PDF "))
        assertEquals("application/octet-stream", AttachmentMetadataPolicy.normalizeMimeType("invalid"))
        assertEquals("pdf", AttachmentMetadataPolicy.normalizeExtension(".PDF", "ignored.txt"))
        assertNull(AttachmentMetadataPolicy.normalizeExtension("../../", "no-extension"))
    }

    @Test
    fun progressIsBoundedEvenWhenProviderSizeIsInaccurate() {
        assertEquals(1f, AttachmentImportProgress(20, 10).fraction)
        assertEquals(0f, AttachmentImportProgress(0, 10).fraction)
        assertNull(AttachmentImportProgress(10, null).fraction)
    }
}
