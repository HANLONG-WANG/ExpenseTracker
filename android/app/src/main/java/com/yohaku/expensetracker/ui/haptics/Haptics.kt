package com.yohaku.expensetracker.ui.haptics

import androidx.compose.runtime.staticCompositionLocalOf

enum class Haptic { Select, TabSwitch, ToggleOn, ToggleOff, Step, PageTurn, Limit, Compose, Grab, Media, Saved, Done, Removed, Restored, Warning, Error }

fun interface HapticOutput { fun play(theme:String,haptic:Haptic,scale:Float) }

class Haptics(private val output:HapticOutput,private val theme:String,private val enabled:Boolean) {
    fun perform(haptic:Haptic,scale:Float=1f,theme:String=this.theme){if(enabled)output.play(theme,haptic,scale)}
}
val LocalHaptics=staticCompositionLocalOf{Haptics(HapticOutput{_,_,_->},"magazine",false)}
