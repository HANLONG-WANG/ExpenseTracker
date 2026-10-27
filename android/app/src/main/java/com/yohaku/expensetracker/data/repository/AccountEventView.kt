package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.db.PostingEntity

/** Display actual account movements when present; otherwise retain the planned side. */
fun BookSnapshot.accountEventPostings(event:EventEntity,accountId:String):List<PostingEntity> {
    val sides=storedPostingsByEvent[event.id].orEmpty().filter{it.accountId==accountId}
    val actual=sides.filter{it.confirmed}
    return actual.ifEmpty{sides}
}
