package com.chizberg.rewind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.chizberg.rewind.app.RootView
import com.chizberg.rewind.ui.theme.RewindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RewindTheme {
                // Edge-to-edge from day one: the map draws under the system bars.
                RootView(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
