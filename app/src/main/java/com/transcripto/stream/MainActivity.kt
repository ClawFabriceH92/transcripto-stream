package com.transcripto.stream
import com.transcripto.stream.update.UpdateManager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.transcripto.stream.ui.StreamScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UpdateManager.start(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    StreamScreen()
                }
            }
        }
    }
}
