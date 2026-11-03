@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.yohaku.expensetracker.data.command

import com.yohaku.expensetracker.domain.model.SchemaFormat
import com.yohaku.expensetracker.domain.model.SchemaRange
import java.time.*
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
            put("properties",buildJsonObject{for(i in 0 until d.elementsCount)put(d.getElementName(i),annotatedSchema(descriptorSchema(d.getElementDescriptor(i)),d.getElementAnnotations(i)))})
            put("required",JsonArray((0 until d.elementsCount).filterNot{d.isElementOptional(it)}.map{JsonPrimitive(d.getElementName(it))}))
        }
        else->buildJsonObject{}
    }
}

internal fun formatSchema(format:String)=buildJsonObject {
    put("type","string")
    when(format) {
        "date"->{put("format","date");put("pattern","^[0-9]{4}-[0-9]{2}-[0-9]{2}$")}
        "year-month"->{put("x-format","year-month");put("pattern","^[0-9]{4}-(0[1-9]|1[0-2])$");put("description","YYYY-MM")}
        else->{put("x-format",format);put("description",when(format){"local-time"->"ISO LocalTime (HH:mm, optional seconds/fraction)";"instant"->"ISO Instant (e.g. 2026-11-02T00:00:00Z)";else->"Java ZoneId (e.g. Asia/Tokyo, UTC, +09:00)"})}
    }
}
internal fun dateSchema()=formatSchema("date")
internal fun monthSchema()=formatSchema("year-month")
internal fun rangeSchema(minimum:Long=Long.MIN_VALUE,maximum:Long=Long.MAX_VALUE)=buildJsonObject {
    put("type","integer");if(minimum!=Long.MIN_VALUE)put("minimum",minimum);if(maximum!=Long.MAX_VALUE)put("maximum",maximum)
}
/** State-dependent constraints are guidance, not unconditional rejection of ignored fields. */
internal fun localConstraintSchema(schema:JsonObject,condition:String,constraint:JsonObject)=JsonObject(schema+buildJsonObject {
    put("x-local-constraint",buildJsonObject{put("when",condition);put("schema",constraint)})
})
private fun constrainSchema(schema:JsonObject,constraint:JsonObject,items:Boolean=false,property:String=""):JsonObject {
    schema["anyOf"]?.jsonArray?.let{return JsonObject(schema+("anyOf" to JsonArray(it.map{branch->
        if(branch.jsonObject["type"]==JsonPrimitive("null"))branch else constrainSchema(branch.jsonObject,constraint,items,property)
    })))}
    if(items)return JsonObject(schema+("items" to constrainSchema(schema.getValue("items").jsonObject,constraint,property=property)))
    if(property.isNotEmpty())return patchSchema(schema,mapOf(property to constrainSchema(schema.getValue("properties").jsonObject.getValue(property).jsonObject,constraint)))
    return JsonObject(schema+constraint)
}
private fun annotatedSchema(schema:JsonObject,annotations:List<Annotation>):JsonObject=annotations.fold(schema){result,annotation->when(annotation) {
    is SchemaFormat->constrainSchema(result,formatSchema(annotation.value),annotation.items)
    is SchemaRange->constrainSchema(result,rangeSchema(annotation.minimum,annotation.maximum),annotation.items,annotation.property).let{
        if(annotation.condition.isEmpty())it else localConstraintSchema(result,annotation.condition,it)
    }
    else->result
}}

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
internal fun enumSchema(vararg values:String)=buildJsonObject{put("type","string");put("enum",encoded(values.toList()))}
internal fun nullableSchema(schema:JsonObject)=buildJsonObject{put("anyOf",JsonArray(listOf(schema,buildJsonObject{put("type","null")})))}

/** Never uses a schema supplied by a cloud result. Also validates references at their typed slots. */
internal fun validateSchema(value:JsonElement,schema:JsonObject,path:String,prior:Map<String,Map<String,String>>,defs:JsonObject=buildJsonObject{}) {
    schema["const"]?.let{require(value==it){"固定值不匹配 / 固定値が一致しません: $path"}}
    schema["oneOf"]?.jsonArray?.let{choices->
        require(choices.count{runCatching{validateSchema(value,it.jsonObject,path,prior,defs)}.isSuccess}==1){"必须且只能选择一种输入形式 / 入力形式は必ず1つだけ選んでください: $path"}
    }
    schema["\$ref"]?.jsonPrimitive?.content?.let{ref->
        val target=if(ref=="#/\$defs/stepReference")descriptorSchema(CommandRef.serializer().descriptor)else {
            require(ref.startsWith("#/\$defs/"));defs.getValue(ref.removePrefix("#/\$defs/")).jsonObject
        }
        validateSchema(value,target,path,prior,defs);return
    }
    schema["anyOf"]?.jsonArray?.let { choices->
        val type=schema["x-reference-type"]?.jsonPrimitive?.content
        if(type!=null&&value is JsonObject) {
            val ref=commandJson.decodeFromJsonElement<CommandRef>(value)
            require(prior[ref.ref]?.get(ref.output)==type){"引用类型错误或引用了后续步骤 / 参照の型が異なるか後続のステップを参照しています: $path"}
        }
        if(choices.size==2&&choices.any{it.jsonObject["type"]==JsonPrimitive("null")}) {
            if(value==JsonNull)return
            validateSchema(value,choices.first{it.jsonObject["type"]!=JsonPrimitive("null")}.jsonObject,path,prior,defs);return
        }
        require(choices.any{runCatching{validateSchema(value,it.jsonObject,path,prior,defs)}.isSuccess}){"输入格式错误 / 入力形式が無効です: $path"}
        return
    }
    when(schema["type"]?.jsonPrimitive?.content) {
        "null"->require(value==JsonNull)
        "object"->{
            require(value is JsonObject){path}
            val properties=schema["properties"]?.jsonObject.orEmpty()
            val additional=schema["additionalProperties"] as? JsonObject
            require(value.keys.all{it in properties||additional!=null}){"未知输入字段 / 未知の入力項目: $path"}
            require(schema["required"]?.jsonArray.orEmpty().all{it.jsonPrimitive.content in value}){"缺少输入字段 / 入力項目が不足しています: $path"}
            value.forEach{(k,v)->validateSchema(v,(properties[k]?.jsonObject?:requireNotNull(additional)),"$path.$k",prior,defs)}
        }
        "array"->{
            require(value is JsonArray)
            require(schema["minItems"]?.jsonPrimitive?.int?.let{value.size>=it}!=false&&schema["maxItems"]?.jsonPrimitive?.int?.let{value.size<=it}!=false){"目标数量超出允许范围 / 対象件数が許可範囲外です: $path"}
            require(schema["uniqueItems"]!=JsonPrimitive(true)||value.distinct().size==value.size){"目标不能重复 / 対象は重複できません: $path"}
            value.forEachIndexed{i,v->validateSchema(v,schema.getValue("items").jsonObject,"$path[$i]",prior,defs)}
        }
        "string"->{
            require(value is JsonPrimitive&&value.isString){path}
            require(schema["minLength"]==null||value.content.length>=schema.getValue("minLength").jsonPrimitive.int){path}
            require(schema["maxLength"]==null||value.content.length<=schema.getValue("maxLength").jsonPrimitive.int){path}
            val format=(schema["format"]?:schema["x-format"])?.jsonPrimitive?.content
            val message=when(format) {
                "date"->"日期格式应为 YYYY-MM-DD / 日付は YYYY-MM-DD 形式で入力してください: $path"
                "year-month"->"月份格式应为 YYYY-MM / 月は YYYY-MM 形式で入力してください: $path"
                else->"时间或字符串格式错误 / 時刻または文字列の形式が無効です: $path"
            }
            schema["pattern"]?.let{require(Regex(it.jsonPrimitive.content).matches(value.content)){message}}
            require(runCatching{when(format){
                "date"->LocalDate.parse(value.content)
                "year-month"->YearMonth.parse(value.content)
                "local-time"->LocalTime.parse(value.content)
                "instant"->Instant.parse(value.content)
                "zone-id"->ZoneId.of(value.content)
                else->Unit
            }}.isSuccess){message}
        }
        "boolean"->require(value is JsonPrimitive&&!value.isString&&value.booleanOrNull!=null)
        "integer"->{
            require(value is JsonPrimitive&&!value.isString&&value.longOrNull!=null){path}
            val number=value.long
            require(schema["minimum"]?.jsonPrimitive?.long?.let{number>=it}!=false&&schema["maximum"]?.jsonPrimitive?.long?.let{number<=it}!=false){"整数超出允许范围 / 整数が許可範囲外です: $path"}
        }
        "number"->require(value is JsonPrimitive&&!value.isString&&value.doubleOrNull!=null)
    }
    schema["enum"]?.let{require(value in it.jsonArray){"不支持的选项，请查看能力清单中的允许值及禁止操作替代 / 未対応の選択肢です。対応機能一覧の許可値と禁止操作の代替を確認してください: $path"}}
}
