@file:Suppress("MagicNumber", "ReturnCount")

package app.ledger.transfer.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.StableEntityReference

/** Stable-id three-way merge. It never uses timestamps as a conflict resolver. */
object CommitGraphMergePlanner {
    fun plan(input: ThreeWayMergeInput): DomainResult<ThreeWayMergePlan> {
        if (input.localBook.bookId != input.incomingBook.bookId) {
            return DomainResult.Failure(RestoreFailure.BookMismatch)
        }
        if (input.localBook.baseCurrencyCode != input.incomingBook.baseCurrencyCode) {
            return DomainResult.Failure(RestoreFailure.BaseCurrencyMismatch)
        }
        val ancestor = commonAncestor(input.graph, input.localHead, input.incomingHead)
            ?: return DomainResult.Failure(RestoreFailure.IntegrityFailed)
        val decisions = mutableListOf<MergeDecision>()
        val conflicts = mutableListOf<MergeConflict>()
        val entities = (
            input.ancestorVersions.keys + input.localVersions.keys + input.incomingVersions.keys +
                input.localTombstones.keys + input.incomingTombstones.keys
            ).distinct().sortedWith(compareBy({ it.type.ordinal }, { it.stableId.hex() }))
        entities.forEachIndexed { index, entity ->
            val localTombstone = input.localTombstones[entity]
            val incomingTombstone = input.incomingTombstones[entity]
            val winningTombstone = listOfNotNull(localTombstone, incomingTombstone)
                .maxWithOrNull(compareBy<PurgeTombstone> { it.purgeGeneration }.thenBy { it.purgeCommitId.value.hex() })
            if (winningTombstone != null) {
                decisions += MergeDecision.KeepPurgeTombstone(entity, winningTombstone)
                return@forEachIndexed
            }
            val ancestorVersion = input.ancestorVersions[entity]
            val localVersion = input.localVersions[entity]
            val incomingVersion = input.incomingVersions[entity]
            when {
                same(localVersion, incomingVersion) -> decisions += MergeDecision.KeepLocal(entity, localVersion)
                same(localVersion, ancestorVersion) -> decisions += MergeDecision.KeepIncoming(entity, incomingVersion)
                same(incomingVersion, ancestorVersion) -> decisions += MergeDecision.KeepLocal(entity, localVersion)
                else -> conflicts += MergeConflict(
                    id = MergeConflictId(deterministicConflictId(entity, index)),
                    sessionId = MergeSessionId(deterministicSessionId(input.localHead, input.incomingHead)),
                    kind = conflictKind(entity.type, ancestorVersion, localVersion, incomingVersion),
                    ancestor = ancestorVersion,
                    local = localVersion,
                    incoming = incomingVersion,
                    purgeTombstone = null,
                    resolution = null,
                )
            }
        }
        return DomainResult.Success(ThreeWayMergePlan(ancestor, input.localHead, input.incomingHead, decisions, conflicts))
    }

    /** Closest common graph ancestor by combined edge distance; stable id breaks only structural ties. */
    fun commonAncestor(
        graph: List<MergeCommitVertex>,
        localHead: BookCommitId,
        incomingHead: BookCommitId,
    ): BookCommitId? {
        val parents = graph.associate { it.id to it.parentIds }
        if (localHead !in parents || incomingHead !in parents) return null
        val localDistances = distances(localHead, parents) ?: return null
        val incomingDistances = distances(incomingHead, parents) ?: return null
        return localDistances.keys.intersect(incomingDistances.keys).minWithOrNull(
            compareBy<BookCommitId> { Math.addExact(localDistances.getValue(it), incomingDistances.getValue(it)) }
                .thenBy { maxOf(localDistances.getValue(it), incomingDistances.getValue(it)) }
                .thenBy { it.value.hex() },
        )
    }

    private fun distances(
        head: BookCommitId,
        parents: Map<BookCommitId, List<BookCommitId>>,
    ): Map<BookCommitId, Int>? {
        val result = linkedMapOf<BookCommitId, Int>()
        val queue = ArrayDeque<Pair<BookCommitId, Int>>()
        queue += head to 0
        while (queue.isNotEmpty()) {
            val (current, distance) = queue.removeFirst()
            val previous = result[current]
            if (previous != null && previous <= distance) continue
            result[current] = distance
            val directParents = parents[current] ?: return null
            directParents.forEach { parent ->
                if (parent !in parents) return null
                queue += parent to Math.addExact(distance, 1)
            }
        }
        return result
    }

    private fun conflictKind(
        entityType: EntityType,
        ancestor: MergeEntityVersion?,
        local: MergeEntityVersion?,
        incoming: MergeEntityVersion?,
    ): MergeConflictKind = when {
        (local == null) != (incoming == null) && ancestor != null -> MergeConflictKind.DELETE_VERSUS_EDIT
        entityType == EntityType.TRANSACTION -> MergeConflictKind.TRANSACTION_REVISION_FORK
        else -> MergeConflictKind.BOTH_MODIFIED
    }

    private fun same(left: MergeEntityVersion?, right: MergeEntityVersion?): Boolean = when {
        left == null || right == null -> left == right
        else ->
            left.entityType == right.entityType && left.entityId == right.entityId &&
                left.contentHash == right.contentHash && left.generation == right.generation
    }

    private fun deterministicSessionId(local: BookCommitId, incoming: BookCommitId): StableId = StableId.fromBytes(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("merge-session".toByteArray(Charsets.US_ASCII) + local.value.bytes + incoming.value.bytes)
            .copyOf(StableId.BYTE_COUNT),
    ).required()

    private fun deterministicConflictId(entity: StableEntityReference, ordinal: Int): StableId = StableId.fromBytes(
        java.security.MessageDigest.getInstance("SHA-256").digest(
            "merge-conflict".toByteArray(Charsets.US_ASCII) + entity.stableId.bytes +
                byteArrayOf(entity.type.ordinal.toByte()) + java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ordinal).array(),
        ).copyOf(StableId.BYTE_COUNT),
    ).required()

    private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun <T> DomainResult<T>.required(): T = (this as DomainResult.Success).value
}
