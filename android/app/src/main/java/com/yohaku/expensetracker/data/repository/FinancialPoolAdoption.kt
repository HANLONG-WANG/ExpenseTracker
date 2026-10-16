package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import kotlinx.serialization.Serializable

@Serializable data class PoolAdoptedFee(val arrangementId:String,val date:String,val includedInRetained:Boolean=false,val waivedMinor:Long=0)

internal fun FinancialGraph.adoptedArrangements(accountId:String,mode:String,adoptExisting:Boolean)=currentArrangements.filter{change->
    val target=plans.first{it.id==change.targetPlanId}
    target.accountId==accountId&&target.mode==mode&&(target.origin=="POOL_RETAINED"||adoptExisting&&target.active&&target.origin=="ARRANGEMENT")&&
        kotlinx.serialization.json.Json.decodeFromString<FinancialArrangementInput>(change.inputJson).mergePoolId==null
}
internal fun FinancialGraph.poolArrangements(poolId:String)=currentArrangements.filter{change->
    reconciliationPool(plans.first{it.id==change.targetPlanId})?.id==poolId
}
