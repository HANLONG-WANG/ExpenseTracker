package com.yohaku.expensetracker.data.files

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*

/** Optional extension fields are compatible, but the preview tells the user they have no effect. */
@OptIn(ExperimentalSerializationApi::class)
fun unknownFileFields(text:String,descriptor:SerialDescriptor):List<String> {
    val paths=mutableListOf<String>()
    fun visit(element:JsonElement,type:SerialDescriptor,path:String) {
        when {
            element is JsonObject && type.kind==StructureKind.CLASS -> {
                val fields=(0 until type.elementsCount).associateBy{type.getElementName(it)}
                element.forEach { (key,value)->
                    val index=fields[key]
                    if(index==null)paths+="$path.$key" else visit(value,type.getElementDescriptor(index),"$path.$key")
                }
            }
            element is JsonArray && type.kind==StructureKind.LIST -> element.forEachIndexed{i,value->visit(value,type.getElementDescriptor(0),"$path[$i]")}
        }
    }
    visit(FileProtocols.json.parseToJsonElement(text.removePrefix("\uFEFF")),descriptor,"$")
    return paths
}
