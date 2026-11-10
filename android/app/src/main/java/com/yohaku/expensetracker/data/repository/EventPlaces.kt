package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlin.math.*

internal fun EntryDraft.placeForNewEntry(bookId:String,eventId:String,today:String):EventPlaceEntity? {
    if(id!=null||kind!=EntryKind.EXPENSE||merchantId!=null||merchant.trim().isEmpty()||date!=today)return null
    return capturedPlace?.let{EventPlaceEntity(bookId,eventId,it.latitude,it.longitude,it.accuracyMeters)}
}
internal fun distanceMeters(latitude:Double,longitude:Double,lat:Double,lon:Double):Double {
    val dLat=Math.toRadians(lat-latitude);val dLon=Math.toRadians(lon-longitude)
    val a=sin(dLat/2).pow(2)+cos(Math.toRadians(latitude))*cos(Math.toRadians(lat))*sin(dLon/2).pow(2)
    return 6371000.0*2*atan2(sqrt(a),sqrt((1-a).coerceAtLeast(0.0)))
}
data class RecordedPlace(val key:String,val latitude:Double,val longitude:Double,val count:Int,val latestDate:String)
fun BookSnapshot.recordedPlaces(name:String):List<RecordedPlace> {
    if(name.isBlank())return emptyList()
    val places=eventPlaces.associateBy{it.eventId}
    val linked=eventMerchants.map{it.eventId}.toSet()
    val points=visibleEvents.filter{it.kind==EntryKind.EXPENSE.name&&normalizedMerchantName(it.merchant)==normalizedMerchantName(name)&&it.id !in linked}
        .sortedWith(compareByDescending<EventEntity>{it.date}.thenByDescending{it.createdAt}.thenBy{it.id})
        .mapNotNull{event->places[event.id]?.let{event to it}}
    val clusters=mutableListOf<MutableList<Pair<EventEntity,EventPlaceEntity>>>()
    for(point in points) {
        val cluster=clusters.firstOrNull{val anchor=it.first().second;distanceMeters(anchor.latitude,anchor.longitude,point.second.latitude,point.second.longitude)<=100.0}
        if(cluster==null)clusters.add(mutableListOf(point))else cluster.add(point)
    }
    return clusters.map{cluster->
        val best=cluster.minBy{it.second.accuracyMeters}.second
        RecordedPlace(cluster.first().first.id,best.latitude,best.longitude,cluster.size,cluster.first().first.date)
    }
}
data class MerchantPlaceInput(val name:String,val latitude:Double,val longitude:Double,val radiusMeters:Int=100)
suspend fun LedgerRepository.saveMerchantWithLocations(bookId:String,name:String,note:String="",id:String?=null,active:Boolean=true,places:List<MerchantPlaceInput> = emptyList(),confirmAdoption:Boolean=false):String=db.withTransaction {
    val merchantId=saveMerchant(bookId,name,note,id,active,confirmAdoption)
    places.forEach{saveMerchantLocation(bookId,merchantId,it.name,it.latitude,it.longitude,it.radiusMeters)}
    merchantId
}
