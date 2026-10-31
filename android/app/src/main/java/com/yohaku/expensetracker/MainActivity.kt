package com.yohaku.expensetracker
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yohaku.expensetracker.ui.features.*
class MainActivity:ComponentActivity() {
    private val reminderBook=kotlinx.coroutines.flow.MutableStateFlow<Pair<String,String?>?>(null)
    override fun onNewIntent(intent:android.content.Intent) {super.onNewIntent(intent);setIntent(intent);readReminderIntent(intent)}
    private fun readReminderIntent(intent:android.content.Intent) {
        if(intent.action==com.yohaku.expensetracker.platform.ReminderCoordinator.ACTION_OPEN) {
            intent.getStringExtra(com.yohaku.expensetracker.platform.ReminderCoordinator.EXTRA_BOOK)?.let { book ->
                reminderBook.value=book to intent.getStringExtra(com.yohaku.expensetracker.platform.ReminderCoordinator.EXTRA_ITEM)
            }
            intent.removeExtra(com.yohaku.expensetracker.platform.ReminderCoordinator.EXTRA_BOOK)
            intent.removeExtra(com.yohaku.expensetracker.platform.ReminderCoordinator.EXTRA_ITEM)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        readReminderIntent(intent)
        // All three app designs use light paper backgrounds, independently of system night mode.
        enableEdgeToEdge(
            statusBarStyle=SystemBarStyle.light(Color.TRANSPARENT,Color.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.light(Color.TRANSPARENT,Color.TRANSPARENT)
        )
        val resultFile=java.io.File(noBackupFilesDir,"backup-result.txt")
        val resultText=resultFile.takeIf{it.exists()}?.readText()
        resultFile.delete()
        setContent {val vm:AppViewModel=viewModel(factory=AppViewModel.factory(application as YohakuApplication))
            val target=reminderBook.collectAsState().value
            androidx.compose.runtime.LaunchedEffect(target){target?.let{vm.openReminderBook(it.first,it.second);reminderBook.value=null}}
            var backupResult by rememberSaveable {mutableStateOf(resultText)}
            CompositionLocalProvider(LocalReceiptCamera provides rememberReceiptCamera(vm)){NativeApp(vm,backupResult,{backupResult=null})}
        }
    }
}
