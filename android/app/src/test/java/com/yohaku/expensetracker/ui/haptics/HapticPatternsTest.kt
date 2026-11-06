package com.yohaku.expensetracker.ui.haptics

import android.os.VibrationEffect
import com.yohaku.expensetracker.data.backup.BackupPreferences
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HapticPatternsTest {
    private val themes=listOf("magazine","journal","objects")
    @Test fun everyThemeHasValidDistinctPatterns() {
        Haptic.entries.forEach{haptic->
            val patterns=themes.map{hapticPattern(it,haptic)}
            patterns.forEach{pattern->
                assertTrue(pattern.isNotEmpty())
                pattern.forEach{assertTrue(it.scale>0f&&it.scale<=1f);assertTrue(it.delayMs>=0)}
            }
            assertEquals("$haptic must feel different in each theme",3,patterns.toSet().size)
            assertEquals(patterns.first(),hapticPattern("unknown",haptic))
        }
    }
    @Test fun everyTokenHasFallbackOnEachSupportedApi() {
        listOf(29,30,31,33,34,37).forEach{sdk->Haptic.entries.forEach{haptic->
            val fallback=hapticFallback(haptic,sdk)
            assertTrue(fallback.effect in listOf(VibrationEffect.EFFECT_CLICK,VibrationEffect.EFFECT_TICK,VibrationEffect.EFFECT_DOUBLE_CLICK,VibrationEffect.EFFECT_HEAVY_CLICK))
            if(sdk==29||haptic==Haptic.Removed)assertNull(fallback.feedback)else assertNotNull(fallback.feedback)
            if(haptic==Haptic.Removed)assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK,fallback.effect)
        }}
    }
    @Test fun disabledFacadeIsSilentAndThemeOverrideIsForwarded() {
        val calls=mutableListOf<Triple<String,Haptic,Float>>()
        val output=HapticOutput{theme,haptic,scale->calls+=Triple(theme,haptic,scale)}
        Haptics(output,"magazine",false).perform(Haptic.Error)
        assertTrue(calls.isEmpty())
        Haptics(output,"magazine",true).perform(Haptic.Select,1.3f,"objects")
        assertEquals(listOf(Triple("objects",Haptic.Select,1.3f)),calls)
    }
    @Test fun legacyBackupEnablesHapticsAndExplicitFalseSurvives() {
        assertTrue(Json.decodeFromString<BackupPreferences>("{}").hapticsEnabled)
        val value=BackupPreferences(hapticsEnabled=false)
        assertEquals(value,Json.decodeFromString<BackupPreferences>(Json.encodeToString(BackupPreferences.serializer(),value)))
    }
}
