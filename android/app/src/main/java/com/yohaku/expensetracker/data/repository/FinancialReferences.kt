package com.yohaku.expensetracker.data.repository

/** Exact structured identities used by pool sources and limit anchors. These are not substring
 * matches: a note merely containing an ID is never rewritten or interpreted as a reference. */
internal fun financialReferenceIds(value:String):List<String> = when {
    value.startsWith("refund-allocation:")->value.removePrefix("refund-allocation:").split(':',limit=2)
    listOf("arrangement:","actual:","charge:","source:","refund:","rule:").any{value.startsWith(it)}->listOf(value.substringAfter(':'))
    else->listOf(value)
}
