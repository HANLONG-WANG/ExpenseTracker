package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.csv.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume

class CsvParserTest {
    @Test fun bomQuotedNewlinesAndEncodingFallback() {
        val bytes=byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())+"账簿,日期,类别,金额,备注\r\n支出,2024-1-2,食物,100,\"一行\n两个\"\"引号\"\r\n收入,2024-1-3,給料,200,\u0000\r\n".toByteArray()
        val doc=CsvParser.read(bytes)
        assertEquals("UTF-8",doc.encoding);assertTrue(doc.header);assertEquals(2,doc.data.size)
        assertEquals("一行\n两个\"引号",doc.data[0][4]);assertEquals("",doc.data[1][4])
        val japanese="日付\t金額\tメモ\n2024/1/2\t100\t交通費"
        assertEquals("Shift_JIS",CsvParser.read(japanese.toByteArray(charset("Shift_JIS"))).encoding)
        val chinese="日期;金额;备注\n2024.1.2;100;龘"
        val gbk=chinese.toByteArray(charset("GBK"))
        assertEquals(chinese,CsvParser.decode(gbk,"GBK").first)
        assertEquals("GBK",CsvParser.read(gbk).encoding)
        assertEquals("UTF-16LE",CsvParser.decode(byteArrayOf(0xff.toByte(),0xfe.toByte())+japanese.toByteArray(charset("UTF-16LE"))).second)
        assertEquals(";",CsvParser.read(chinese.toByteArray()).delimiter)
    }
    @Test fun datesAmountsAndDirections() {
        for(date in listOf("2024-01-02 12:34:56","2024/1/2","2024.1.2","2024年1月2日T09:00"))assertEquals("2024-01-02",CsvParser.date(date).toString())
        assertTrue(runCatching{CsvParser.date("2024-02-30")}.isFailure)
        assertEquals(1234L,CsvParser.minor(" ￥ 1,234 ",0))
        assertEquals(123L,CsvParser.minor("-1.23",2))
        assertTrue(runCatching{CsvParser.minor("123.4",0)}.isFailure)
        assertTrue(runCatching{CsvParser.minor("0",0)}.isFailure)
        val doc=CsvParser.read("账簿,日期,类别,金额,备注\n支出,2024-01-02,交通,100,\n收入,2024-01-03,退款,200,\n支出,2024-01-02,交通,100,".toByteArray())
        val settings=CsvParser.guess(doc)
        assertEquals(listOf("EXPENSE","INCOME","EXPENSE"),CsvParser.values(doc,settings).map{it.kind})
        assertEquals(3,doc.data.size) // Identical rows are real separate records.
        assertEquals("SKIP",CsvParser.values(doc,settings.copy(types=settings.types+("收入" to "SKIP")))[1].kind)
        assertEquals("EXPENSE",CsvParser.values(doc,settings.copy(rule="SIGN"),mapOf(0 to CsvEdit(amount="-100")))[0].kind)
        assertTrue(CsvParser.values(doc,settings.copy(rule="EXPENSE")).all{it.kind=="EXPENSE"})
    }
    /** Opt-in local analysis prints aggregate facts only; no personal CSV bytes enter test resources. */
    @Test fun localSampleStatistics() {
        val path=System.getenv("YOHAKU_CSV_SAMPLE")
        Assume.assumeTrue(path!=null)
        val bytes=java.io.File(path!!).readBytes()
        val doc=CsvParser.read(bytes);val settings=CsvParser.guess(doc)
        val values=CsvParser.values(doc,settings)
        val problems=values.filter{runCatching{CsvParser.minor(it.amount,0);CsvParser.date(it.date)}.isFailure}
        val equal=doc.data.groupingBy{it}.eachCount().values.count{it>1}
        val raw=CsvParser.parse(CsvParser.decode(bytes).first,doc.delimiter.single()).drop(1)
        println("CSV_SAMPLE rawEmptyNotes=${raw.count{it[4].isEmpty()}} nulRows=${raw.count{row->row.any{it.contains('\u0000')}}}")
        println("CSV_SAMPLE encoding=${doc.encoding} header=${doc.header} rows=${values.size} directions=${values.groupingBy{it.kind}.eachCount()} range=${values.minOf{it.date}}..${values.maxOf{it.date}} emptyNotes=${values.count{it.note.isEmpty()}} duplicateGroups=$equal nul=${bytes.count{it.toInt()==0}}")
        println("CSV_SAMPLE problems="+problems.map{"row=${it.row+2},kind=${it.kind},amount=${it.amount},dateValid=${runCatching{CsvParser.date(it.date)}.isSuccess}"})
        println("CSV_SAMPLE categories="+values.groupBy{it.kind}.mapValues{(_,rows)->rows.map{it.category}.distinct().size})
    }
}
