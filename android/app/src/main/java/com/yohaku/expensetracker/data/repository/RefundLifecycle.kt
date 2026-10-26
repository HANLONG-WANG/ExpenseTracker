package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.domain.model.EntryKind

/** The same visible/deletion-batch scope used when moving an original purchase. */
fun BookSnapshot.linkedLifecycleRefunds(event:EventEntity,restoring:Boolean):List<EventEntity> {
    if(restoring&&event.deletionGroupId!=null)return events.filter{it.id!=event.id&&it.deletedAt!=null&&it.deletionGroupId==event.deletionGroupId}
    if(event.kind!=EntryKind.EXPENSE.name)return emptyList()
    val linked=settlements.filter{it.originalEventId==event.id}.map{it.eventId}.toSet()
    return events.filter{it.id in linked&&if(restoring)(if(event.deletionGroupId!=null)it.deletionGroupId==event.deletionGroupId else it.deletionGroupId==null&&it.deletedAt==event.deletedAt) else it.deletedAt==null}
}
