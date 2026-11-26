package dev.leash.personal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.leash.personal.ui.LeashRoot
import dev.leash.personal.ui.theme.LeashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LeashTheme {
                LeashRoot()
            }
        }
    }
}
