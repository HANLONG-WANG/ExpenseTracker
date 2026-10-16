package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import kotlin.math.*

enum class AnalysisModule { CATEGORIES, MERCHANTS, TREND, ACTIVITIES, COMPARISON }
fun LedgerRepository.analysisModules(snapshot:BookSnapshot):List<AnalysisModule> = snapshot.analysisPreferences?.let{json.decodeFromString<List<String>>(it.modulesJson).map(AnalysisModule::valueOf)}.orEmpty()
fun LedgerRepository.analysisPins(snapshot:BookSnapshot):Set<AnalysisModule> = snapshot.analysisPreferences?.let{json.decodeFromString<List<String>>(it.pinnedJson).map(AnalysisModule::valueOf).toSet()}.orEmpty()
suspend fun LedgerRepository.saveAnalysisPreferences(bookId:String,modules:List<AnalysisModule>,pinned:Set<AnalysisModule>)=db.withTransaction {
    require(dao.book(bookId)!=null);require(modules.distinct()==modules&&pinned.all{it in modules})
    dao.putAnalysisPreferences(AnalysisPreferencesEntity(bookId,json.encodeToString(modules.map{it.name}),json.encodeToString(pinned.map{it.name})))
}
suspend fun LedgerRepository.saveMerchant(bookId:String,name:String,note:String="",id:String?=null,active:Boolean=true):String=db.withTransaction {
    require(dao.book(bookId)!=null);require(name.isNotBlank()){ "请输入商户原名 / お店の元の名前を入力してください" }
    val old=id?.let{dao.merchant(bookId,it)?:error("商户不属于本账本 / 別の帳簿のお店です")}
    val merchant=MerchantEntity(bookId,old?.id?:this.id(),name.trim(),note,active)
    dao.putMerchant(merchant);merchant.id
}
suspend fun LedgerRepository.saveMerchantLocation(bookId:String,merchantId:String,name:String,latitude:Double?,longitude:Double?,radiusMeters:Int=100,id:String?=null):String=db.withTransaction {
    require(dao.merchant(bookId,merchantId)!=null)
    require(name.isNotBlank()){ "请输入地点名称 / 場所の名前を入力してください" }
    require((latitude==null)==(longitude==null)){"经纬度请同时填写 / 緯度・経度を両方入力してください"}
    require(latitude==null||latitude.isFinite()&&latitude in -90.0..90.0&&longitude!!.isFinite()&&longitude in -180.0..180.0){"经纬度超出有效范围 / 緯度・経度が有効範囲外です"}
    require(radiusMeters>0){"匹配半径应大于零 / 選択範囲は0より大きくしてください"}
    val old=id?.let{dao.merchantLocation(bookId,it)?:error("地点不属于本账本 / 別の帳簿の場所です")}
    require(old==null||old.merchantId==merchantId)
    val location=MerchantLocationEntity(bookId,old?.id?:this.id(),merchantId,name.trim(),latitude,longitude,radiusMeters)
    dao.putMerchantLocation(location);location.id
}
data class MerchantCandidate(val merchant:MerchantEntity,val location:MerchantLocationEntity,val distanceMeters:Double,val accuracyMeters:Double=0.0) {
    val trustworthy get()=distanceMeters+accuracyMeters<=location.radiusMeters
}
/** Include uncertain overlaps for manual choice; auto-fill requires the entire accuracy circle inside one place. */
fun BookSnapshot.merchantCandidates(latitude:Double,longitude:Double,accuracyMeters:Double=0.0):List<MerchantCandidate> {
    require(latitude.isFinite()&&latitude in -90.0..90.0&&longitude.isFinite()&&longitude in -180.0..180.0)
    require(accuracyMeters.isFinite()&&accuracyMeters>=0)
    return merchantLocations.filter{it.bookId==book.id}.mapNotNull{location->
        val merchant=merchants.find{it.bookId==book.id&&it.id==location.merchantId&&it.active}?:return@mapNotNull null
        val lat=location.latitude?:return@mapNotNull null;val lon=location.longitude!!
        val dLat=Math.toRadians(lat-latitude);val dLon=Math.toRadians(lon-longitude)
        val a=sin(dLat/2).pow(2)+cos(Math.toRadians(latitude))*cos(Math.toRadians(lat))*sin(dLon/2).pow(2)
        val distance=6371000.0*2*atan2(sqrt(a),sqrt((1-a).coerceAtLeast(0.0)))
        if(distance<=location.radiusMeters+accuracyMeters)MerchantCandidate(merchant,location,distance,accuracyMeters)else null
    }.sortedWith(compareByDescending<MerchantCandidate>{it.trustworthy}.thenBy{it.distanceMeters}).distinctBy{it.merchant.id}
}

suspend fun LedgerRepository.setFriendActive(bookId:String,friendId:String,active:Boolean)=db.withTransaction {
    val old=dao.friend(bookId,friendId)?:error("朋友不属于本账本 / 別の帳簿の友達です")
    dao.putFriend(old.copy(active=active))
}
