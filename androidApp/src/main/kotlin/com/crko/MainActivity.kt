package com.crko

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        AndroidAppContext.install(this)
        ActivityHolder.attach(this)
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
