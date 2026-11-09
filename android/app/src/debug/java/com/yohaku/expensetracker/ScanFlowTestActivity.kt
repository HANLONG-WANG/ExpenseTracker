package com.yohaku.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

/** Debug-only host: re-installs the test content after a real Activity recreation. No launcher. */
class ScanFlowTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content() }
    }
    companion object {
        var content by mutableStateOf<@Composable () -> Unit>({})
    }
}
