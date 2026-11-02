package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.SpaceAgentApp
import com.example.ui.SpaceAgentViewModel
import com.example.ui.theme.SpaceAgentTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SpaceAgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpaceAgentTheme {
                SpaceAgentApp(viewModel = viewModel)
            }
        }
    }
}
