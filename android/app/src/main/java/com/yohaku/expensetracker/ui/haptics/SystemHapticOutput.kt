package com.yohaku.expensetracker.ui.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

class SystemHapticOutput(private val context:Context,private val view:View):HapticOutput {
    private val vibrator=if(Build.VERSION.SDK_INT>=31)context.getSystemService(VibratorManager::class.java).defaultVibrator else context.getSystemService(Vibrator::class.java)
    private val supported=mutableMapOf<Int,Boolean>()
    private var pending:(()->Unit)?=null
    override fun play(theme:String,haptic:Haptic,scale:Float) {
        // UI feedback within one frame is coalesced; the final semantic event wins.
        val scheduled=pending!=null
        pending={playNow(theme,haptic,scale)}
        if(!scheduled)view.postOnAnimation{val next=pending;pending=null;next?.invoke()}
    }
    private fun playNow(theme:String,haptic:Haptic,scale:Float) {
        if(!vibrator.hasVibrator()||Settings.System.getInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1)==0)return
        val pattern=hapticPattern(theme,haptic)
        if(Build.VERSION.SDK_INT>=30&&pattern.all{step->supported.getOrPut(step.primitive){vibrator.areAllPrimitivesSupported(step.primitive)}}) {
            val composition=VibrationEffect.startComposition()
            pattern.forEach{composition.addPrimitive(it.primitive,(it.scale*scale).coerceIn(.001f,1f),it.delayMs)}
            vibrate(composition.compose())
        }else {
            val fallback=hapticFallback(haptic,Build.VERSION.SDK_INT)
            if(fallback.feedback!=null)view.performHapticFeedback(fallback.feedback)
            else vibrate(VibrationEffect.createPredefined(fallback.effect))
        }
    }
    private fun vibrate(effect:VibrationEffect) {
        if(Build.VERSION.SDK_INT>=33)vibrator.vibrate(effect,VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build())
        else vibrator.vibrate(effect,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
    }
}

@Composable fun rememberSystemHapticOutput():HapticOutput {
    val context=LocalContext.current;val view=LocalView.current
    return remember(context,view){SystemHapticOutput(context,view)}
}
