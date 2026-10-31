package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.db.MerchantEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.EntryDraft
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun EntryMerchantInput(s:BookSnapshot,d:EntryDraft,onChange:(EntryDraft)->Unit) {
    var open by rememberSaveable(d.mediaSession){mutableStateOf(false)}
    var query by rememberSaveable(d.mediaSession){mutableStateOf("")}
    fun choose(m:MerchantEntity){onChange(d.copy(merchant=m.name,merchantId=m.id,merchantManuallyEdited=true,merchantLocated=false));open=false}
    val suggestions=if(d.merchantId==null&&d.merchant.isNotBlank())s.merchants.matchingMerchants(d.merchant).take(5)else emptyList()
    // Suggestions sit under the field; keep them above the keyboard as they appear and as it slides in.
    val requester=remember{BringIntoViewRequester()}
    val density=LocalDensity.current;val keyboard=WindowInsets.ime.getBottom(density)
    var size by remember{mutableStateOf(IntSize.Zero)}
    LaunchedEffect(suggestions.map{it.id},keyboard){if(suggestions.isNotEmpty()){withFrameNanos{};requester.bringIntoView(Rect(0f,0f,size.width.toFloat(),size.height+with(density){12.dp.toPx()}))}}
    Column(Modifier.bringIntoViewRequester(requester).onSizeChanged{size=it},verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            YField(word("商户 / 买了什么","お店 / 買ったもの"),d.merchant,{onChange(s.merchantInput(d,it))},Modifier.weight(1f).testTag("entry-merchant-input"))
            YLink(word("商户一览","一覧"),{query="";open=true},Modifier.testTag("entry-merchant-list"))
        }
        if(d.merchantId!=null) {
            LocatedMerchantChip(d.merchant,word("已选商户 · 点击清除","選択済み・タップして解除"),{onChange(d.copy(merchant="",merchantId=null,merchantManuallyEdited=true,merchantLocated=false))},Modifier.fillMaxWidth().testTag("entry-merchant-selected"))
        }else suggestions.forEach{m->
            LocatedMerchantChip(m.name,word("使用已保存商户","保存済みのお店を使用"),{choose(m)},Modifier.fillMaxWidth().testTag("merchant-suggestion-${m.id}"))
        }
    }
    if(open)YSheet(word("商户一览","お店の一覧"),{open=false}) {
        YSearch(word("搜索商户","お店を検索"),query,{query=it})
        val matches=s.merchants.matchingMerchants(query)
        if(matches.isEmpty())YText(word("没有匹配的商户","一致するお店はありません"),12,color=LocalYohaku.current.muted)
        matches.forEach{m->LocatedMerchantChip(m.name,if(d.merchantId==m.id)word("已选中","選択済み")else word("选择商户","お店を選択"),{choose(m)},Modifier.fillMaxWidth().testTag("merchant-choice-${m.id}"))}
    }
}
