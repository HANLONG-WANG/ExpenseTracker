@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yohaku.expensetracker.domain.model

import kotlinx.serialization.SerialInfo

/** Wire constraints read by the command schema generator; items applies to each list element. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class SchemaFormat(val value:String,val items:Boolean=false)

/** Nonempty condition emits advisory x-local-constraint; the existing business path enforces it. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class SchemaRange(val minimum:Long=Long.MIN_VALUE,val maximum:Long=Long.MAX_VALUE,val items:Boolean=false,val property:String="",val condition:String="")
