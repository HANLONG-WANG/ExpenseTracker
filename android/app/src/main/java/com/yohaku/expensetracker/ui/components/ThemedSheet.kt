package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.yohaku.expensetracker.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable fun YSheet(title:String,onClose:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val journal=t.id=="journal";val objects=t.id=="objects"
    val narrow=LocalConfiguration.current.screenWidthDp<=360
    val maximum=with(LocalDensity.current){LocalWindowInfo.current.containerSize.height.toDp()}*.9f
    var sheetTop by remember{mutableFloatStateOf(Float.POSITIVE_INFINITY)}
    val shape=when(t.id){"journal"->RoundedCornerShape(3.dp,6.dp,0.dp,0.dp);"objects"->RoundedCornerShape(26.dp,26.dp,0.dp,0.dp);else->RoundedCornerShape(0.dp)}
    val background=if(journal)Color(0xfffaf4e4) else if(objects)Color(0xfffff9ed) else t.bg
    val left=if(journal)if(narrow)28 else 31 else if(objects)if(narrow)17 else 22 else if(narrow)18 else 25
    val right=if(journal)if(narrow)16 else 19 else left
    val border=if(objects||journal)1 else 2
    val closeLabel=word("关闭","閉じる")
    val scroll=rememberScrollState()
    var headerHeight by remember{mutableIntStateOf(0)}
    val focusScroll=remember{object:BringIntoViewSpec{
        override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float):Float =
            super.calculateScrollDistance(offset-headerHeight,size,(containerSize-headerHeight).coerceAtLeast(0f))
    }}
    // HTML scrolls the grip away, then keeps only the title row at the top padding.
    val gripExtent=with(LocalDensity.current){(14+if(objects)5 else if(journal)3 else 2).dp.toPx()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val window=(LocalView.current.parent as DialogWindowProvider).window
        // Our themed backdrop already applies the design opacity; Android must not dim it again.
        SideEffect{window.setDimAmount(0f)}
        val backdrop=when(t.id){"magazine"->Color(0x6e1a342c);"journal"->Color(0x62615641);else->Color(0x5291694b)}
        Box(Modifier.fillMaxSize().background(backdrop).navigationBarsPadding().imePadding().testTag("sheet-viewport").pointerInput(onClose){detectTapGestures{if(it.y<sheetTop)onClose()}},contentAlignment=Alignment.BottomCenter) {
            Column(Modifier.onGloballyPositioned{sheetTop=it.positionInParent().y}.fillMaxWidth().heightIn(max=maximum).testTag("theme-sheet").clip(shape).background(background).drawBehind{
                if(journal){
                    val step=30.dp.toPx();var y=size.height-step
                    while(y>0){drawLine(Color(0xffe2d8c6),Offset(0f,y),Offset(size.width,y),1.dp.toPx());y-=step}
                    drawLine(Color(0x44b5695c),Offset(20.dp.toPx(),0f),Offset(20.dp.toPx(),size.height),1.dp.toPx())
                    repeat(3){i->drawRoundRect(Color(0xffa1aeaa),Offset(5.dp.toPx(),(73+i*103).dp.toPx()),androidx.compose.ui.geometry.Size(11.dp.toPx(),8.dp.toPx()),CornerRadius(4.dp.toPx()),style=Stroke(1.5.dp.toPx()))}
                }
                if(!objects)drawLine(if(journal)Color(0xffd5c7a7) else t.ink,Offset(0f,border.dp.toPx()/2),Offset(size.width,border.dp.toPx()/2),border.dp.toPx())
            }.then(if(objects)Modifier.border(1.dp,Color(0xffe5d2b4),shape) else Modifier).padding(start=(left+if(objects)1 else 0).dp,end=(right+if(objects)1 else 0).dp,top=(border+if(objects)12 else if(journal)18 else 20).dp)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides focusScroll) {
                    Column(Modifier.fillMaxWidth().weight(1f,fill=false).verticalScroll(scroll).testTag("sheet-scroll")) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom=14.dp).size(if(objects)31.dp else 35.dp,if(objects)5.dp else if(journal)3.dp else 2.dp).testTag("sheet-grip").then(if(journal)Modifier.rotate(-2f) else Modifier).background(if(objects)Color(0xffd7bea0) else if(journal)Color(0xff99aaa5) else t.ink,if(objects)RoundedCornerShape(6.dp) else RoundedCornerShape(0.dp)))
                        val headerLine=if(objects)Color(0xffdbc29f) else if(journal)t.line else t.ink
                        Column(Modifier.fillMaxWidth().zIndex(4f).graphicsLayer{translationY=(scroll.value-gripExtent).coerceAtLeast(0f)}.padding(bottom=if(journal)17.dp else if(objects)15.dp else 20.dp)) {
                            Row(Modifier.fillMaxWidth().testTag("sheet-header").onSizeChanged{headerHeight=it.height}.background(background).drawBehind{
                                drawLine(headerLine,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=if(journal||objects)PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())) else null)
                            }.padding(bottom=if(journal)14.dp else if(objects)15.dp else 18.dp),horizontalArrangement=Arrangement.spacedBy(if(objects)12.dp else 0.dp),verticalAlignment=Alignment.CenterVertically){
                                YText(title,if(journal)29 else if(objects)if(narrow)23 else 25 else 28,Modifier.weight(1f).testTag("sheet-title"),cssLineBox=true,lineHeightMultiplier=if(journal)42f/29 else if(objects)1.6f else 1.5f,weight=if(objects)FontWeight.Medium else FontWeight.Normal)
                                Box(Modifier.size(if(objects)30.dp else 36.dp).testTag("sheet-close").then(if(objects)Modifier.background(Color(0xfff0e2ca),RoundedCornerShape(11.dp)) else Modifier).semantics{text=AnnotatedString("×");contentDescription=closeLabel}.clickable(onClick=onClose),contentAlignment=Alignment.Center){YIcon("close",Modifier.size(if(objects)16.dp else 18.dp))}
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(bottom=if(journal)27.dp else 30.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                            SheetCaption()
                            Column(Modifier.fillMaxWidth().testTag("sheet-body-content").padding(top=if(objects)14.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){content()}
                            SheetColophon()
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SheetCaption() {
    val t=LocalYohaku.current
    when(t.id){
        "magazine"->Row(Modifier.fillMaxWidth().padding(bottom=1.dp).testTag("sheet-caption"),horizontalArrangement=Arrangement.SpaceBetween){YText("YOHAKU / PERSONAL NOTES",7,color=t.muted,letterSpacing=1f,lineHeightMultiplier=10f/7,cssLineBox=true);YText("01",7,color=t.muted,letterSpacing=1f,lineHeightMultiplier=10f/7,cssLineBox=true)}
        "journal"->Row(Modifier.fillMaxWidth().padding(bottom=1.dp).testTag("sheet-caption").drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=3.dp,bottom=13.dp),horizontalArrangement=Arrangement.SpaceBetween){YText(word("写在这里","ここに記す"),12,color=t.warm,lineHeightMultiplier=17f/12,cssLineBox=true);YText(java.time.YearMonth.now().toString().replace("-"," / "),12,color=t.warm,lineHeightMultiplier=17f/12,cssLineBox=true)}
        else->Row(Modifier.fillMaxWidth().testTag("sheet-caption").drawBehind{drawLine(Color(0xffdbc29f),Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(start=3.dp,end=3.dp,bottom=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(17.dp)){YText(word("留住这一笔日常","日々の一筆を残す"),12,Modifier.weight(1f),color=Color(0xffb39067));SheetStillLife()}
    }
}

@Composable private fun SheetColophon() {
    val t=LocalYohaku.current
    val gap=7.dp // The parent contributes 15dp of the CSS 22dp margin.
    when(t.id){
        "magazine"->YText("THE EVERYDAY EDITION",7,Modifier.fillMaxWidth().padding(top=gap).testTag("sheet-colophon").drawBehind{drawLine(t.line,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=16.dp),color=t.muted,letterSpacing=2f,lineHeightMultiplier=10f/7,cssLineBox=true,textAlign=TextAlign.Center)
        "journal"->YText(word("记好了，就安心。","記して、安心。"),13,Modifier.fillMaxWidth().padding(top=gap).testTag("sheet-colophon").rotate(-1f),color=t.warm,lineHeightMultiplier=19f/13,cssLineBox=true,textAlign=TextAlign.End)
        else->Column(Modifier.fillMaxWidth().padding(top=gap).testTag("sheet-colophon").drawBehind{drawLine(Color(0xffdbc29f),Offset.Zero,Offset(size.width,0f),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx())))}.padding(top=18.dp),horizontalAlignment=Alignment.CenterHorizontally){
            YText(word("生活的小票，收好。","暮らしのレシートを、大切に。"),10,color=Color(0xffb49c7c),lineHeightMultiplier=1.5f,cssLineBox=true)
            Canvas(Modifier.padding(top=13.dp).size(46.dp,14.dp)){var x=0f;while(x<size.width){drawRect(Color(0xffc7b193).copy(alpha=.6f),Offset(x,0f),androidx.compose.ui.geometry.Size(1.dp.toPx(),size.height));x+=3.dp.toPx()}}
        }
    }
}
