package com.yohaku.expensetracker

import com.yohaku.expensetracker.ui.haptics.Haptic
import com.yohaku.expensetracker.ui.haptics.HapticOutput
import java.util.concurrent.CopyOnWriteArrayList

class RecordingHapticOutput:HapticOutput {
    val calls=CopyOnWriteArrayList<Triple<String,Haptic,Float>>()
    override fun play(theme:String,haptic:Haptic,scale:Float){calls+=Triple(theme,haptic,scale)}
    fun clear(){calls.clear()}
}
