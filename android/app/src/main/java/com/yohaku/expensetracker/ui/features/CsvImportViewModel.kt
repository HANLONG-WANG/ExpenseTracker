package com.yohaku.expensetracker.ui.features

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

data class CsvUiState(val fileName:String="",val sha256:String="",val document:CsvDocument?=null,val settings:CsvSettings=CsvSettings(),
    val edits:Map<Int,CsvEdit> = emptyMap(),val preview:CsvPreview?=null,val step:Int=0,val busy:Boolean=false,val progress:Pair<Int,Int>?=null,
    val error:String?=null,val imported:Int?=null,val repeated:Boolean=false,val remembered:Boolean=false,val batchEvents:List<EventEntity>?=null)
class CsvImportViewModel(private val repo:LedgerRepository,private val bookId:String):ViewModel() {
    val state=MutableStateFlow(CsvUiState())
    private var bytes:ByteArray?=null
    private fun work(block:suspend ()->Unit) {
        if(state.value.busy)return
        state.value=state.value.copy(busy=true,error=null)
        viewModelScope.launch {
            try{withContext(Dispatchers.IO){block()}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){state.value=state.value.copy(error=e.message?:e.javaClass.simpleName)}
            finally{state.value=state.value.copy(busy=false,progress=null)}
        }
    }
    fun open(context:Context,uri:Uri)=work {
        val data=context.contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("无法读取文件 / ファイルを読み込めません")
        var name=uri.lastPathSegment.orEmpty()
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)}
        bytes=data
        state.value=CsvUiState(fileName=name,sha256=CsvParser.hash(data),busy=true)
        decodeNow("AUTO",null,null)
    }
    fun decode(encoding:String=state.value.settings.encoding,delimiter:String?=state.value.document?.delimiter,header:Boolean?=state.value.document?.header)=work{decodeNow(encoding,delimiter,header)}
    private suspend fun decodeNow(encoding:String,delimiter:String?,header:Boolean?) {
        val document=CsvParser.read(requireNotNull(bytes),encoding,delimiter,header)
        val saved=repo.db.csvImportDao().profile(bookId,document.signature)
        var settings=saved?.let{repo.json.decodeFromString<CsvSettings>(it.settingsJson)}?:CsvParser.guess(document)
        settings=settings.copy(header=document.header,encoding=encoding,delimiter=document.delimiter)
        val s=repo.snapshot(requireNotNull(repo.dao.book(bookId))).first()
        if(settings.accountId.isEmpty())settings=settings.copy(accountId=s.accounts.firstOrNull{csvAccountAllowed(it,s.book)&&!com.yohaku.expensetracker.domain.model.AccountType.valueOf(it.type).debt}?.id.orEmpty())
        val repeat=repo.db.csvImportDao().batches(bookId).first().any{it.sha256==state.value.sha256}
        state.value=state.value.copy(document=document,settings=normalized(document,settings,s),edits=emptyMap(),preview=null,step=0,repeated=repeat,remembered=saved!=null,imported=null)
    }
    private fun normalized(document:CsvDocument,settings:CsvSettings,s:BookSnapshot):CsvSettings {
        val types=settings.columns["type"]?.let{i->document.data.map{it.getOrElse(i){""}}.distinct().associateWith{settings.types[it]?:CsvParser.direction(it)?:"UNSET"}}.orEmpty()
        val next=settings.copy(types=types)
        val categories=next.categories.toMutableMap()
        CsvParser.values(document,next).filter{it.kind in listOf("EXPENSE","INCOME")&&it.category.isNotBlank()}.forEach {
            categories.putIfAbsent(CsvParser.categoryKey(it.kind!!,it.category),csvCategoryChoice(s,it.kind,it.category))
        }
        return next.copy(categories=categories)
    }
    fun configure(settings:CsvSettings){state.value=state.value.copy(settings=settings,preview=null,error=null)}
    fun step(step:Int)=work {
        val old=state.value
        val document=requireNotNull(old.document)
        if(step>=2) {
            require(old.settings.columns["date"]!=null&&old.settings.columns["amount"]!=null){"请选择日期和金额列 / 日付・金額の列を選択してください"}
            val s=repo.snapshot(requireNotNull(repo.dao.book(bookId))).first()
            state.value=old.copy(settings=normalized(document,old.settings,s),step=step,busy=true)
        }else state.value=old.copy(step=step)
        if(step==3)previewNow()
    }
    private suspend fun previewNow() {
        val old=state.value
        val preview=repo.csvPreview(bookId,requireNotNull(old.document),old.settings,old.edits)
        repo.db.csvImportDao().putProfile(CsvProfileEntity(bookId,old.document.signature,repo.json.encodeToString(old.settings)))
        state.value=state.value.copy(preview=preview)
    }
    fun edit(row:Int,edit:CsvEdit)=work {
        state.value=state.value.copy(edits=state.value.edits+(row to edit))
        previewNow()
    }
    fun importBatch()=work {
        val old=state.value
        val batch=repo.importCsv(bookId,old.fileName,old.sha256,requireNotNull(old.document),old.settings,old.edits,requireNotNull(old.preview)){done,total->
            state.value=state.value.copy(progress=done to total)
        }
        state.value=state.value.copy(imported=batch.importedCount,preview=null,document=null,step=0,repeated=true)
        bytes=null
    }
    fun undo(batch:CsvBatchEntity)=work{repo.undoCsvBatch(bookId,batch.id);state.value=state.value.copy(batchEvents=repo.db.csvImportDao().events(bookId,batch.id))}
    fun events(batch:CsvBatchEntity)=work{state.value=state.value.copy(batchEvents=repo.db.csvImportDao().events(bookId,batch.id))}
    fun closeEvents(){state.value=state.value.copy(batchEvents=null)}
}
