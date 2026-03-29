package com.patentia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.patentia.ui.AppViewModel
import com.patentia.ui.PatentIAApp

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<AppViewModel> {
        AppViewModel.factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PatentIAApp(viewModel = viewModel)
        }
    }
}