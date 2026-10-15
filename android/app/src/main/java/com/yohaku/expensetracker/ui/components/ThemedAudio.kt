package com.yohaku.expensetracker.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ThemedAudio(name:String,bytes:Long,position:Int,duration:Int,playing:Boolean,ready:Boolean,onPlay:()->Unit,onSeek:(Int)->Unit) {
    val t=LocalYohaku.current
    val playLabel=if(playing)word("暂停","一時停止")else word("播放","再生")
    Column(Modifier.fillMaxWidth().padding(vertical=12.dp).testTag("themed-audio"),horizontalAlignment=Alignment.CenterHorizontally) {
        AudioCover()
        YText("A NOTE TO MYSELF",9,Modifier.padding(top=24.dp),color=t.muted,letterSpacing=1.4f)
        YText(name,if(t.id=="journal")26 else 22,Modifier.fillMaxWidth().padding(top=18.dp,bottom=9.dp),textAlign=TextAlign.Center)
        YText("${bytes} "+word("字节 · 音频播放","バイト・音声再生"),10,color=t.muted,lineHeightMultiplier=1.9f)
        // This is the original decorative audio motif, not a fabricated signal analysis.
        Canvas(Modifier.fillMaxWidth().padding(vertical=22.dp).height(64.dp)) {
            val bar=(if(t.id=="objects")4 else 3).dp.toPx();val gap=4.dp.toPx();val left=(size.width-(33*bar+32*gap))/2
            repeat(33){i->val height=(12+i*17%34).dp.toPx();val x=left+i*(bar+gap);val y=(size.height-height)/2
                rotate(if(t.id=="journal")-6f else 0f,Offset(x+bar/2,size.height/2)) {
                    drawRoundRect(t.accent.copy(alpha=if(t.id=="objects").4f else .45f),Offset(x,y),androidx.compose.ui.geometry.Size(bar,height),androidx.compose.ui.geometry.CornerRadius(if(t.id=="objects")4.dp.toPx() else if(t.id=="journal")1.dp.toPx()else 0f))
                }
            }
        }
        val objectShape=RoundedCornerShape(19.dp)
        val frame=when(t.id) {
            "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=20.dp)
            "journal"->Modifier.rotate(-.4f).background(Color(0xfffff8e5)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(17.dp)
            else->Modifier.drawBehind{drawRoundRect(Color(0x28cbb08a),topLeft=Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(19.dp.toPx()))}.background(Color(0xfff1e5cf),objectShape).padding(17.dp)
        }
        Row(Modifier.fillMaxWidth().padding(vertical=20.dp).then(frame),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            val playFrame=when(t.id) {
                "magazine"->Modifier.border(1.dp,t.ink)
                "journal"->Modifier.border(1.dp,t.accent,RoundedCornerShape(19.dp,16.dp,19.dp,16.dp))
                else->Modifier.drawBehind{drawRoundRect(Color(0x309d673d),topLeft=Offset(0f,3.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))}.background(Color(0xffb77b53),RoundedCornerShape(14.dp))
            }
            Box(Modifier.size(42.dp).testTag("media-audio-play").then(playFrame).semantics(mergeDescendants=true){text=AnnotatedString(playLabel)}.clickable(enabled=ready,role=Role.Button,onClick=onPlay),contentAlignment=Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    val color=if(t.id=="objects")Color(0xfffff9e9)else t.accent
                    if(playing){drawRect(color,Offset(size.width*.15f,0f),androidx.compose.ui.geometry.Size(size.width*.23f,size.height));drawRect(color,Offset(size.width*.62f,0f),androidx.compose.ui.geometry.Size(size.width*.23f,size.height))}
                    else {val path=Path().apply{moveTo(size.width*.2f,0f);lineTo(size.width*.95f,size.height/2);lineTo(size.width*.2f,size.height);close()};drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx()))}
                }
            }
            Column(Modifier.weight(1f)) {
                AudioSeek(position,duration,ready,onSeek)
                Row(Modifier.fillMaxWidth().padding(top=9.dp).testTag("media-audio-position").clearAndSetSemantics{text=AnnotatedString("${position/1000}s / ${duration/1000}s")},horizontalArrangement=Arrangement.SpaceBetween) {
                    YText(audioTime(position),10,number=true,color=t.muted)
                    YText(if(ready)audioTime(duration)else "—",10,number=true,color=t.muted)
                }
            }
        }
        YText(word("仅提供播放与进度控制，不识别音频内容。","再生とシークのみ。音声認識は行いません。"),10,color=t.muted,lineHeightMultiplier=1.9f,textAlign=TextAlign.Center)
    }
}
private fun audioTime(milliseconds:Int):String="%02d:%02d".format(milliseconds/60000,milliseconds/1000%60)
@Composable private fun AudioCover() {
    val t=LocalYohaku.current;val shape=RoundedCornerShape(17.dp,21.dp,25.dp,17.dp)
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawRect(t.soft,Offset(5.dp.toPx(),5.dp.toPx()));drawRect(t.bg)}.border(1.dp,t.ink)
        "journal"->Modifier.rotate(-4f).background(Color(0xffe4e8db)).drawBehind{drawRect(t.line,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}
        else->Modifier.rotate(-5f).drawBehind{drawRoundRect(Color(0x2dbb936b),topLeft=Offset(0f,4.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()))}.background(Color(0xffe9d3ba),shape)
    }
    Box(Modifier.size(90.dp,100.dp).then(frame),contentAlignment=Alignment.Center) {
        YIcon("audio",Modifier.size(45.dp),if(t.id=="objects")Color(0xffad7554)else t.ink,stroke=1.3f)
    }
}
@Composable private fun AudioSeek(position:Int,duration:Int,ready:Boolean,onSeek:(Int)->Unit) {
    val t=LocalYohaku.current;var width by remember{mutableIntStateOf(1)}
    val seekLabel=word("播放进度","再生位置")
    fun seek(value:Float){onSeek(value.toInt().coerceIn(0,duration))}
    var dragPosition by remember{mutableFloatStateOf(0f)}
    val drag=rememberDraggableState{delta->dragPosition+=delta*duration/width;seek(dragPosition)}
    Canvas(Modifier.fillMaxWidth().height(22.dp).testTag("media-audio-progress").onSizeChanged{width=it.width.coerceAtLeast(1)}.semantics {
        contentDescription=seekLabel;progressBarRangeInfo=ProgressBarRangeInfo(position.toFloat(),0f..duration.coerceAtLeast(1).toFloat())
        if(ready)setProgress{seek(it);true}else disabled()
    }.draggable(drag,Orientation.Horizontal,enabled=ready,startDragImmediately=true,onDragStarted={start->dragPosition=start.x*duration/width;seek(dragPosition)})) {
        val fraction=if(duration==0)0f else position.toFloat()/duration
        val radius=if(t.id=="magazine")0f else 3.dp.toPx();val thickness=(if(t.id=="magazine")2 else 3).dp.toPx();val y=(size.height-thickness)/2
        drawRoundRect(t.line,Offset(0f,y),androidx.compose.ui.geometry.Size(size.width,thickness),androidx.compose.ui.geometry.CornerRadius(radius))
        drawRoundRect(t.accent,Offset(0f,y),androidx.compose.ui.geometry.Size(size.width*fraction,thickness),androidx.compose.ui.geometry.CornerRadius(radius))
        val x=(size.width*fraction).coerceIn(5.dp.toPx(),size.width-5.dp.toPx())
        if(t.id=="magazine")drawRect(t.accent,Offset(x-4.dp.toPx(),size.height/2-5.dp.toPx()),androidx.compose.ui.geometry.Size(8.dp.toPx(),10.dp.toPx()))
        else drawCircle(if(t.id=="objects")Color(0xffb77b53)else t.accent,5.dp.toPx(),Offset(x,size.height/2))
    }
}
