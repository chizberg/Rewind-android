package com.chizberg.rewind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import com.chizberg.rewind.app.RootView
import com.chizberg.rewind.ui.theme.RewindTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the root map minimises to the launcher (like Home) instead of finishing the
        // activity — matching iOS, where back never destroys the app. A finish() would clear the
        // ViewModel (and with it the whole loaded map) and drop saved state, so returning would
        // rebuild from the initial world view. Registered before setContent, so overlay back
        // callbacks (added later during composition) win by LIFO; this fires only at the root.
        onBackPressedDispatcher.addCallback(this) { this@MainActivity.moveTaskToBack(true) }
        enableEdgeToEdge()
        setContent {
            // Re-apply the edge-to-edge bar styles whenever the system theme flips. A theme change
            // does NOT recreate this activity (`configChanges=uiMode`, kept deliberately so the
            // loaded map survives), so the one-shot enableEdgeToEdge() in onCreate would leave the
            // status-bar icons stuck in their launch-time light/dark form. Re-running it here — on
            // the same recomposition that re-themes Compose — flips the bar icons with no teardown.
            val darkTheme = isSystemInDarkTheme()
            DisposableEffect(darkTheme) {
                enableEdgeToEdge()
                onDispose {}
            }
            RewindTheme {
                // Edge-to-edge from day one: the map draws under the system bars.
                RootView(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
