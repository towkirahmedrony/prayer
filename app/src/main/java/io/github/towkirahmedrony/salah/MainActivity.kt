package io.github.towkirahmedrony.salah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.towkirahmedrony.salah.navigation.SalahApp

/** Single-activity host for the Compose UI. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SalahApp()
        }
    }
}
