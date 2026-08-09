package app.ledger.transfer.data

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportFailure
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafExportDestinationDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree = DocumentsContract.buildTreeDocumentUri(P29TestDocumentsProvider.AUTHORITY, P29TestDocumentsProvider.ROOT_ID)

    @Before
    fun setUp() {
        P29TestDocumentsProvider.permissionRevoked = false
        context.contentResolver.acquireContentProviderClient(P29TestDocumentsProvider.AUTHORITY)?.close()
        context.grantUriPermission(
            context.packageName,
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        requireNotNull(DocumentFile.fromTreeUri(context, tree)).listFiles().forEach(DocumentFile::delete)
    }

    @After
    fun tearDown() {
        P29TestDocumentsProvider.permissionRevoked = false
        context.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun nameConflictPreservesExistingFileUntilExplicitOverwriteThenPublishesAtomically() {
        val grantedRoot = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        assertTrue("test SAF root must be queryable", grantedRoot.exists())
        val existing = requireNotNull(grantedRoot.createFile("text/csv", "transactions.csv"))
        context.contentResolver.openOutputStream(existing.uri, "rwt")!!.use { it.write("old-ledger-export".toByteArray()) }
        val conflict = destination(overwrite = false)
        conflict.openTemporary().use { it.write("new-complete-export".toByteArray()) }
        assertEquals(ExportFailure.NameConflict, (conflict.publish(RESULT) as DomainResult.Failure).error)
        assertEquals("old-ledger-export", read(requireNotNull(grantedRoot.findFile("transactions.csv"))))

        val overwrite = destination(overwrite = true)
        overwrite.openTemporary().use { it.write("new-complete-export".toByteArray()) }
        val published = overwrite.publish(RESULT)
        assertTrue(published is DomainResult.Success)
        assertEquals("new-complete-export", read(requireNotNull(grantedRoot.findFile("transactions.csv"))))
        assertTrue(grantedRoot.listFiles().none { it.name?.contains(".partial") == true || it.name?.contains(".previous") == true })
    }

    @Test
    fun revokedPermissionReturnsTypedStateAndCancelCleanupRemovesAppTemporary() {
        val destination = destination(overwrite = false)
        destination.openTemporary().use { it.write("incomplete".toByteArray()) }
        P29TestDocumentsProvider.permissionRevoked = true
        assertEquals(ExportFailure.PermissionRevoked, (destination.publish(RESULT) as DomainResult.Failure).error)
        P29TestDocumentsProvider.permissionRevoked = false
        assertTrue(destination.cleanup())
        assertFalse(destination.hasCompleteTemporary())
    }

    private fun destination(overwrite: Boolean) = SafExportDestination(
        context,
        StableId.fromUuid(UUID.randomUUID()),
        tree,
        ExportDescriptor(
            ExportContent.CURRENT_FILTER,
            ExportFormat.CSV,
            "transactions.csv",
            fields = setOf(ExportField.TRANSACTION_ID),
            filterSummary = "Current filter",
            overwriteConfirmed = overwrite,
        ),
    )

    private fun read(document: DocumentFile): String = context.contentResolver.openInputStream(document.uri)!!.bufferedReader().use { it.readText() }

    private companion object {
        val RESULT = ExportResult(1, 1, 19, "text/csv")
    }
}
