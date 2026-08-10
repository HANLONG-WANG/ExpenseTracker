@file:Suppress("LongParameterList")

package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.ContentHash
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.StableEntityReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CommitGraphMergePlannerTest {
    @Test
    fun `closest graph ancestor wins without looking at timestamps`() {
        val root = commit(1)
        val localBase = commit(2)
        val remoteBase = commit(3)
        val localHead = commit(4)
        val remoteHead = commit(5)
        val graph = listOf(
            vertex(root),
            vertex(localBase, root),
            vertex(remoteBase, localBase),
            vertex(localHead, remoteBase),
            vertex(remoteHead, remoteBase),
        )

        assertEquals(remoteBase, CommitGraphMergePlanner.commonAncestor(graph, localHead, remoteHead))
    }

    @Test
    fun `same transaction fork is never resolved by timestamp`() {
        val root = commit(1)
        val ancestor = commit(2)
        val localHead = commit(3)
        val incomingHead = commit(4)
        val entity = StableEntityReference(EntityType.TRANSACTION, id(40))
        val plan = CommitGraphMergePlanner.plan(
            input(
                graph = listOf(vertex(root), vertex(ancestor, root), vertex(localHead, ancestor), vertex(incomingHead, ancestor)),
                localHead = localHead,
                incomingHead = incomingHead,
                ancestor = mapOf(entity to version(entity, 10, ancestor, 1)),
                local = mapOf(entity to version(entity, 11, localHead, 2)),
                incoming = mapOf(entity to version(entity, 12, incomingHead, 2)),
            ),
        ).success()

        assertFalse(plan.readyToApply)
        assertEquals(MergeConflictKind.TRANSACTION_REVISION_FORK, plan.conflicts.single().kind)
        assertEquals(null, plan.conflicts.single().resolution)
    }

    @Test
    fun `one sided edit applies automatically while delete versus edit conflicts`() {
        val root = commit(1)
        val ancestor = commit(2)
        val localHead = commit(3)
        val incomingHead = commit(4)
        val account = StableEntityReference(EntityType.ACCOUNT, id(30))
        val category = StableEntityReference(EntityType.CATEGORY, id(31))
        val ancestorVersions = mapOf(
            account to version(account, 10, ancestor, 1),
            category to version(category, 20, ancestor, 1),
        )
        val plan = CommitGraphMergePlanner.plan(
            input(
                graph = listOf(vertex(root), vertex(ancestor, root), vertex(localHead, ancestor), vertex(incomingHead, ancestor)),
                localHead = localHead,
                incomingHead = incomingHead,
                ancestor = ancestorVersions,
                local = mapOf(account to version(account, 10, ancestor, 1)),
                incoming = mapOf(
                    account to version(account, 11, incomingHead, 2),
                    category to version(category, 21, incomingHead, 2),
                ),
            ),
        ).success()

        assertTrue(plan.automaticDecisions.any { it is MergeDecision.KeepIncoming && it.entity == account })
        assertEquals(MergeConflictKind.DELETE_VERSUS_EDIT, plan.conflicts.single().kind)
    }

    @Test
    fun `purge tombstone always prevents resurrection and carries no entity content`() {
        val root = commit(1)
        val localHead = commit(2)
        val incomingHead = commit(3)
        val transaction = StableEntityReference(EntityType.TRANSACTION, id(50))
        val tombstone = PurgeTombstone(transaction, localHead, Instant.ofEpochSecond(50), 3)
        val plan = CommitGraphMergePlanner.plan(
            input(
                graph = listOf(vertex(root), vertex(localHead, root), vertex(incomingHead, root)),
                localHead = localHead,
                incomingHead = incomingHead,
                ancestor = emptyMap(),
                local = emptyMap(),
                incoming = mapOf(transaction to version(transaction, 99, incomingHead, 99)),
                localTombstones = mapOf(transaction to tombstone),
            ),
        ).success()

        assertTrue(plan.readyToApply)
        assertEquals(tombstone, (plan.automaticDecisions.single() as MergeDecision.KeepPurgeTombstone).tombstone)
        assertEquals(
            setOf("entity", "purgeCommitId", "purgedAt", "purgeGeneration", "lifecycle"),
            PurgeTombstone::class.java.declaredFields.map { it.name }.toSet(),
        )
    }

    @Test
    fun `book and base currency mismatch fail closed`() {
        val root = commit(1)
        val base = input(listOf(vertex(root)), root, root, emptyMap(), emptyMap(), emptyMap())
        assertTrue(CommitGraphMergePlanner.plan(base.copy(incomingBook = MergeBookIdentity(id(9), "JPY"))) is DomainResult.Failure)
        assertTrue(CommitGraphMergePlanner.plan(base.copy(incomingBook = MergeBookIdentity(id(8), "USD"))) is DomainResult.Failure)
    }

    private fun input(
        graph: List<MergeCommitVertex>,
        localHead: BookCommitId,
        incomingHead: BookCommitId,
        ancestor: Map<StableEntityReference, MergeEntityVersion>,
        local: Map<StableEntityReference, MergeEntityVersion>,
        incoming: Map<StableEntityReference, MergeEntityVersion>,
        localTombstones: Map<StableEntityReference, PurgeTombstone> = emptyMap(),
    ) = ThreeWayMergeInput(
        MergeBookIdentity(id(8), "JPY"),
        MergeBookIdentity(id(8), "JPY"),
        graph,
        localHead,
        incomingHead,
        ancestor,
        local,
        incoming,
        localTombstones,
        emptyMap(),
    )

    private fun vertex(id: BookCommitId, parent: BookCommitId? = null) = MergeCommitVertex(id, listOfNotNull(parent))
    private fun commit(value: Int) = BookCommitId(id(value))
    private fun version(entity: StableEntityReference, hash: Int, commit: BookCommitId, generation: Long) = MergeEntityVersion(
        entity.type,
        entity.stableId,
        ContentHash(Hash256.sha256(byteArrayOf(hash.toByte()))),
        commit,
        generation,
    )
    private fun id(value: Int) = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT) { value.toByte() }).success()
    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success).value
}
