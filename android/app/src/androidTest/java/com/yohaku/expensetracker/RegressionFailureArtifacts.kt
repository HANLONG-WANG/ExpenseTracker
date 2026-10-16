package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Preserve the failing screen and semantics before the outer Compose rule disposes its activity. */
class RegressionFailureArtifacts(private val ui:ComposeContentTestRule):TestWatcher() {
    override fun failed(error:Throwable,description:Description) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-e-regression-failures").also{it.mkdirs()}
        val name=description.testClass.simpleName+"-"+description.methodName
        File(folder,"$name.txt").writeText(error.stackTraceToString()+"\n"+runCatching{ui.onRoot().printToString()}.getOrElse{it.stackTraceToString()})
        instrumentation.uiAutomation.takeScreenshot()?.let{screen->
            try{File(folder,"$name.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{screen.recycle()}
        }
    }
}
