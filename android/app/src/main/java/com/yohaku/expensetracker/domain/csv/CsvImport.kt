package com.yohaku.expensetracker.domain.csv

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Serializable data class CsvSettings(
    val columns:Map<String,Int> = emptyMap(),val header:Boolean=true,val delimiter:String=",",val encoding:String="AUTO",
    val dateFormat:String="AUTO",val rule:String="TYPE",val types:Map<String,String> = emptyMap(),
    val accountId:String="",val accounts:Map<String,String> = emptyMap(),
    val categories:Map<String,String> = emptyMap(),val defaults:Map<String,String> = emptyMap(),val noteAsMerchant:Boolean=false
)
data class CsvDocument(val rows:List<List<String>>,val encoding:String,val delimiter:String,val header:Boolean) {
    val labels get()=if(header)rows.firstOrNull().orEmpty() else List(rows.maxOfOrNull{it.size}?:0){"#${it+1}"}
    val data get()=if(header)rows.drop(1)else rows
    val signature get()=CsvParser.hash((if(header)labels.joinToString("\u001f"){it.trim().lowercase()}else "NO_HEADER:${labels.size}").toByteArray())
}
data class CsvEdit(val date:String?=null,val amount:String?=null,val skip:Boolean?=null)
data class CsvValue(val row:Int,val date:String,val amount:String,val kind:String?,val category:String,val note:String,val merchant:String,val account:String)

object CsvParser {
    val fields=listOf("date","amount","type","category","note","merchant","account")
    private val aliases=mapOf(
        "date" to setOf("日期","日付","date"),"amount" to setOf("金额","金額","amount"),
        "type" to setOf("类型","收支","账簿","帳簿","種類","type","収支"),
        "category" to setOf("类别","分类","カテゴリ","カテゴリー","category"),
        "note" to setOf("备注","メモ","memo","note","備考"),
        "merchant" to setOf("商户","店","payee","merchant","店舗"),"account" to setOf("账户","口座","account"))
    fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun clean(text:String)=text.filter{!it.isISOControl()||it=='\n'||it=='\t'}.trim()
    fun decode(bytes:ByteArray,encoding:String="AUTO"):Pair<String,String> {
        fun prefix(vararg values:Int)=bytes.size>=values.size&&values.indices.all{bytes[it].toInt() and 255==values[it]}
        val bom=when {
            prefix(0,0,0xfe,0xff)->"UTF-32BE" to 4
            prefix(0xff,0xfe,0,0)->"UTF-32LE" to 4
            prefix(0xef,0xbb,0xbf)->"UTF-8" to 3
            prefix(0xfe,0xff)->"UTF-16BE" to 2
            prefix(0xff,0xfe)->"UTF-16LE" to 2
            else->null
        }
        val encodings=if(bom!=null)listOf(bom.first)else if(encoding!="AUTO")listOf(encoding)else listOf("UTF-8","Shift_JIS","GBK")
        for(name in encodings)try {
            val decoder=Charset.forName(name).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes,bom?.second?:0,bytes.size-(bom?.second?:0))).toString() to name
        }catch(_:java.nio.charset.CharacterCodingException) { }
        error("无法解码，请手动选择编码 / 文字コードを選び直してください")
    }
    /** RFC 4180 records; embedded CR/LF, escaped quotes and final empty cells are retained. */
    fun parse(text:String,delimiter:Char):List<List<String>> {
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val cell=StringBuilder()
        var quoted=false;var closed=false;var i=0
        fun field(){row+=cell.toString().trim();cell.setLength(0);closed=false}
        fun record(){field();if(row.any{it.isNotEmpty()})rows+=row.toList();row.clear()}
        while(i<text.length) {
            val c=text[i]
            if(quoted) {
                if(c=='"') {if(i+1<text.length&&text[i+1]=='"'){cell.append('"');i++}else{quoted=false;closed=true}}
                else cell.append(c)
            }else when {
                c==delimiter->field()
                c=='\r'||c=='\n'->{record();if(c=='\r'&&i+1<text.length&&text[i+1]=='\n')i++}
                c=='"'->{require(cell.isEmpty()&&!closed){"CSV 引号位置错误 / CSV の引用符が不正です"};quoted=true}
                closed&&c.isWhitespace()->Unit
                else->{require(!closed){"CSV 引号后有多余字符 / 引用符の後に余分な文字があります"};cell.append(c)}
            }
            i++
        }
        require(!quoted){"CSV 引号未闭合 / CSV の引用符が閉じていません"}
        if(cell.isNotEmpty()||row.isNotEmpty()||closed)record()
        return rows
    }
    fun read(bytes:ByteArray,encoding:String="AUTO",delimiter:String?=null,header:Boolean?=null):CsvDocument {
        val (text,used)=decode(bytes,encoding)
        val candidates=(delimiter?.let{listOf(it.single())}?:listOf(',','\t',';')).mapNotNull{d->runCatching{d to parse(text,d)}.getOrNull()}
        val chosen=candidates.maxByOrNull{(_,rows)->val widths=rows.take(50).map{it.size};val common=widths.groupingBy{it}.eachCount().maxByOrNull{it.value};if(common==null||common.key<=1)0 else common.value*100+common.key}
            ?:error("CSV 格式错误，请检查引号和分隔符 / CSV の引用符・区切り文字を確認してください")
        require(chosen.second.isNotEmpty()){"文件没有数据 / ファイルにデータがありません"}
        val first=chosen.second.first()
        val isHeader=first.count{v->aliases.values.any{v.lowercase() in it}}>=2
        return CsvDocument(chosen.second.map{row->row.map(::clean)},used,chosen.first.toString(),header?:isHeader)
    }
    fun guess(document:CsvDocument):CsvSettings {
        val columns=fields.mapNotNull{field->document.labels.indexOfFirst{it.lowercase() in aliases.getValue(field)}.takeIf{it>=0}?.let{field to it}}.toMap().toMutableMap()
        if("type" !in columns) {
            val index=document.labels.indices.firstOrNull{i->val values=document.data.map{it.getOrElse(i){""}}.filter{it.isNotBlank()}.distinct();values.isNotEmpty()&&values.all{direction(it)!=null}}
            if(index!=null)columns["type"]=index
        }
        val types=columns["type"]?.let{i->document.data.map{it.getOrElse(i){""}}.distinct().associateWith{direction(it)?:"UNSET"}}.orEmpty()
        return CsvSettings(columns=columns,header=document.header,delimiter=document.delimiter,rule=if("type" in columns)"TYPE"else"EXPENSE",types=types)
    }
    fun direction(value:String):String?=when(value.trim().lowercase()) {
        "支出","支払","支払い","出金","expense","expenses","out","支払い額"->"EXPENSE"
        "收入","収入","入金","income","in"->"INCOME"
        else->null
    }
    fun date(text:String,format:String="AUTO"):LocalDate {
        val raw=clean(text)
        val day=Regex("^([0-9]{4}(?:[-/.][0-9]{1,2}[-/.][0-9]{1,2}|年[0-9]{1,2}月[0-9]{1,2}日))(?:[T\\s].*)?$",RegexOption.DOT_MATCHES_ALL).matchEntire(raw)?.groupValues?.get(1)
            ?:error("日期无法解析 / 日付を解析できません")
        if(format!="AUTO")return LocalDate.parse(day,DateTimeFormatter.ofPattern(format.replace("yyyy","uuuu")).withResolverStyle(ResolverStyle.STRICT))
        val parts=day.split(Regex("[-/.年月日]")).filter{it.isNotEmpty()}.map{it.toInt()}
        return LocalDate.of(parts[0],parts[1],parts[2])
    }
    fun decimal(text:String):BigDecimal=clean(text).replace(Regex("[¥￥,\\s]"),"").toBigDecimal()
    fun minor(text:String,scale:Int):Long {
        val value=decimal(text).abs()
        require(value.signum()!=0){"金额为 0，请修正或跳过 / 金額が 0 です。修正またはスキップしてください"}
        require(value.stripTrailingZeros().scale()<=scale){"金额超过币种精度 / 通貨の小数桁数を超えています"}
        return value.movePointRight(scale).setScale(0,RoundingMode.UNNECESSARY).longValueExact()
    }
    fun values(document:CsvDocument,settings:CsvSettings,edits:Map<Int,CsvEdit> = emptyMap()):List<CsvValue> = document.data.mapIndexed{index,row->
        fun value(field:String)=settings.columns[field]?.let{row.getOrElse(it){""}}.orEmpty()
        val edit=edits[index];val amount=edit?.amount?:value("amount")
        val kind=when(settings.rule){"EXPENSE"->"EXPENSE";"SIGN"->runCatching{if(decimal(amount).signum()<0)"EXPENSE"else"INCOME"}.getOrNull();else->settings.types[value("type")]?.takeUnless{it=="UNSET"}}
        val note=value("note")
        CsvValue(index,edit?.date?:value("date"),amount,kind,value("category"),note,if(settings.noteAsMerchant)note else value("merchant"),value("account"))
    }
    fun categoryKey(kind:String,value:String)="$kind\u001f$value"
    fun duplicateKey(date:String,kind:String,minor:Long,note:String,merchant:String)=listOf(date,kind,minor.toString(),note,merchant)
}
