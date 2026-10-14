package com.yohaku.expensetracker
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yohaku.expensetracker.ui.features.*
class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent {val vm:AppViewModel=viewModel(factory=AppViewModel.factory(application as YohakuApplication));NativeApp(vm)}
    }
}
