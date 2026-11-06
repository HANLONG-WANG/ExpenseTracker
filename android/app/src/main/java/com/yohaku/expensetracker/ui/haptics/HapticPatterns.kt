package com.yohaku.expensetracker.ui.haptics

import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.*
import android.view.HapticFeedbackConstants

internal data class HapticStep(val primitive:Int,val scale:Float,val delayMs:Int=0)

internal fun hapticPattern(theme:String,haptic:Haptic):List<HapticStep> = when(theme) {
    "journal"->when(haptic) {
        Haptic.Select->listOf(HapticStep(PRIMITIVE_LOW_TICK,.7f))
        Haptic.TabSwitch->listOf(HapticStep(PRIMITIVE_LOW_TICK,.5f),HapticStep(PRIMITIVE_TICK,.3f,30))
        Haptic.ToggleOn->listOf(HapticStep(PRIMITIVE_TICK,.5f),HapticStep(PRIMITIVE_CLICK,.6f,45))
        Haptic.ToggleOff->listOf(HapticStep(PRIMITIVE_LOW_TICK,.6f),HapticStep(PRIMITIVE_LOW_TICK,.4f,30))
        Haptic.Step->listOf(HapticStep(PRIMITIVE_LOW_TICK,.55f))
        Haptic.PageTurn->listOf(HapticStep(PRIMITIVE_SLOW_RISE,.3f),HapticStep(PRIMITIVE_QUICK_FALL,.45f))
        Haptic.Limit->listOf(HapticStep(PRIMITIVE_THUD,.5f))
        Haptic.Compose->listOf(HapticStep(PRIMITIVE_LOW_TICK,.5f),HapticStep(PRIMITIVE_CLICK,.7f,60))
        Haptic.Grab->listOf(HapticStep(PRIMITIVE_LOW_TICK,.4f))
        Haptic.Media->listOf(HapticStep(PRIMITIVE_LOW_TICK,.6f))
        Haptic.Saved->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.5f),HapticStep(PRIMITIVE_THUD,1f))
        Haptic.Done->listOf(HapticStep(PRIMITIVE_THUD,.7f))
        Haptic.Removed->listOf(HapticStep(PRIMITIVE_LOW_TICK,.7f),HapticStep(PRIMITIVE_LOW_TICK,.6f,18),HapticStep(PRIMITIVE_LOW_TICK,.5f,22),HapticStep(PRIMITIVE_LOW_TICK,.35f,26))
        Haptic.Restored->listOf(HapticStep(PRIMITIVE_SLOW_RISE,.4f),HapticStep(PRIMITIVE_TICK,.6f))
        Haptic.Warning->listOf(HapticStep(PRIMITIVE_THUD,.6f),HapticStep(PRIMITIVE_LOW_TICK,.6f,140))
        Haptic.Error->listOf(HapticStep(PRIMITIVE_THUD,.9f),HapticStep(PRIMITIVE_THUD,.9f,90))
    }
    "objects"->when(haptic) {
        Haptic.Select->listOf(HapticStep(PRIMITIVE_CLICK,.35f))
        Haptic.TabSwitch->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.25f),HapticStep(PRIMITIVE_CLICK,.5f))
        Haptic.ToggleOn->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.35f),HapticStep(PRIMITIVE_CLICK,.8f))
        Haptic.ToggleOff->listOf(HapticStep(PRIMITIVE_QUICK_FALL,.5f))
        Haptic.Step->listOf(HapticStep(PRIMITIVE_CLICK,.45f))
        Haptic.PageTurn->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.3f),HapticStep(PRIMITIVE_THUD,.5f,40))
        Haptic.Limit->listOf(HapticStep(PRIMITIVE_THUD,.7f))
        Haptic.Compose->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.3f),HapticStep(PRIMITIVE_THUD,.6f))
        Haptic.Grab->listOf(HapticStep(PRIMITIVE_CLICK,.3f))
        Haptic.Media->listOf(HapticStep(PRIMITIVE_CLICK,.5f))
        Haptic.Saved->listOf(HapticStep(PRIMITIVE_CLICK,1f),HapticStep(PRIMITIVE_TICK,.6f,70),HapticStep(PRIMITIVE_TICK,.35f,60),HapticStep(PRIMITIVE_TICK,.15f,50))
        Haptic.Done->listOf(HapticStep(PRIMITIVE_CLICK,.8f),HapticStep(PRIMITIVE_TICK,.4f,70))
        Haptic.Removed->listOf(HapticStep(PRIMITIVE_SLOW_RISE,.4f),HapticStep(PRIMITIVE_THUD,.9f))
        Haptic.Restored->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.4f),HapticStep(PRIMITIVE_CLICK,.7f))
        Haptic.Warning->listOf(HapticStep(PRIMITIVE_THUD,.5f),HapticStep(PRIMITIVE_THUD,.5f,110))
        Haptic.Error->listOf(HapticStep(PRIMITIVE_THUD,.9f),HapticStep(PRIMITIVE_QUICK_FALL,.6f,60))
    }
    else->when(haptic) {
        Haptic.Select->listOf(HapticStep(PRIMITIVE_TICK,.45f))
        Haptic.TabSwitch->listOf(HapticStep(PRIMITIVE_TICK,.6f))
        Haptic.ToggleOn->listOf(HapticStep(PRIMITIVE_CLICK,.6f))
        Haptic.ToggleOff->listOf(HapticStep(PRIMITIVE_TICK,.5f))
        Haptic.Step->listOf(HapticStep(PRIMITIVE_TICK,.4f))
        Haptic.PageTurn->listOf(HapticStep(PRIMITIVE_QUICK_FALL,.4f),HapticStep(PRIMITIVE_TICK,.5f,20))
        Haptic.Limit->listOf(HapticStep(PRIMITIVE_TICK,.8f),HapticStep(PRIMITIVE_TICK,.4f,40))
        Haptic.Compose->listOf(HapticStep(PRIMITIVE_CLICK,.7f))
        Haptic.Grab->listOf(HapticStep(PRIMITIVE_TICK,.3f))
        Haptic.Media->listOf(HapticStep(PRIMITIVE_TICK,.5f))
        Haptic.Saved->listOf(HapticStep(PRIMITIVE_TICK,.5f),HapticStep(PRIMITIVE_CLICK,.9f,70))
        Haptic.Done->listOf(HapticStep(PRIMITIVE_CLICK,.7f))
        Haptic.Removed->listOf(HapticStep(PRIMITIVE_QUICK_FALL,.7f))
        Haptic.Restored->listOf(HapticStep(PRIMITIVE_QUICK_RISE,.5f),HapticStep(PRIMITIVE_TICK,.6f))
        Haptic.Warning->listOf(HapticStep(PRIMITIVE_CLICK,.6f),HapticStep(PRIMITIVE_CLICK,.6f,120))
        Haptic.Error->listOf(HapticStep(PRIMITIVE_CLICK,.9f),HapticStep(PRIMITIVE_CLICK,.9f,80),HapticStep(PRIMITIVE_CLICK,.9f,80))
    }
}

internal data class HapticFallback(val feedback:Int?,val effect:Int)
internal fun hapticFallback(haptic:Haptic,sdk:Int):HapticFallback {
    val effect=when(haptic) {
        Haptic.Select,Haptic.TabSwitch,Haptic.Step,Haptic.Grab,Haptic.Media,Haptic.ToggleOff->VibrationEffect.EFFECT_TICK
        Haptic.ToggleOn,Haptic.Saved,Haptic.Done,Haptic.Restored,Haptic.Compose,Haptic.PageTurn->VibrationEffect.EFFECT_CLICK
        Haptic.Warning,Haptic.Error,Haptic.Limit->VibrationEffect.EFFECT_DOUBLE_CLICK
        Haptic.Removed->VibrationEffect.EFFECT_HEAVY_CLICK
    }
    val feedback=if(sdk<30||haptic==Haptic.Removed)null else when(haptic) {
        Haptic.Select,Haptic.TabSwitch,Haptic.Step,Haptic.Grab,Haptic.Media->if(sdk>=34)HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        Haptic.ToggleOn->if(sdk>=34)HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.ToggleOff->if(sdk>=34)HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        Haptic.Saved,Haptic.Done,Haptic.Restored,Haptic.Compose,Haptic.PageTurn->HapticFeedbackConstants.CONFIRM
        Haptic.Warning,Haptic.Error,Haptic.Limit->HapticFeedbackConstants.REJECT
        Haptic.Removed->null
    }
    return HapticFallback(feedback,effect)
}
