package com.yohaku.expensetracker.domain.model

import kotlin.math.roundToInt

data class PhotoSize(val width:Int,val height:Int)
fun scaledPhotoSize(width:Int,height:Int,limit:Int,shortEdge:Boolean=false):PhotoSize {
    val edge=if(shortEdge)minOf(width,height)else maxOf(width,height)
    val scale=minOf(1.0,limit.toDouble()/edge)
    return PhotoSize((width*scale).roundToInt().coerceAtLeast(1),(height*scale).roundToInt().coerceAtLeast(1))
}
fun optimizedPhotoSize(width:Int,height:Int,receipt:Boolean)=scaledPhotoSize(width,height,if(receipt)2000 else 2560,receipt)
fun jpegFileName(name:String)=name.ifBlank{"file"}.let{val dot=it.lastIndexOf('.');(if(dot>0)it.substring(0,dot)else it)+".jpg"}
