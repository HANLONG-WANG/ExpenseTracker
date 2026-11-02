@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yohaku.expensetracker.data.command

import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** Generates the supported JSON Schema subset from the exact strict Kotlin input serializers. */
internal fun descriptorSchema(d:SerialDescriptor,nullable:Boolean=d.isNullable):JsonObject {
    if(nullable)return buildJsonObject{put("anyOf",JsonArray(listOf(descriptorSchema(d,false),buildJsonObject{put("type","null")})))}
    return when(d.kind) {
        PrimitiveKind.STRING->buildJsonObject{put("type","string")}
        PrimitiveKind.BOOLEAN->buildJsonObject{put("type","boolean")}
        PrimitiveKind.BYTE,PrimitiveKind.SHORT,PrimitiveKind.INT,PrimitiveKind.LONG->buildJsonObject{put("type","integer")}
        PrimitiveKind.FLOAT,PrimitiveKind.DOUBLE->buildJsonObject{put("type","number")}
        SerialKind.ENUM->buildJsonObject{put("type","string");put("enum",JsonArray((0 until d.elementsCount).map{JsonPrimitive(d.getElementName(it))}))}
        StructureKind.LIST->buildJsonObject{put("type","array");put("items",descriptorSchema(d.getElementDescriptor(0)))}
        StructureKind.CLASS,StructureKind.OBJECT->buildJsonObject {
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject{for(i in 0 until d.elementsCount)put(d.getElementName(i),descriptorSchema(d.getElementDescriptor(i)))})
            put("required",JsonArray((0 until d.elementsCount).filterNot{d.isElementOptional(it)}.map{JsonPrimitive(d.getElementName(it))}))
        }
        else->buildJsonObject{}
    }
}

internal fun objectSchema(properties:Map<String,JsonElement>,required:List<String>)=buildJsonObject {
    put("type","object");put("additionalProperties",false);put("properties",JsonObject(properties));put("required",encoded(required))
}
internal fun patchSchema(schema:JsonObject,fields:Map<String,JsonElement>)=JsonObject(schema+("properties" to JsonObject(schema.getValue("properties").jsonObject+fields)))
internal fun withoutFields(schema:JsonObject,names:Set<String>)=JsonObject(schema+mapOf(
    "properties" to JsonObject(schema.getValue("properties").jsonObject.filterKeys{it !in names}),
    "required" to JsonArray(schema.getValue("required").jsonArray.filter{it.jsonPrimitive.content !in names})))
internal fun referenceSchema(type:String)=buildJsonObject {
    put("x-reference-type",type)
    put("anyOf",JsonArray(listOf(buildJsonObject{put("type","string");put("minLength",1)},buildJsonObject{put("\$ref","#/\$defs/stepReference")})))
}
internal fun nullableSchema(schema:JsonObject)=buildJsonObject{put("anyOf",JsonArray(listOf(schema,buildJsonObject{put("type","null")})))}

/** Never uses a schema supplied by a cloud result. Also validates references at their typed slots. */
internal fun validateSchema(value:JsonElement,schema:JsonObject,path:String,prior:Map<String,Map<String,String>>) {
    schema["\$ref"]?.jsonPrimitive?.content?.let{ref->
        require(ref=="#/\$defs/stepReference")
        validateSchema(value,descriptorSchema(CommandRef.serializer().descriptor),path,prior);return
    }
    schema["anyOf"]?.jsonArray?.let { choices->
        val type=schema["x-reference-type"]?.jsonPrimitive?.content
        if(type!=null&&value is JsonObject) {
            val ref=commandJson.decodeFromJsonElement<CommandRef>(value)
            require(prior[ref.ref]?.get(ref.output)==type){"引用类型错误或引用了后续步骤 / 参照の型が異なるか後続のステップを参照しています: $path"}
        }
        require(choices.any{runCatching{validateSchema(value,it.jsonObject,path,prior)}.isSuccess}){"输入格式错误 / 入力形式が無効です: $path"}
        return
    }
    when(schema["type"]?.jsonPrimitive?.content) {
        "null"->require(value==JsonNull)
        "object"->{
            require(value is JsonObject){path}
            val properties=schema["properties"]?.jsonObject.orEmpty()
            require(value.keys.all{it in properties}){"未知输入字段 / 未知の入力項目: $path"}
            require(schema["required"]?.jsonArray.orEmpty().all{it.jsonPrimitive.content in value}){"缺少输入字段 / 入力項目が不足しています: $path"}
            value.forEach{(k,v)->validateSchema(v,properties.getValue(k).jsonObject,"$path.$k",prior)}
        }
        "array"->{require(value is JsonArray);value.forEachIndexed{i,v->validateSchema(v,schema.getValue("items").jsonObject,"$path[$i]",prior)}}
        "string"->{require(value is JsonPrimitive&&value.isString);require(schema["minLength"]==null||value.content.length>=schema.getValue("minLength").jsonPrimitive.int)}
        "boolean"->require(value is JsonPrimitive&&!value.isString&&value.booleanOrNull!=null)
        "integer"->require(value is JsonPrimitive&&!value.isString&&value.longOrNull!=null)
        "number"->require(value is JsonPrimitive&&!value.isString&&value.doubleOrNull!=null)
    }
    schema["enum"]?.let{require(value in it.jsonArray)}
}
