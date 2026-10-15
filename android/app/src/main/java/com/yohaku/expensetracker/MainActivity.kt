package com.yohaku.expensetracker
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yohaku.expensetracker.ui.features.*
class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // All three app designs use light paper backgrounds, independently of system night mode.
        enableEdgeToEdge(
            statusBarStyle=SystemBarStyle.light(Color.TRANSPARENT,Color.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.light(Color.TRANSPARENT,Color.TRANSPARENT)
        )
        setContent {val vm:AppViewModel=viewModel(factory=AppViewModel.factory(application as YohakuApplication));NativeApp(vm)}
    }
}
