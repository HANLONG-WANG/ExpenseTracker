package com.yohaku.expensetracker.ui.features

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import java.io.File

/** Same whole-file preview and write contract for inbox and original-record entry points. */
@Composable internal fun ReceiptImportFlow(s:BookSnapshot,vm:AppViewModel,onClose:()->Unit,onSaved:()->Unit,targetRecordId:String?=null) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var path by rememberSaveable(s.book.id,targetRecordId){mutableStateOf<String?>(null)}
    var preview by remember(s.book.id,targetRecordId){mutableStateOf<ReceiptBackfillPreview?>(null)}
    var help by rememberSaveable(s.book.id,targetRecordId){mutableStateOf(false)}
    fun checkTarget(result:ReceiptBackfillPreview) {
        require(targetRecordId==null||result.targets.any{it.record.record_id==targetRecordId}) {
            "文件未包含当前原记账，请重新选择 / 現在の元記録が含まれていません。ファイルを選び直してください"
        }
    }
    fun discard(){path?.let{File(it).delete()};path=null;preview=null}
    LaunchedEffect(path) {
        val staged=path?:return@LaunchedEffect
        if(preview!=null)return@LaunchedEffect
        var result:ReceiptBackfillPreview?=null
        vm.execute(onSuccess={preview=result}) {
            require(File(staged).isFile){"导入预览已过期，请重新选择文件 / 取込プレビューの期限が切れました。ファイルを選び直してください"}
            result=vm.repository.previewReceiptItems(s.book.id,File(staged).readText()).also(::checkTarget)
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null) {
        var result:ReceiptBackfillPreview?=null;var staged:String?=null
        vm.execute(onSuccess={discard();path=staged;preview=result}) {
            val raw=readUtf8Document(context,uri)
            result=vm.repository.previewReceiptItems(s.book.id,raw).also(::checkTarget)
            staged=File.createTempFile("receipt-items-",".json",context.cacheDir).apply{writeText(raw)}.path
        }
    } }
    fun choose(){picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
    val result=preview
    when {
        help->ReceiptFormatHelp({help=false})
        result!=null->ReceiptBackfillSheet(s,result,busy,onClose={if(!busy){discard();onClose()}},onReselect={discard()},onSave={stale,replacement->
            vm.execute(onSuccess={discard();onSaved()}){vm.repository.saveReceiptItems(s.book.id,result,acceptStale=stale,acceptReplacement=replacement)}
        })
        else->YSheet(word("导入小票商品明细","レシートの商品明細を取込"),{if(!busy){discard();onClose()}}) {
            ReceiptImportCover()
            MaterialUpload(word("选择商品明细文件","商品明細ファイルを選択"),"receipt-result-pick-file",!busy,leadingIcon={ReceiptGlyph(Modifier.size(18.dp),tint=LocalYohaku.current.accent)}){choose()}
            YLink(word("文件格式与助手说明 ↗","ファイル形式・助手への説明 ↗"),{help=true},Modifier.testTag("receipt-import-format-help"))
            YText(word("只补充商品信息；不新增消费、不拆分类、不修改原金额。","商品情報だけ補足。支出追加・分類分割・元金額変更はしません。"),10,color=LocalYohaku.current.muted,lineHeightMultiplier=2f)
        }
    }
}

@Composable private fun ReceiptImportCover() {
    val t=LocalYohaku.current
    val objects=t.id=="objects"
    Column(Modifier.fillMaxWidth().padding(top=14.dp,bottom=22.dp).testTag("receipt-import-cover"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        ReceiptGlyph(if(objects)Modifier.size(58.dp,70.dp).rotate(-5f).background(Color(0xffece0ca),RoundedCornerShape(11.dp)).padding(9.dp)else Modifier.size(45.dp,60.dp),tint=if(objects)Color(0xffa77d55)else t.ink)
        YText(word("买过的东西，回来了。","買ったものが、戻ってきた。"),23,textAlign=TextAlign.Center)
        YText(word("助手整理后的 JSON 文件，关联到原记账。","助手の整理結果JSONを、元の記録へ。"),10,color=t.muted,textAlign=TextAlign.Center,lineHeightMultiplier=2f)
    }
}
