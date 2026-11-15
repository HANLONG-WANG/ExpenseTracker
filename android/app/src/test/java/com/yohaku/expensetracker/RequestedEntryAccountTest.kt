package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class RequestedEntryAccountTest {
    private val bank=AccountEntity("b","bank","Bank A","BANK","JPY",0,"2026-10-01")
    private val target=bank.copy(id="target",name="Bank B")
    private val last=EventEntity("b","last","INCOME",bank.id,null,100,"JPY",100,"2026-10-10","","",createdAt=1)
    private val snapshot=BookSnapshot(BookEntity("b","Book","JPY","2026-10-01"),listOf(bank,target),emptyList(),listOf(last),emptyList(),null)

    @Test fun requestedIncomeAccountOverridesRecentAccountWhileOrdinaryEntryKeepsDefault() {
        assertEquals(bank.id,snapshot.newEntryDraft(EntryKind.INCOME,"2026-10-15").accountId)
        assertEquals(target.id,snapshot.newEntryDraft(EntryKind.INCOME,"2026-10-15",accountId=target.id).accountId)
    }

    @Test fun invalidRequestedIncomeAccountIsRejectedInsteadOfFallingBack() {
        for(accounts in listOf(listOf(bank),listOf(bank,target.copy(active=false)),listOf(bank,target.copy(type="CREDIT")),listOf(bank,target.copy(type="LOAN")))) {
            val failure=runCatching{snapshot.copy(accounts=accounts).newEntryDraft(EntryKind.INCOME,"2026-10-15",accountId=target.id)}.exceptionOrNull()
            assertTrue("Explicit target must be an active funds account",failure is IllegalArgumentException)
        }
    }
}
