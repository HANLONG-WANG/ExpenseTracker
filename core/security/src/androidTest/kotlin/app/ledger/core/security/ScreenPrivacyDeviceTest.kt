package app.ledger.core.security

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenPrivacyDeviceTest {
    @Test
    fun vaultAndBackgroundPoliciesSetRealWindowSecureFlagIndependently() {
        ActivityScenario.launch(SecurityPromptTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = AndroidScreenPrivacyController(activity)
                controller.apply(ScreenPrivacyPolicy())
                assertFalse(activity.hasSecureFlag())

                controller.apply(ScreenPrivacyPolicy(vaultVisible = true))
                assertTrue(activity.hasSecureFlag())

                controller.apply(ScreenPrivacyPolicy(applicationInBackground = true))
                assertTrue(activity.hasSecureFlag())

                controller.apply(ScreenPrivacyPolicy(obscureRecentTasks = false, globalFlagSecure = true))
                assertTrue(activity.hasSecureFlag())

                controller.apply(ScreenPrivacyPolicy(obscureRecentTasks = false))
                assertFalse(activity.hasSecureFlag())
            }
        }
    }

    private fun SecurityPromptTestActivity.hasSecureFlag(): Boolean = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
