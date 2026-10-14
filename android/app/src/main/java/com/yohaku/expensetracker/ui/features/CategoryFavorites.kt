package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.db.CategoryEntity
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun CategoryFavorites(s:BookSnapshot,onFavorite:(CategoryEntity)->Unit){
    val t=LocalYohaku.current
    var expanded by rememberSaveable(s.book.id){mutableStateOf(false)}
    val stateLabel=if(expanded)word("已展开","展開中") else word("已收起","折りたたみ")
    ThemeAuditPanel(word("常用分类快捷入口","よく使うカテゴリのショートカット")){
        Column(Modifier.fillMaxWidth().testTag("category-favorites-details").drawBehind{
            drawLine(t.line,Offset(0f,size.height-.5.dp.toPx()),Offset(size.width,size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(t.id=="journal")PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
        }.padding(top=15.dp,bottom=16.dp)){
            Row(Modifier.fillMaxWidth().testTag("category-favorites-toggle").semantics{stateDescription=stateLabel}.clickable(role=Role.Button){expanded=!expanded},verticalAlignment=Alignment.CenterVertically){
                val size=when(t.id){"journal"->14;"objects"->12;else->10}
                YText(word("设置常用 / 一级或二级均可","よく使うカテゴリを設定・親も子も可"),size,Modifier.weight(1f),color=t.accent,lineHeightMultiplier=when(t.id){"journal"->20f/size;"objects"->17f/size;else->14f/size},cssLineBox=true)
                YText(if(expanded)"−" else "+",17,color=t.accent,lineHeightMultiplier=25f/17,cssLineBox=true)
            }
            if(expanded){
                for(kind in listOf("EXPENSE","INCOME")){
                    val roots=s.categories.filter{it.kind==kind&&it.parentId==null&&it.active}
                    for(root in roots)for(c in listOf(root)+s.categories.filter{it.parentId==root.id&&it.active}){
                        val size=if(t.id=="journal")15 else 12
                        YText((if(c.favorite)"★ " else "☆ ")+(if(kind=="INCOME")word("收入 / ","収入 / ") else "")+s.path(c.id),size,
                            Modifier.fillMaxWidth().testTag("category-favorite-${c.id}").semantics{selected=c.favorite}.clickable(role=Role.Button){onFavorite(c)}.drawBehind{
                                drawLine(t.line,Offset(0f,this.size.height-.5.dp.toPx()),Offset(this.size.width,this.size.height-.5.dp.toPx()),1.dp.toPx(),pathEffect=if(t.id=="magazine")null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))
                            }.padding(top=if(t.id=="objects")15.dp else 14.dp,bottom=if(t.id=="objects")16.dp else 15.dp),color=t.accent,lineHeightMultiplier=1.8f,cssLineBox=true)
                    }
                }
            }
        }
    }
}
